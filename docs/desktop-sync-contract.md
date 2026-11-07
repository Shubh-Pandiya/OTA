# Desktop sync contract

How the (future) Windows desktop app syncs with the Supabase cloud. The phones already follow
these rules. Schema: `supabase/migrations/20261107000000_catalog.sql`,
`supabase/migrations/20261108000000_orders.sql` and
`supabase/migrations/20261109000000_payment_advice.sql`.

## Roles

| | Phones | Cloud | Desktop |
|---|---|---|---|
| Buyers, suppliers | full copy | **permanent** full copy | full copy |
| Orders, bills, order_bills, payment_advices, advice_lines | their own rows, plus anything pulled | only the last `retention_days` (by order / payment date) | **every row ever**, the system of record |

The desktop signs in with an **admin** staff login (`staff.is_admin = true`). Only admins can
archive, purge or change settings.

## Tables

Every synced table has `id uuid` (generated on the device), `deleted boolean` (soft delete; there
are no hard deletes from clients), `updated_at timestamptz` (set by the server on every write),
and `client_updated_at bigint` (the editing device's clock in epoch ms).

| Table | Columns (besides the sync columns) |
|---|---|
| `buyers`, `suppliers` | name, address, city, phone, gstin, bank fields (see the catalog migration) |
| `orders` | order_no, order_date (`dd-MM-yyyy` text, as printed), buyer, supplier (text as printed), items (`[[s.no, particulars, qty, rate], …]`), remarks, delivery_ready, transport_to, cash_payment, less_disc, side, created_by, archived_at |
| `bills` | bill_no, bill_date (`date`), amount (`numeric(14,2)`), lr_no (LR / bilty no, `''` if none), cd_percent (`numeric(6,3)`), cd_amount (`numeric(14,2)`), archived_at |
| `order_bills` | order_id, bill_id, archived_at. Many-to-many. **No foreign keys**, see below. |
| `payment_advices` | advice_no, pay_date (`date`), buyer (paid by), supplier (paid to), mode (`CHEQUE`/`ONLINE`/`CASH`), reference (cheque no / txn id), balance (`numeric(14,2)`, Dr/Cr balance as entered), balance_side (`DR`/`CR`/null), remarks, created_by, archived_at |
| `advice_lines` | advice_id, bill_id, paid, cd_percent, cd_amount, goods_return, rate_diff, archived_at. Advice ↔ bill is many-to-many. **No foreign keys.** |
| `staff` | user_id, code (1–9, first digit of the order number), name, is_admin |
| `app_settings` | single row (`id = true`): retention_days (30), storage_limit_mb (500), warn_percent (80) |

Bill and link ids are deterministic, so the same bill typed on two devices is one row:

- `bills.id = UUID.nameUUIDFromBytes("ota-bill:" + billKey)`
- `order_bills.id = UUID.nameUUIDFromBytes("ota-link:" + orderId + ":" + billId)`
- `advice_lines.id = UUID.nameUUIDFromBytes("ota-advice-line:" + adviceId + ":" + billId)` (a bill
  appears at most once per advice)

These use Java's MD5-based UUIDs over UTF-8 bytes. `billKey` is the bill number trimmed, with whitespace runs collapsed to one space,
upper-cased.

Order numbers are `{staff code}{yyyyMMdd}{daily serial, 3 digits}` (e.g. `120261107001`), unique in the cloud.
A few early test numbers have an unpadded serial (e.g. `9202611071`); treat the digits after the
date as the serial in both cases.
Older numbers (`yyyyMMdd` + 2 digits) and manual numbers may repeat.

Payment advice numbers are `PA{staff code}{yyyyMMdd}{daily serial, 3 digits}` (e.g.
`PA120261108001`) with their own daily counter, unique in the cloud (`23505` on a duplicate).

A bill's balance is `amount − Σ(paid + cd_amount + goods_return + rate_diff)` over its
non-deleted lines whose advice is not deleted. Status: no lines → Unpaid, balance ≤ 0 → Paid,
otherwise Partly paid (a bill without an amount is Partly paid once it has a line).

## Pulling (delta sync)

For each table, keep a cursor `(updated_at, id)` of the last row received and page with:

```
GET /rest/v1/<table>?select=<cols>&order=updated_at.asc,id.asc&limit=500
    &or=(updated_at.gt."<cursor>",and(updated_at.eq."<cursor>",id.gt.<cursor id>))
```

Pull `orders`, then `bills`, then `order_bills`, then `payment_advices`, then `advice_lines`. Store `updated_at` **exactly as returned**
(microseconds), because archiving needs it. Apply with last-write-wins on `client_updated_at`; a
newer local edit that hasn't been uploaded yet is kept. Accept links whose order or bill you
haven't seen yet: keep them, or retry them after the next pull.

## Pushing

`POST /rest/v1/<table>?on_conflict=id` with `Prefer: resolution=merge-duplicates,return=minimal`
and a JSON array of rows (at most 200). Send `client_updated_at` as the local edit time. The server
keeps the stored row when an incoming update has an older `client_updated_at`. Never send
`updated_at`, `archived_at` or `created_by`; the server sets them. A `23505` error on `orders`
or `payment_advices` means that order / advice number is already taken.

## Archive, then purge (retention)

1. **Pull** everything (as above) and commit it to the desktop database.
2. **Mark the stored versions as archived**, per table, in batches:
   ```
   POST /rest/v1/rpc/ota_mark_archived
   {"p_table": "orders", "p_rows": [{"id": "…", "updated_at": "<as pulled>"}, …]}
   ```
   `p_table` is one of `orders`, `bills`, `order_bills`, `payment_advices`, `advice_lines`.
   This returns the number of rows marked. A row edited after your pull is skipped; it comes back
   in the next pull and is archived then. Archiving doesn't change `updated_at`, so phones don't
   download anything again.
3. **Purge**: `POST /rest/v1/rpc/ota_purge_archived_orders` with `{}`. It deletes:
   - orders dated (by order date) more than `retention_days` ago that are archived and unchanged
     since (`updated_at <= archived_at`),
   - the archived, unchanged links of those orders (and old archived links whose order is gone),
   - payment advices dated (by pay_date) before the cutoff that are archived and unchanged,
   - their archived, unchanged lines (and old archived lines whose advice is gone),
   - archived, unchanged bills that no order link and no advice line points to any more.

   It returns `{"orders": n, "order_bills": n, "payment_advices": n, "advice_lines": n, "bills": n,
   "cutoff": "yyyy-mm-dd", "retention_days": n}`.
   **A row that isn't archived is never deleted.** Buyers and suppliers are never purged.

Run steps 1–3 every time the desktop connects, and on a timer while it runs.

### Changing retention

An admin updates the single settings row:

```
PATCH /rest/v1/app_settings?id=eq.true   {"retention_days": 15}
```

Then run steps 1–3 to free the space straight away. Postgres reuses the freed space automatically;
the reported database size may only drop after autovacuum.

### Late edits and late bills

- A phone may edit or delete an order that has already been purged from the cloud. The upload
  re-creates the row; the desktop pulls it and upserts it by id (last-write-wins on
  `client_updated_at`), and it gets archived and purged again.
- Bills often arrive weeks after the order. Phones upload the link and the bill (a bill is
  re-uploaded every time it is attached), even when the order has already left the cloud. That
  is why `order_bills` has no foreign keys. The desktop joins them by id because it holds every order.
- Payments arrive later still. A phone re-uploads a paid bill with every advice line that
  points at it, so the desktop can always resolve `advice_lines.bill_id`, even after the bill
  and its orders have left the cloud.
- A purge never deletes anything on a phone. Purged rows just stop appearing in pulls, and no
  tombstones are created for them.

## Storage monitoring

`POST /rest/v1/rpc/ota_cloud_status` with `{}` returns
`{"used_bytes", "limit_mb", "warn_percent", "retention_days", "percent"}`, where `used_bytes` comes
from `pg_database_size`. Phones call it on every sync. When `percent >= warn_percent` they show a
notification and a banner saying to open the desktop app and archive orders. The desktop should
do the same and offer to lower retention.

Supabase Free plan limits:

- **500 MB database size quota.** This is the `storage_limit_mb` default. Going over it can get the
  project restricted, with requests failing with HTTP 402.
- **2 GB disk.** It turns **read-only at 95 %**; writes then fail with Postgres error `25006`.
- The 1 GB in the plan is file storage, which this app doesn't use.

When a write fails with 402, `25006` or `53100`, keep the change locally as pending and retry
later. Never drop it.

## Size guide

An order takes about 1 KB including one bill and its link (row data plus indexes). Items are
stored as compact JSON, and no PDFs are stored. A payment advice with two lines is under 1 KB. With about 450 MB usable, the cloud could hold
roughly 400 000 orders; with 30-day retention it only ever holds one month's worth.
