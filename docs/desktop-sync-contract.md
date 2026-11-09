# Desktop sync contract

How the Windows desktop app (`desktop/`) syncs with the Supabase cloud. The phones follow the
same rules. Schema: every file in `supabase/migrations/`, run in name order (catalog, orders,
payment advice, bill lines and claims, the advice-image cleanup, financial-year order numbers,
bill parties).

Only text data syncs. Advice images (cheque photos, transfer screenshots) and the Home to-dos /
reminders stay on the phone that created them and are never uploaded.

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
| `orders` | order_no, order_date (`dd-MM-yyyy` text, as printed), buyer, supplier (text as printed), items (`[[s.no, particulars, qty, rate, unit?], …]`, see *Item units* below), remarks, delivery_ready, transport_to, cash_payment, less_disc, side, created_by, archived_at |
| `bills` | bill_no, bill_date (`date`), amount (`numeric(14,2)`), lr_no (LR / bilty no, `''` if none), cd_percent (`numeric(6,3)`), cd_amount (`numeric(14,2)`), items (`jsonb`, same arrays as order items, null = no lines), buyer, supplier (text, null = take them from the bill's orders), transport (transport company the goods came by, null = none; migration `20261115000000_bill_transport.sql`), archived_at |
| `order_bills` | order_id, bill_id, archived_at. Many-to-many. **No foreign keys**, see below. |
| `payment_advices` | advice_no, pay_date (`date`), buyer (paid by), supplier (paid to), mode (`CHEQUE`/`ONLINE`/`CASH`), reference (cheque no / txn id), balance (`numeric(14,2)`, Dr/Cr balance as entered), balance_side (`DR`/`CR`/null), remarks, created_by, archived_at |
| `advice_lines` | advice_id, bill_id, paid, cd_percent, cd_amount, goods_return, rate_diff, claim (`numeric(14,2)`, claim / defect deduction), claim_note (reason, `''` if none), archived_at. Advice ↔ bill is many-to-many. **No foreign keys.** |
| `staff` | user_id, code (1–9, first digit of legacy order numbers and of legacy advice numbers), name, is_admin |
| `order_counters` | fy (`'2627'`), last_serial. Written only by `ota_allocate_order_no`; clients never read or write it. |
| `advice_counters` | fy (`'2627'`), last_serial. Written only by `ota_allocate_advice_no` (migration `20261116000000_fy_advice_numbers.sql`); separate from the order counter. |
| `app_settings` | single row (`id = true`): retention_days (30), storage_limit_mb (500), warn_percent (80) |

Bill and link ids are deterministic, so the same bill typed on two devices is one row:

- `bills.id = UUID.nameUUIDFromBytes("ota-bill:" + billKey)`
- `order_bills.id = UUID.nameUUIDFromBytes("ota-link:" + orderId + ":" + billId)`
- `advice_lines.id = UUID.nameUUIDFromBytes("ota-advice-line:" + adviceId + ":" + billId)` (a bill
  appears at most once per advice)

These use Java's MD5-based UUIDs over UTF-8 bytes. `billKey` is the bill number trimmed, with whitespace runs collapsed to one space,
upper-cased.

### Order numbers

New orders are numbered per financial year (1 April to 31 March):
`{FY start YY}{FY end YY}{4-digit serial}`, e.g. `26270001` is the first order of FY 2026-27. The
serial restarts at `0001` every 1 April and one shared counter serves every phone and the desktop.
At most 9999 orders per year.

- Get a number with `POST /rest/v1/rpc/ota_allocate_order_no` and `{"p_date": "yyyy-mm-dd"}`
  (the order date). It returns the number as a JSON string. It is atomic and skips any number
  already used. When the year is full it fails with `P0001` and a message starting
  `ORDER_LIMIT_REACHED`; tell the user, keep the order pending, and don't retry blindly.
- An order saved offline is stored with an **empty** `order_no` and shown as "Pending number".
  It is not uploaded, has no PDF and can't take a bill until the next sync numbers it; then it is
  uploaded with its number. A pending order deleted before it is numbered is simply dropped.
- An 8-digit number whose second pair of digits is the year after the first is an FY number; a
  partial unique index (`orders_fy_order_no_key`) backs that up in the cloud.
- Older orders keep their numbers and are never renumbered:
  `{staff code}{yyyyMMdd}{daily serial, 3 digits}` (e.g. `120261107001`). A few early test numbers
  have an unpadded serial (e.g. `9202611071`); treat the digits after the date as the serial.
  Even older numbers (`yyyyMMdd` + 2 digits) and manual numbers may repeat.
- Sort by order date, then number. Search matches the digits typed in either format.

### Payment advice numbers

New advices follow the order rules with **their own** FY counter: `advice_no` is stored as the bare
8 digits (e.g. `26270001`, the first advice of FY 2026-27), and is shown, printed and used in file
names as `PA-26270001`.

- Get a number with `POST /rest/v1/rpc/ota_allocate_advice_no` and `{"p_date": "yyyy-mm-dd"}` (the
  payment date). Atomic, seeded from existing FY advice numbers, skips used ones; when the year's
  9999 are used it fails with `P0001` / `ADVICE_LIMIT_REACHED`. A partial unique index
  (`payment_advices_fy_advice_no_key`) backs it up.
- An advice saved offline has an **empty** `advice_no` ("Pending number"). It and its lines are not
  uploaded, and it has no PDF and can't be shared, until the next sync numbers it (then its PDF is
  written). A pending advice deleted before it is numbered is dropped with its lines.
- Older advices keep `PA{staff code}{yyyyMMdd}{daily serial, 3 digits}` (e.g. `PA120261108001`),
  shown and named as stored, never renumbered. Search matches `26270001` and `PA-26270001`; sort
  by payment date, then number.

### PDFs

- File names: orders `Order_{order no}.pdf` (e.g. `Order_26270001.pdf`, legacy numbers as stored),
  advices `PA-{advice no}.pdf` for FY numbers (legacy: `PA120261108001.pdf`). Never a device name.
  When a file of that name already exists from another install, the phone writes `… (2).pdf`.
- Every rupee amount prints with 2 decimals and Indian grouping (`1,25,000.00`). Rupee column
  headers carry `(₹)`: the order table's `RATES (₹)` and each advice amount column. An order item
  rate that is a plain number (optionally with ₹/Rs, commas or `/-`) prints as `60.00`; any other
  text (`60 + GST`) prints as typed.

A bill's balance is `amount − Σ(paid + cd_amount + goods_return + rate_diff + claim)` over its
non-deleted lines whose advice is not deleted. Status: no lines → Unpaid, balance ≤ 0 → Paid,
otherwise Partly paid (a bill without an amount is Partly paid once it has a line).

### Bills

A bill records what the supplier actually billed, which can differ from the orders (24 m ordered,
25 m sent; a different variety). `items` holds its own lines; `amount` defaults to their total but
can be overridden. Old bills without lines have `items = null`.

A bill can be added on its own from the Bills section, with zero or more orders. Its `buyer` and
`supplier` are then stored on the bill and are required when no order is linked. When they are
null, the parties come from the bill's orders (and a bill whose orders disagree is "mixed").
Payment-advice eligibility uses the bill's own parties first. A bill with its own parties stays
when its last order is detached; a bill without them is retired (soft-deleted) then.

Party names are matched forgivingly (`data/PartyMatch.kt`, shared by both apps): only the first
line counts, case, spacing, punctuation, `&`/"and", a leading "M/s" and trailing "Pvt / Ltd / Co"
are ignored, Shree/Shri/Sri are the same, and a name that starts with the other's words (e.g.
"Shyam Garments Surat" vs "Shyam Garments") is a close match. The advice bill picker lists the
pair's unpaid bills (exact and close), and "Search all unpaid bills" can add any other bill: when its
parties differ, the app warns and asks before adding. An advice can hold many bills and a bill can
be paid by many advices; a bill's balance due subtracts every live advice line (paid + CD + goods
return + rate difference + claim).

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

## Item units

Order and bill item arrays may carry a fifth element: the quantity's unit as a one-letter code.

| Code | Unit |
|------|------|
| `M` | Mtr |
| `T` | Than |
| `P` | Pcs |
| `S` | Set |
| `B` | Bale |

- Items saved before units existed have only four elements: show their quantity text exactly as typed (it may already contain a unit, e.g. `"20m"` or `"5 kg"`).
- The phone splits such a trailing unit (`"100 mtr"` → `"100"` + `M`) only when the order is opened for editing, so old rows are rewritten only if they are edited and saved.
- Quantities are text; with a unit they are plain numbers (decimals allowed).
- Print as `"{qty} {unit label}"`. Any quantity total is per unit (e.g. `250 Mtr + 10 Than`); units are never added together.
- No SQL change: `items` is already `jsonb`.

## Desktop database

The desktop keeps every row in its own PostgreSQL (Flyway migrations in
`desktop/src/main/resources/db`). `V2__bill_lines_claims_parties.sql` adds the bill items and
parties and the claim columns, and resets the bill and advice-line pull cursors so the next sync
pulls those columns for rows it already had.

## Local-only Home features

Weather, the roaming cat, and to-dos/reminders never sync, and they have no cloud tables or columns.

- Weather comes from Open-Meteo (`api.open-meteo.com` forecast and `geocoding-api.open-meteo.com` city search), needs no key, and refreshes at most every 30 minutes.
- The phone uses its coarse location, or falls back to a fixed city. Its settings and cache are kept in SharedPreferences.
- The desktop uses a fixed city, or an approximate IP location from `get.geojs.io`, then `ipwho.is` (cached for 12 hours). Its settings and cache are kept in the local `kv` table.
