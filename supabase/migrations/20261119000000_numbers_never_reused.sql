-- OTA Business: order and payment advice numbers are never handed out twice.
--
-- Run after 20261118000000_hard_deletes.sql. Safe to run more than once.
--
-- 20261117 put the production counters back to the highest serial still in the table, so after
-- orders 26270001–26270005 were deleted the next order got 26270001 again. Numbers are never
-- reused now:
--   * a counter only moves forward: an update that would lower it keeps the higher value, and a
--     counter row can't be deleted (so it is never re-seeded from the rows that are left);
--   * clearing test data keeps the test counters too;
--   * the production order counter for FY 2026-27 is moved past the deleted 26270001–26270005,
--     so the next real order is 26270006 (or later, if more were issued since).

-- ---------------------------------------------------------------- counters never go back
create or replace function public.ota_counter_forward_only()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'Number counters are never deleted, so numbers are never reused' using errcode = '42501';
  end if;
  if new.last_serial < old.last_serial then
    new.last_serial := old.last_serial;
  end if;
  return new;
end;
$$;

revoke all on function public.ota_counter_forward_only() from public, anon, authenticated;

do $$
declare
  t text;
begin
  foreach t in array array['order_counters', 'advice_counters'] loop
    execute format('drop trigger if exists %I on public.%I', t || '_forward_only', t);
    execute format(
      'create trigger %I before update or delete on public.%I for each row execute function public.ota_counter_forward_only()',
      t || '_forward_only', t
    );
  end loop;
end;
$$;

-- ---------------------------------------------------------------- clear test data keeps counters
create or replace function public.ota_clear_test_data()
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  n jsonb := '{}';
  c integer;
begin
  if not public.ota_is_test() then
    raise exception 'Only the test account can clear test data' using errcode = '42501';
  end if;
  delete from public.advice_lines where is_test;    get diagnostics c = row_count; n := n || jsonb_build_object('advice_lines', c);
  delete from public.payment_advices where is_test; get diagnostics c = row_count; n := n || jsonb_build_object('payment_advices', c);
  delete from public.order_bills where is_test;     get diagnostics c = row_count; n := n || jsonb_build_object('order_bills', c);
  delete from public.bills where is_test;           get diagnostics c = row_count; n := n || jsonb_build_object('bills', c);
  delete from public.orders where is_test;          get diagnostics c = row_count; n := n || jsonb_build_object('orders', c);
  delete from public.buyers where is_test;          get diagnostics c = row_count; n := n || jsonb_build_object('buyers', c);
  delete from public.suppliers where is_test;       get diagnostics c = row_count; n := n || jsonb_build_object('suppliers', c);
  delete from public.ota_deletions where is_test;
  return n;
end;
$$;

revoke all on function public.ota_clear_test_data() from public, anon;
grant execute on function public.ota_clear_test_data() to authenticated;

-- ---------------------------------------------------------------- past 26270001–26270005
-- Production orders 26270001–26270005 were issued and deleted: the next real order is 26270006.
do $$
declare
  before_serial integer;
  after_serial  integer;
begin
  select last_serial into before_serial from public.order_counters where fy = '2627' and not is_test;
  insert into public.order_counters (fy, is_test, last_serial)
  values ('2627', false, greatest(5, coalesce((
    select max(right(order_no, 4)::integer) from public.orders
     where not is_test and order_no ~ '^2627[0-9]{4}$'), 0)))
  on conflict (fy, is_test) do update
     set last_serial = greatest(public.order_counters.last_serial, excluded.last_serial), updated_at = now();
  select last_serial into after_serial from public.order_counters where fy = '2627' and not is_test;
  raise notice 'Production order counter 2026-27: % -> % (next order %)',
    coalesce(before_serial::text, 'none'), after_serial, '2627' || lpad((after_serial + 1)::text, 4, '0');
end;
$$;

notify pgrst, 'reload schema';
