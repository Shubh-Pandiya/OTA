-- OTA Business: orders, bills and their links, per-staff order number codes, and the cloud
-- retention (archive-then-purge) controls used by the desktop app.
--
-- Run once in the Supabase dashboard: SQL Editor > New query > paste this file > Run.
-- Needs the catalog migration (20261107000000_catalog.sql) first. Safe to re-run.
--
-- Kept deliberately small (the free plan's database quota is 500 MB):
--   * No files: PDFs are never uploaded; each phone regenerates them from the order data.
--   * One row per order. Items are a compact JSON array of [s.no, particulars, qty, rate].
--   * Only the indexes that sync needs: primary keys, (updated_at, id) for "changed since"
--     pulls, and a unique backstop on the new-style order numbers.
--   * Same sync rules as the catalog: phone-generated UUIDs, server-set updated_at as the pull
--     cursor, client_updated_at (phone edit time, epoch millis) for last-write-wins, and soft
--     deletes only (deleted = true).
--   * The cloud keeps orders only for app_settings.retention_days. The desktop app keeps every
--     order: it pulls rows, stores them, marks them archived (ota_mark_archived) and then calls
--     ota_purge_archived_orders(), which only ever deletes archived, unchanged, old rows.
--     See docs/desktop-sync-contract.md.

-- ---------------------------------------------------------------- staff codes
-- One digit per staff login; it is the first digit of every order number they create
-- ({code}{yyyyMMdd}{3-digit serial}, e.g. 120261107001). Rows are added by the owner in the SQL Editor.
create table if not exists public.staff (
  user_id uuid primary key references auth.users (id) on delete cascade,
  code     smallint not null unique check (code between 1 and 9),
  name     text not null default '',
  is_admin boolean not null default false
);

alter table public.staff add column if not exists is_admin boolean not null default false;

alter table public.staff enable row level security;

drop policy if exists "staff can read own code" on public.staff;
create policy "staff can read own code" on public.staff
  for select to authenticated using (user_id = (select auth.uid()));

revoke all on public.staff from anon;
revoke insert, update, delete, truncate on public.staff from authenticated;
grant select on public.staff to authenticated;

-- Admins (the owner) may change retention and purge archived orders.
create or replace function public.ota_is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (select 1 from public.staff where user_id = auth.uid() and is_admin)
$$;

revoke all on function public.ota_is_admin() from public, anon;
grant execute on function public.ota_is_admin() to authenticated;

-- ---------------------------------------------------------------- settings
-- Exactly one row. Everyone signed in can read it; only an admin can change it.
create table if not exists public.app_settings (
  id               boolean primary key default true check (id),
  retention_days   integer not null default 30 check (retention_days between 1 and 3650),
  -- Supabase Free plan database size quota is 500 MB (the 1 GB is file storage, unused here).
  storage_limit_mb integer not null default 500 check (storage_limit_mb > 0),
  warn_percent     integer not null default 80 check (warn_percent between 1 and 100),
  updated_at       timestamptz not null default now(),
  updated_by       uuid
);

insert into public.app_settings (id) values (true) on conflict (id) do nothing;

create or replace function public.ota_settings_before_update()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.id := true;
  new.updated_at := clock_timestamp();
  new.updated_by := auth.uid();
  return new;
end;
$$;

drop trigger if exists app_settings_before_update on public.app_settings;
create trigger app_settings_before_update
  before update on public.app_settings
  for each row execute function public.ota_settings_before_update();

alter table public.app_settings enable row level security;

drop policy if exists "staff can read settings" on public.app_settings;
create policy "staff can read settings" on public.app_settings
  for select to authenticated using ((select public.ota_is_staff()));

drop policy if exists "admin can change settings" on public.app_settings;
create policy "admin can change settings" on public.app_settings
  for update to authenticated
  using ((select public.ota_is_admin()))
  with check ((select public.ota_is_admin()));

revoke all on public.app_settings from anon, authenticated;
grant select on public.app_settings to authenticated;
grant update (retention_days, storage_limit_mb, warn_percent) on public.app_settings to authenticated;

-- ---------------------------------------------------------------- shared trigger
create or replace function public.ota_sync_before_write()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' then
    if current_setting('ota.archiving', true) = 'on' then
      -- ota_mark_archived: only archived_at changes, the row stays "unchanged" for sync.
      return new;
    end if;
    if new.client_updated_at < old.client_updated_at then
      -- A stale edit arrived late: keep the newer row untouched.
      return old;
    end if;
    new.archived_at := old.archived_at;
  else
    new.archived_at := null;
  end if;
  new.updated_at := clock_timestamp();
  return new;
end;
$$;

create or replace function public.ota_orders_before_write()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' then
    if current_setting('ota.archiving', true) = 'on' then
      return new;
    end if;
    if new.client_updated_at < old.client_updated_at then
      return old;
    end if;
    new.created_by := old.created_by;
    new.archived_at := old.archived_at;
  else
    new.created_by := auth.uid();
    new.archived_at := null;
  end if;
  new.updated_at := clock_timestamp();
  return new;
end;
$$;

-- ---------------------------------------------------------------- orders
create table if not exists public.orders (
  id                uuid primary key,
  order_no          text not null,
  order_date        text not null default '',
  buyer             text not null default '',
  supplier          text not null default '',
  items             jsonb not null default '[]' check (jsonb_typeof(items) = 'array'),
  remarks           text not null default '',
  delivery_ready    text not null default '',
  transport_to      text not null default '',
  cash_payment      text not null default '',
  less_disc         text not null default '',
  side              text not null default '',
  deleted           boolean not null default false,
  updated_at        timestamptz not null default now(),
  client_updated_at bigint not null default 0,
  created_by        uuid default auth.uid(),
  -- Set only by ota_mark_archived once the desktop has stored this exact version.
  archived_at       timestamptz
);

create index if not exists orders_updated_at_idx on public.orders (updated_at, id);

-- Backstop: a new-style number ({digit}{yyyyMMdd}{serial}) can exist only once. Older per-phone
-- numbers (yyyyMMdd + 2 digits) are left out because two phones could legitimately share one.
create unique index if not exists orders_order_no_key on public.orders (order_no)
  where order_no ~ '^[1-9]20[0-9]{6}[0-9]+$';

drop trigger if exists orders_before_write on public.orders;
create trigger orders_before_write
  before insert or update on public.orders
  for each row execute function public.ota_orders_before_write();

alter table public.orders enable row level security;

drop policy if exists "staff can read orders" on public.orders;
create policy "staff can read orders" on public.orders
  for select to authenticated using ((select public.ota_is_staff()));

drop policy if exists "staff can add orders" on public.orders;
create policy "staff can add orders" on public.orders
  for insert to authenticated with check ((select public.ota_is_staff()));

drop policy if exists "staff can edit orders" on public.orders;
create policy "staff can edit orders" on public.orders
  for update to authenticated
  using ((select public.ota_is_staff()))
  with check ((select public.ota_is_staff()));

revoke all on public.orders from anon;
revoke delete, truncate on public.orders from authenticated;
grant select, insert, update on public.orders to authenticated;

-- ---------------------------------------------------------------- bills
-- id is derived from the normalised bill number on the phone, so the same bill typed on two
-- phones becomes one row.
create table if not exists public.bills (
  id                uuid primary key,
  bill_no           text not null check (length(btrim(bill_no)) > 0),
  bill_date         date,
  amount            numeric(14, 2),
  deleted           boolean not null default false,
  updated_at        timestamptz not null default now(),
  client_updated_at bigint not null default 0,
  archived_at       timestamptz
);

create index if not exists bills_updated_at_idx on public.bills (updated_at, id);

drop trigger if exists bills_before_write on public.bills;
create trigger bills_before_write
  before insert or update on public.bills
  for each row execute function public.ota_sync_before_write();

alter table public.bills enable row level security;

drop policy if exists "staff can read bills" on public.bills;
create policy "staff can read bills" on public.bills
  for select to authenticated using ((select public.ota_is_staff()));

drop policy if exists "staff can add bills" on public.bills;
create policy "staff can add bills" on public.bills
  for insert to authenticated with check ((select public.ota_is_staff()));

drop policy if exists "staff can edit bills" on public.bills;
create policy "staff can edit bills" on public.bills
  for update to authenticated
  using ((select public.ota_is_staff()))
  with check ((select public.ota_is_staff()));

revoke all on public.bills from anon;
revoke delete, truncate on public.bills from authenticated;
grant select, insert, update on public.bills to authenticated;

-- ---------------------------------------------------------------- order_bills
-- Many-to-many: an order can have several bills and a bill can cover several orders.
-- id is derived from (order_id, bill_id) on the phone, so a link exists at most once.
--
-- No foreign keys on purpose: bills usually arrive weeks after the order, when the order may
-- already have been archived to the desktop and purged from the cloud. The link (and the bill,
-- which the phone re-uploads on every attach) must still upload; the desktop, which keeps every
-- order, joins them by id.
create table if not exists public.order_bills (
  id                uuid primary key,
  order_id          uuid not null,
  bill_id           uuid not null,
  deleted           boolean not null default false,
  updated_at        timestamptz not null default now(),
  client_updated_at bigint not null default 0,
  archived_at       timestamptz
);

alter table public.order_bills drop constraint if exists order_bills_order_id_fkey;
alter table public.order_bills drop constraint if exists order_bills_bill_id_fkey;

create index if not exists order_bills_updated_at_idx on public.order_bills (updated_at, id);

drop trigger if exists order_bills_before_write on public.order_bills;
create trigger order_bills_before_write
  before insert or update on public.order_bills
  for each row execute function public.ota_sync_before_write();

alter table public.order_bills enable row level security;

drop policy if exists "staff can read order bills" on public.order_bills;
create policy "staff can read order bills" on public.order_bills
  for select to authenticated using ((select public.ota_is_staff()));

drop policy if exists "staff can add order bills" on public.order_bills;
create policy "staff can add order bills" on public.order_bills
  for insert to authenticated with check ((select public.ota_is_staff()));

drop policy if exists "staff can edit order bills" on public.order_bills;
create policy "staff can edit order bills" on public.order_bills
  for update to authenticated
  using ((select public.ota_is_staff()))
  with check ((select public.ota_is_staff()));

revoke all on public.order_bills from anon;
revoke delete, truncate on public.order_bills from authenticated;
grant select, insert, update on public.order_bills to authenticated;

-- Re-runs on a project created before archiving existed.
alter table public.orders add column if not exists archived_at timestamptz;
alter table public.bills add column if not exists archived_at timestamptz;
alter table public.order_bills add column if not exists archived_at timestamptz;

-- ---------------------------------------------------------------- retention
-- Order day used for retention: order_date is dd-MM-yyyy text as printed on the PDF; anything
-- unreadable falls back to the day the row last changed in the cloud.
create or replace function public.ota_order_day(order_date text, changed_at timestamptz)
returns date
language plpgsql
stable
set search_path = ''
as $$
begin
  if order_date ~ '^[0-9]{2}-[0-9]{2}-[0-9]{4}$' then
    return to_date(order_date, 'DD-MM-YYYY');
  end if;
  return (changed_at at time zone 'Asia/Kolkata')::date;
exception when others then
  return (changed_at at time zone 'Asia/Kolkata')::date;
end;
$$;

-- Desktop: "I have stored these exact versions". p_rows is a JSON array of
-- {"id": uuid, "updated_at": the updated_at value it pulled}. A row edited since then is
-- skipped (its new version comes in the next pull and is archived then). Returns rows marked.
create or replace function public.ota_mark_archived(p_table text, p_rows jsonb)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  marked integer;
begin
  if not public.ota_is_admin() then
    raise exception 'Only an admin can archive orders' using errcode = '42501';
  end if;
  if p_table not in ('orders', 'bills', 'order_bills') then
    raise exception 'Unknown table %', p_table using errcode = '22023';
  end if;
  perform set_config('ota.archiving', 'on', true);
  execute format(
    'update public.%I t set archived_at = clock_timestamp()
       from jsonb_to_recordset($1) r(id uuid, updated_at timestamptz)
      where t.id = r.id and t.updated_at = r.updated_at',
    p_table
  ) using coalesce(p_rows, '[]'::jsonb);
  get diagnostics marked = row_count;
  perform set_config('ota.archiving', 'off', true);
  return marked;
end;
$$;

-- Deletes orders older than retention_days (by order date) that the desktop has archived and
-- that have not changed since; then their archived links, then archived bills left with no
-- links. Never touches an unarchived or re-edited row. Returns what was removed.
create or replace function public.ota_purge_archived_orders()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  keep_days   integer;
  cutoff      date;
  gone_orders uuid[];
  gone_links  integer;
  touched     uuid[];
  gone_bills  integer;
begin
  if not public.ota_is_admin() then
    raise exception 'Only an admin can purge orders' using errcode = '42501';
  end if;
  select retention_days into keep_days from public.app_settings where id;
  cutoff := (now() at time zone 'Asia/Kolkata')::date - coalesce(keep_days, 30);

  with purged as (
    delete from public.orders o
     where o.archived_at is not null and o.updated_at <= o.archived_at
       and public.ota_order_day(o.order_date, o.updated_at) < cutoff
    returning o.id
  )
  select coalesce(array_agg(id), '{}') into gone_orders from purged;

  -- Links of purged orders, plus old links whose order is no longer in the cloud.
  with purged as (
    delete from public.order_bills l
     where l.archived_at is not null and l.updated_at <= l.archived_at
       and (l.order_id = any(gone_orders)
            or ((l.updated_at at time zone 'Asia/Kolkata')::date < cutoff
                and not exists (select 1 from public.orders o where o.id = l.order_id)))
    returning l.bill_id
  )
  select count(*), coalesce(array_agg(distinct bill_id), '{}') into gone_links, touched from purged;

  delete from public.bills b
   where b.archived_at is not null and b.updated_at <= b.archived_at
     and (b.id = any(touched) or (b.updated_at at time zone 'Asia/Kolkata')::date < cutoff)
     and not exists (select 1 from public.order_bills l where l.bill_id = b.id);
  get diagnostics gone_bills = row_count;

  return jsonb_build_object(
    'orders', cardinality(gone_orders), 'order_bills', gone_links, 'bills', gone_bills,
    'cutoff', cutoff, 'retention_days', keep_days
  );
end;
$$;

-- Database size against the quota, plus the retention setting; read by phones on every sync.
create or replace function public.ota_cloud_status()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'used_bytes', pg_database_size(current_database()),
    'limit_mb', s.storage_limit_mb,
    'warn_percent', s.warn_percent,
    'retention_days', s.retention_days,
    'percent', round(100.0 * pg_database_size(current_database()) / (s.storage_limit_mb * 1048576.0), 1)
  )
  from public.app_settings s
  where s.id and public.ota_is_staff()
$$;

revoke all on function public.ota_order_day(text, timestamptz) from public, anon;
revoke all on function public.ota_mark_archived(text, jsonb) from public, anon;
revoke all on function public.ota_purge_archived_orders() from public, anon;
revoke all on function public.ota_cloud_status() from public, anon;
grant execute on function public.ota_mark_archived(text, jsonb) to authenticated;
grant execute on function public.ota_purge_archived_orders() to authenticated;
grant execute on function public.ota_cloud_status() to authenticated;
