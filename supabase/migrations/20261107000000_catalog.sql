-- OTA Business: shared buyer / supplier catalog.
--
-- Run once in the Supabase dashboard: SQL Editor > New query > paste this file > Run.
-- Safe to re-run: every statement is idempotent.
--
-- Model (mirrors the app's local SQLite tables):
--   * id is generated on the phone (UUID) so rows can be created offline.
--   * updated_at is always set by the server and is what phones use as their "pull since" cursor,
--     so a wrong phone clock can never hide changes.
--   * client_updated_at is the phone's edit time (epoch millis). It only decides conflicts:
--     if an older edit reaches the server after a newer one, the newer row is kept (last write wins).
--   * Rows are never hard-deleted; deleted = true is a soft delete that syncs to every phone.

-- Only signed-in staff (not anonymous sign-ins) may use the catalog.
create or replace function public.ota_is_staff()
returns boolean
language sql
stable
set search_path = ''
as $$
  select auth.uid() is not null
     and coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) = false
$$;

create or replace function public.ota_catalog_before_write()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' then
    if new.client_updated_at < old.client_updated_at then
      -- A stale edit arrived late: keep the newer row untouched.
      return old;
    end if;
    new.created_at := old.created_at;
  else
    new.created_at := coalesce(new.created_at, now());
  end if;
  new.updated_at := clock_timestamp();
  new.updated_by := auth.uid();
  return new;
end;
$$;

-- ---------------------------------------------------------------- buyers
create table if not exists public.buyers (
  id                uuid primary key,
  name              text not null check (length(btrim(name)) > 0),
  address           text not null default '',
  city              text not null default '',
  phone             text not null default '',
  gstin             text not null default '',
  bank_account_name text not null default '',
  bank_name         text not null default '',
  bank_account_no   text not null default '',
  bank_ifsc         text not null default '',
  bank_branch       text not null default '',
  bank_upi          text not null default '',
  deleted           boolean not null default false,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  client_updated_at bigint not null default 0,
  updated_by        uuid default auth.uid()
);

create index if not exists buyers_updated_at_idx on public.buyers (updated_at, id);

drop trigger if exists buyers_before_write on public.buyers;
create trigger buyers_before_write
  before insert or update on public.buyers
  for each row execute function public.ota_catalog_before_write();

alter table public.buyers enable row level security;

drop policy if exists "staff can read buyers" on public.buyers;
create policy "staff can read buyers" on public.buyers
  for select to authenticated using ((select public.ota_is_staff()));

drop policy if exists "staff can add buyers" on public.buyers;
create policy "staff can add buyers" on public.buyers
  for insert to authenticated with check ((select public.ota_is_staff()));

drop policy if exists "staff can edit buyers" on public.buyers;
create policy "staff can edit buyers" on public.buyers
  for update to authenticated
  using ((select public.ota_is_staff()))
  with check ((select public.ota_is_staff()));

-- No delete policy or grant: rows are soft-deleted with deleted = true.
revoke all on public.buyers from anon;
revoke delete, truncate on public.buyers from authenticated;
grant select, insert, update on public.buyers to authenticated;

-- ---------------------------------------------------------------- suppliers
create table if not exists public.suppliers (
  id                uuid primary key,
  name              text not null check (length(btrim(name)) > 0),
  address           text not null default '',
  city              text not null default '',
  phone             text not null default '',
  gstin             text not null default '',
  bank_account_name text not null default '',
  bank_name         text not null default '',
  bank_account_no   text not null default '',
  bank_ifsc         text not null default '',
  bank_branch       text not null default '',
  bank_upi          text not null default '',
  deleted           boolean not null default false,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  client_updated_at bigint not null default 0,
  updated_by        uuid default auth.uid()
);

create index if not exists suppliers_updated_at_idx on public.suppliers (updated_at, id);

drop trigger if exists suppliers_before_write on public.suppliers;
create trigger suppliers_before_write
  before insert or update on public.suppliers
  for each row execute function public.ota_catalog_before_write();

alter table public.suppliers enable row level security;

drop policy if exists "staff can read suppliers" on public.suppliers;
create policy "staff can read suppliers" on public.suppliers
  for select to authenticated using ((select public.ota_is_staff()));

drop policy if exists "staff can add suppliers" on public.suppliers;
create policy "staff can add suppliers" on public.suppliers
  for insert to authenticated with check ((select public.ota_is_staff()));

drop policy if exists "staff can edit suppliers" on public.suppliers;
create policy "staff can edit suppliers" on public.suppliers
  for update to authenticated
  using ((select public.ota_is_staff()))
  with check ((select public.ota_is_staff()));

revoke all on public.suppliers from anon;
revoke delete, truncate on public.suppliers from authenticated;
grant select, insert, update on public.suppliers to authenticated;
