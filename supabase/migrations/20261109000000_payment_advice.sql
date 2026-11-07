-- OTA Business: bill details (LR number, cash discount) and payment advices.
--
-- Run once in the Supabase dashboard: SQL Editor > New query > paste this file > Run.
-- Needs 20261107000000_catalog.sql and 20261108000000_orders.sql first. Safe to re-run.
--
-- A payment advice is one buyer paying one supplier; its lines say how much of each bill it
-- settles (advice <-> bill is many-to-many). Same rules as orders: phone-generated UUIDs,
-- server updated_at as the pull cursor, client_updated_at for last-write-wins, soft deletes,
-- no files (the PDF is regenerated on the phone), and the same archive-then-purge retention.

-- ---------------------------------------------------------------- bill details
alter table public.bills add column if not exists lr_no text not null default '';
alter table public.bills add column if not exists cd_percent numeric(6, 3);
alter table public.bills add column if not exists cd_amount numeric(14, 2);

-- ---------------------------------------------------------------- payment_advices
create table if not exists public.payment_advices (
  id                uuid primary key,
  -- PA{staff digit}{yyyyMMdd}{3-digit serial}, e.g. PA120261108001
  advice_no         text not null,
  pay_date          date,
  buyer             text not null default '',  -- paid by (name as printed)
  supplier          text not null default '',  -- paid to
  mode              text not null default 'CASH' check (mode in ('CHEQUE', 'ONLINE', 'CASH')),
  reference         text not null default '',  -- cheque number / transaction id
  balance           numeric(14, 2),            -- Dr/Cr balance as entered
  balance_side      text check (balance_side in ('DR', 'CR')),
  remarks           text not null default '',
  deleted           boolean not null default false,
  updated_at        timestamptz not null default now(),
  client_updated_at bigint not null default 0,
  created_by        uuid default auth.uid(),
  archived_at       timestamptz
);

create index if not exists payment_advices_updated_at_idx on public.payment_advices (updated_at, id);

-- Backstop: an advice number can exist only once.
create unique index if not exists payment_advices_advice_no_key on public.payment_advices (advice_no)
  where advice_no ~ '^PA[1-9]20[0-9]{6}[0-9]+$';

drop trigger if exists payment_advices_before_write on public.payment_advices;
create trigger payment_advices_before_write
  before insert or update on public.payment_advices
  for each row execute function public.ota_orders_before_write();

alter table public.payment_advices enable row level security;

drop policy if exists "staff can read advices" on public.payment_advices;
create policy "staff can read advices" on public.payment_advices
  for select to authenticated using ((select public.ota_is_staff()));

drop policy if exists "staff can add advices" on public.payment_advices;
create policy "staff can add advices" on public.payment_advices
  for insert to authenticated with check ((select public.ota_is_staff()));

drop policy if exists "staff can edit advices" on public.payment_advices;
create policy "staff can edit advices" on public.payment_advices
  for update to authenticated
  using ((select public.ota_is_staff()))
  with check ((select public.ota_is_staff()));

revoke all on public.payment_advices from anon;
revoke delete, truncate on public.payment_advices from authenticated;
grant select, insert, update on public.payment_advices to authenticated;

-- ---------------------------------------------------------------- advice_lines
-- What one advice settles against one bill. id is derived from (advice_id, bill_id) on the
-- phone, so a bill appears at most once per advice. No foreign keys, like order_bills: the
-- bill may already be archived to the desktop and purged from the cloud when it is paid.
create table if not exists public.advice_lines (
  id                uuid primary key,
  advice_id         uuid not null,
  bill_id           uuid not null,
  paid              numeric(14, 2) not null default 0,
  cd_percent        numeric(6, 3),
  cd_amount         numeric(14, 2),
  goods_return      numeric(14, 2),
  rate_diff         numeric(14, 2),
  deleted           boolean not null default false,
  updated_at        timestamptz not null default now(),
  client_updated_at bigint not null default 0,
  archived_at       timestamptz
);

create index if not exists advice_lines_updated_at_idx on public.advice_lines (updated_at, id);

drop trigger if exists advice_lines_before_write on public.advice_lines;
create trigger advice_lines_before_write
  before insert or update on public.advice_lines
  for each row execute function public.ota_sync_before_write();

alter table public.advice_lines enable row level security;

drop policy if exists "staff can read advice lines" on public.advice_lines;
create policy "staff can read advice lines" on public.advice_lines
  for select to authenticated using ((select public.ota_is_staff()));

drop policy if exists "staff can add advice lines" on public.advice_lines;
create policy "staff can add advice lines" on public.advice_lines
  for insert to authenticated with check ((select public.ota_is_staff()));

drop policy if exists "staff can edit advice lines" on public.advice_lines;
create policy "staff can edit advice lines" on public.advice_lines
  for update to authenticated
  using ((select public.ota_is_staff()))
  with check ((select public.ota_is_staff()));

revoke all on public.advice_lines from anon;
revoke delete, truncate on public.advice_lines from authenticated;
grant select, insert, update on public.advice_lines to authenticated;

-- ---------------------------------------------------------------- retention
-- Same as before, plus the two advice tables.
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
  if p_table not in ('orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines') then
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

-- Deletes rows older than retention_days that the desktop has archived and that have not
-- changed since: orders (by order date) and their links, payment advices (by payment date)
-- and their lines, then archived bills that nothing links to any more. Never touches an
-- unarchived or re-edited row. Returns what was removed.
create or replace function public.ota_purge_archived_orders()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  keep_days    integer;
  cutoff       date;
  gone_orders  uuid[];
  gone_links   integer;
  gone_advices uuid[];
  gone_lines   integer;
  touched      uuid[];
  touched2     uuid[];
  gone_bills   integer;
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

  with purged as (
    delete from public.payment_advices a
     where a.archived_at is not null and a.updated_at <= a.archived_at
       and coalesce(a.pay_date, (a.updated_at at time zone 'Asia/Kolkata')::date) < cutoff
    returning a.id
  )
  select coalesce(array_agg(id), '{}') into gone_advices from purged;

  -- Lines of purged advices, plus old lines whose advice is no longer in the cloud.
  with purged as (
    delete from public.advice_lines l
     where l.archived_at is not null and l.updated_at <= l.archived_at
       and (l.advice_id = any(gone_advices)
            or ((l.updated_at at time zone 'Asia/Kolkata')::date < cutoff
                and not exists (select 1 from public.payment_advices a where a.id = l.advice_id)))
    returning l.bill_id
  )
  select count(*), coalesce(array_agg(distinct bill_id), '{}') into gone_lines, touched2 from purged;

  delete from public.bills b
   where b.archived_at is not null and b.updated_at <= b.archived_at
     and (b.id = any(touched) or b.id = any(touched2) or (b.updated_at at time zone 'Asia/Kolkata')::date < cutoff)
     and not exists (select 1 from public.order_bills l where l.bill_id = b.id)
     and not exists (select 1 from public.advice_lines l where l.bill_id = b.id);
  get diagnostics gone_bills = row_count;

  return jsonb_build_object(
    'orders', cardinality(gone_orders), 'order_bills', gone_links,
    'payment_advices', cardinality(gone_advices), 'advice_lines', gone_lines,
    'bills', gone_bills, 'cutoff', cutoff, 'retention_days', keep_days
  );
end;
$$;

revoke all on function public.ota_mark_archived(text, jsonb) from public, anon;
revoke all on function public.ota_purge_archived_orders() from public, anon;
grant execute on function public.ota_mark_archived(text, jsonb) to authenticated;
grant execute on function public.ota_purge_archived_orders() to authenticated;

-- Make PostgREST see the new tables and columns straight away.
notify pgrst, 'reload schema';
