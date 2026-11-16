-- OTA Business: the cloud keeps open items, however old, and purges only settled ones.
--
-- Bills arrive days to months after an order and payments 3-9+ months later, so a rolling window by
-- date hid old orders and bills from the phone writing the advice. Now:
-- - an order stays until it is billed and every bill linked to it is settled;
-- - a bill stays until it is settled: amount - (paid + cash discount + goods return + rate difference
--   + claim over its advice lines) < 0.005 (a bill without an amount is open);
-- - an advice (and its lines) stays while any bill it touches is open;
-- - everything linked together goes together: a group of orders, links, bills, advices and lines is
--   purged only when all its bills are settled, all its orders are billed, every row in it is archived
--   by the desktop and unchanged since, and nothing in it changed within retention_days;
-- - a bill a line or link points at but that is no longer in the cloud counts as settled (the desktop,
--   which keeps everything, uploads open ones again once after updating to 1.11.0);
-- - buyers and suppliers are never purged, and a purge is still not a delete (no tombstones).
-- The test account's test_ tables follow the same rule; the test login may archive and purge its own
-- test_ tables (only those). Production archive and purge stay admin-only.
--
-- Safe to run more than once.

begin;

create or replace function public.ota_mark_archived(p_table text, p_rows jsonb)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_test boolean := public.ota_is_test();
  marked integer;
begin
  if not (v_test or public.ota_is_admin()) then
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
    case when v_test then 'test_' || p_table else p_table end
  ) using coalesce(p_rows, '[]'::jsonb);
  get diagnostics marked = row_count;
  perform set_config('ota.archiving', 'off', true);
  return marked;
end;
$$;

create or replace function public.ota_purge_archived_orders()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_test    boolean := public.ota_is_test();
  p         text := case when v_test then 'test_' else '' end;
  keep_days integer;
  cutoff    date;
  stale     text;
  n         bigint;
  added     bigint;
  rounds    integer := 0;
  gone      jsonb := '{}'::jsonb;
  t         text;
begin
  if not (v_test or public.ota_is_admin()) then
    raise exception 'Only an admin can purge orders' using errcode = '42501';
  end if;
  select retention_days into keep_days from public.app_settings where id;
  keep_days := coalesce(keep_days, 30);
  cutoff := (now() at time zone 'Asia/Kolkata')::date - keep_days;

  -- A row may go only when the desktop has archived its current version and it is older than the window.
  stale := '(%1$s.archived_at is not null and %1$s.updated_at <= %1$s.archived_at
              and (%1$s.updated_at at time zone ''Asia/Kolkata'')::date < $1)';

  create temp table ota_keep_orders (id uuid primary key) on commit drop;
  create temp table ota_keep_bills (id uuid primary key) on commit drop;
  create temp table ota_keep_advices (id uuid primary key) on commit drop;

  -- Open bills, and bills with a recent or unarchived row of their own.
  execute format(
    'insert into pg_temp.ota_keep_bills
     select b.id from public.%1$sbills b
      where not ' || format(stale, 'b') || '
         or b.amount is null
         or b.amount - coalesce((select sum(l.paid + coalesce(l.cd_amount, 0) + coalesce(l.goods_return, 0)
                                            + coalesce(l.rate_diff, 0) + coalesce(l.claim, 0))
                                   from public.%1$sadvice_lines l join public.%1$spayment_advices a on a.id = l.advice_id
                                  where l.bill_id = b.id), 0) >= 0.005
         or exists (select 1 from public.%1$sorder_bills x where x.bill_id = b.id and not ' || format(stale, 'x') || ')
         or exists (select 1 from public.%1$sadvice_lines x where x.bill_id = b.id and not ' || format(stale, 'x') || ')',
    p) using cutoff;
  -- Unbilled orders, and orders with a recent or unarchived row of their own.
  execute format(
    'insert into pg_temp.ota_keep_orders
     select o.id from public.%1$sorders o
      where not ' || format(stale, 'o') || '
         or not exists (select 1 from public.%1$sorder_bills x where x.order_id = o.id)
         or exists (select 1 from public.%1$sorder_bills x where x.order_id = o.id and not ' || format(stale, 'x') || ')',
    p) using cutoff;
  execute format(
    'insert into pg_temp.ota_keep_advices
     select a.id from public.%1$spayment_advices a
      where not ' || format(stale, 'a') || '
         or exists (select 1 from public.%1$sadvice_lines x where x.advice_id = a.id and not ' || format(stale, 'x') || ')',
    p) using cutoff;

  -- Whatever is linked to a kept row is kept: an order with its bills, a bill with its orders and
  -- advices, an advice with all its bills.
  loop
    rounds := rounds + 1;
    added := 0;
    execute format('insert into pg_temp.ota_keep_orders select distinct x.order_id from public.%1$sorder_bills x
                     join pg_temp.ota_keep_bills k on k.id = x.bill_id on conflict do nothing', p);
    get diagnostics n = row_count; added := added + n;
    execute format('insert into pg_temp.ota_keep_advices select distinct x.advice_id from public.%1$sadvice_lines x
                     join pg_temp.ota_keep_bills k on k.id = x.bill_id on conflict do nothing', p);
    get diagnostics n = row_count; added := added + n;
    execute format('insert into pg_temp.ota_keep_bills select distinct x.bill_id from public.%1$sorder_bills x
                     join pg_temp.ota_keep_orders k on k.id = x.order_id on conflict do nothing', p);
    get diagnostics n = row_count; added := added + n;
    execute format('insert into pg_temp.ota_keep_bills select distinct x.bill_id from public.%1$sadvice_lines x
                     join pg_temp.ota_keep_advices k on k.id = x.advice_id on conflict do nothing', p);
    get diagnostics n = row_count; added := added + n;
    exit when added = 0;
  end loop;

  execute format('delete from public.%1$sorders t where not exists (select 1 from pg_temp.ota_keep_orders k where k.id = t.id)
                   and ' || format(stale, 't'), p) using cutoff;
  get diagnostics n = row_count; gone := gone || jsonb_build_object('orders', n);
  execute format('delete from public.%1$sorder_bills t
                   where not exists (select 1 from pg_temp.ota_keep_orders k where k.id = t.order_id)
                     and not exists (select 1 from pg_temp.ota_keep_bills k where k.id = t.bill_id)
                     and ' || format(stale, 't'), p) using cutoff;
  get diagnostics n = row_count; gone := gone || jsonb_build_object('order_bills', n);
  execute format('delete from public.%1$spayment_advices t where not exists (select 1 from pg_temp.ota_keep_advices k where k.id = t.id)
                   and ' || format(stale, 't'), p) using cutoff;
  get diagnostics n = row_count; gone := gone || jsonb_build_object('payment_advices', n);
  execute format('delete from public.%1$sadvice_lines t
                   where not exists (select 1 from pg_temp.ota_keep_advices k where k.id = t.advice_id)
                     and not exists (select 1 from pg_temp.ota_keep_bills k where k.id = t.bill_id)
                     and ' || format(stale, 't'), p) using cutoff;
  get diagnostics n = row_count; gone := gone || jsonb_build_object('advice_lines', n);
  execute format('delete from public.%1$sbills t where not exists (select 1 from pg_temp.ota_keep_bills k where k.id = t.id)
                   and ' || format(stale, 't'), p) using cutoff;
  get diagnostics n = row_count; gone := gone || jsonb_build_object('bills', n);

  drop table if exists pg_temp.ota_keep_orders, pg_temp.ota_keep_bills, pg_temp.ota_keep_advices;
  return gone || jsonb_build_object('cutoff', cutoff, 'retention_days', keep_days, 'rule', 'open_items');
end;
$$;

revoke all on function public.ota_mark_archived(text, jsonb) from public, anon;
grant execute on function public.ota_mark_archived(text, jsonb) to authenticated;
revoke all on function public.ota_purge_archived_orders() from public, anon;
grant execute on function public.ota_purge_archived_orders() to authenticated;

commit;

notify pgrst, 'reload schema';
