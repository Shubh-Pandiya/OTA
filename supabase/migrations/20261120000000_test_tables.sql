-- OTA Business: test data lives in its own tables, with its own T-numbers.
--
-- Run after 20261119000000_numbers_never_reused.sql. Safe to run more than once.
--
-- 1. Every synced table gets a twin with a test_ prefix in the same public schema (so no Supabase
--    API setting changes): test_buyers, test_suppliers, test_orders, test_bills, test_order_bills,
--    test_payment_advices, test_advice_lines, test_ota_deletions, test_order_counters and
--    test_advice_counters. Same columns, indexes and triggers; only testuser1@ota.com can read or
--    write them (RLS), and production logins never see them.
-- 2. Apps 2.0.2 / desktop 1.10.0 and later send the test login to the test_ tables. Older apps signed
--    in as the test user are refused by the production tables (fail safe); production logins are
--    unaffected.
-- 3. Test order and advice numbers are T{FY start YY}{FY end YY}{serial}, e.g. T26270001, from the
--    test counters. Production numbers don't change. Numbers still only move forward.
-- 4. The existing is_test rows (and their tombstones and counters) are moved into the test tables
--    and their FY numbers get the T prefix. Production rows are not touched.
-- 5. ota_clear_test_data() empties the test tables (counters are kept, so T-numbers aren't reused).

-- ---------------------------------------------------------------- the test tables
do $$
declare
  t text;
begin
  foreach t in array array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines',
                           'ota_deletions', 'order_counters', 'advice_counters'] loop
    execute format('create table if not exists public.%I (like public.%I including all)', 'test_' || t, t);
    execute format('alter table public.%I alter column is_test set default true', 'test_' || t);
    if not exists (select 1 from pg_constraint where conname = 'test_' || t || '_is_test'
                     and conrelid = format('public.%I', 'test_' || t)::regclass) then
      execute format('alter table public.%I add constraint %I check (is_test)', 'test_' || t, 'test_' || t || '_is_test');
    end if;
    execute format('alter table public.%I enable row level security', 'test_' || t);
    execute format('revoke all on public.%I from anon', 'test_' || t);
    if t in ('ota_deletions', 'order_counters', 'advice_counters') then
      -- Written only by the functions below.
      execute format('revoke insert, update, delete, truncate on public.%I from authenticated', 'test_' || t);
      execute format('grant select on public.%I to authenticated', 'test_' || t);
    else
      execute format('revoke delete, truncate on public.%I from authenticated', 'test_' || t);
      execute format('grant select, insert, update on public.%I to authenticated', 'test_' || t);
    end if;
    execute format('drop policy if exists "only the test account" on public.%I', 'test_' || t);
    execute format(
      'create policy "only the test account" on public.%I for all to authenticated
         using ((select public.ota_is_test())) with check ((select public.ota_is_test()))',
      'test_' || t
    );
  end loop;
end;
$$;

-- T-numbers are unique within the test tables.
create unique index if not exists test_orders_t_order_no_key on public.test_orders (order_no)
  where order_no ~ '^T[0-9]{8}$';
create unique index if not exists test_payment_advices_t_advice_no_key on public.test_payment_advices (advice_no)
  where advice_no ~ '^T[0-9]{8}$';

-- ---------------------------------------------------------------- production is production only
-- The test login can no longer read or write the production tables (an older app signed in as the
-- test user fails there instead of mixing data).
do $$
declare
  t text;
begin
  foreach t in array array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines', 'ota_deletions'] loop
    execute format('drop policy if exists "test and production rows are separate" on public.%I', t);
    execute format(
      'create policy "test and production rows are separate" on public.%I as restrictive for all to authenticated
         using (not is_test and not (select public.ota_is_test()))
         with check (not is_test and not (select public.ota_is_test()))',
      t
    );
  end loop;
  foreach t in array array['order_counters', 'advice_counters'] loop
    execute format('drop policy if exists "test and production counters are separate" on public.%I', t);
    execute format(
      'create policy "test and production counters are separate" on public.%I as restrictive for select to authenticated
         using (not is_test and not (select public.ota_is_test()))',
      t
    );
  end loop;
end;
$$;

-- ---------------------------------------------------------------- move the existing test rows
-- Runs before the test tables' triggers exist (first run), so rows keep their stamps; later runs
-- find nothing to move.
do $$
declare
  t text;
  n integer;
begin
  foreach t in array array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines', 'ota_deletions'] loop
    execute format('insert into public.%I select * from public.%I where is_test on conflict (id) do nothing', 'test_' || t, t);
    get diagnostics n = row_count;
    -- No tombstones: these rows only change tables, they are not deleted.
    execute format('delete from public.%I where is_test', t);
    raise notice 'Moved % test rows to test_%', n, t;
  end loop;

  update public.test_orders set order_no = 'T' || order_no
   where order_no ~ '^[0-9]{8}$' and substr(order_no, 3, 2)::integer = (substr(order_no, 1, 2)::integer + 1) % 100;
  get diagnostics n = row_count;
  raise notice 'Test orders renumbered to T-numbers: %', n;
  update public.test_payment_advices set advice_no = 'T' || advice_no
   where advice_no ~ '^[0-9]{8}$' and substr(advice_no, 3, 2)::integer = (substr(advice_no, 1, 2)::integer + 1) % 100;
  get diagnostics n = row_count;
  raise notice 'Test advices renumbered to T-numbers: %', n;

  -- Counters move too, never backwards.
  insert into public.test_order_counters (fy, is_test, last_serial, updated_at)
  select fy, true, last_serial, updated_at from public.order_counters where is_test
  on conflict (fy, is_test) do update set last_serial = greatest(public.test_order_counters.last_serial, excluded.last_serial);
  insert into public.test_advice_counters (fy, is_test, last_serial, updated_at)
  select fy, true, last_serial, updated_at from public.advice_counters where is_test
  on conflict (fy, is_test) do update set last_serial = greatest(public.test_advice_counters.last_serial, excluded.last_serial);
  alter table public.order_counters disable trigger order_counters_forward_only;
  alter table public.advice_counters disable trigger advice_counters_forward_only;
  delete from public.order_counters where is_test;
  delete from public.advice_counters where is_test;
  alter table public.order_counters enable trigger order_counters_forward_only;
  alter table public.advice_counters enable trigger advice_counters_forward_only;
end;
$$;

-- ---------------------------------------------------------------- deletes know about test tables
create or replace function public.ota_record_deletion(p_tbl text, p_id uuid, p_at bigint, p_test boolean)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  rel   text := case when p_test then 'test_' || p_tbl else p_tbl end;
  links text := case when p_test then 'test_order_bills' else 'order_bills' end;
  lines text := case when p_test then 'test_advice_lines' else 'advice_lines' end;
  cur   bigint;
  child record;
begin
  if p_tbl not in ('buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines') then
    raise exception 'Unknown table %', p_tbl using errcode = '22023';
  end if;
  execute format('select client_updated_at from public.%I where id = $1', rel) into cur using p_id;
  if cur is not null and cur > p_at then
    return false;
  end if;

  -- Children go with their parent, stamped no older than their own last edit.
  if p_tbl = 'orders' then
    for child in execute format('select id, client_updated_at from public.%I where order_id = $1', links) using p_id loop
      perform public.ota_record_deletion('order_bills', child.id, greatest(p_at, child.client_updated_at), p_test);
    end loop;
  elsif p_tbl = 'bills' then
    for child in execute format('select id, client_updated_at from public.%I where bill_id = $1', links) using p_id loop
      perform public.ota_record_deletion('order_bills', child.id, greatest(p_at, child.client_updated_at), p_test);
    end loop;
    for child in execute format('select id, client_updated_at from public.%I where bill_id = $1', lines) using p_id loop
      perform public.ota_record_deletion('advice_lines', child.id, greatest(p_at, child.client_updated_at), p_test);
    end loop;
  elsif p_tbl = 'payment_advices' then
    for child in execute format('select id, client_updated_at from public.%I where advice_id = $1', lines) using p_id loop
      perform public.ota_record_deletion('advice_lines', child.id, greatest(p_at, child.client_updated_at), p_test);
    end loop;
  end if;

  execute format('delete from public.%I where id = $1', rel) using p_id;

  if p_test then
    insert into public.test_ota_deletions as d (id, tbl, client_updated_at, updated_at, is_test)
    values (p_id, p_tbl, p_at, clock_timestamp(), true)
    on conflict (id) do update
       set client_updated_at = excluded.client_updated_at, updated_at = excluded.updated_at, tbl = excluded.tbl
     where d.client_updated_at < excluded.client_updated_at;
  else
    insert into public.ota_deletions as d (id, tbl, client_updated_at, updated_at, is_test)
    values (p_id, p_tbl, p_at, clock_timestamp(), false)
    on conflict (id) do update
       set client_updated_at = excluded.client_updated_at, updated_at = excluded.updated_at, tbl = excluded.tbl
     where not d.is_test and d.client_updated_at < excluded.client_updated_at;
  end if;
  return true;
end;
$$;

revoke all on function public.ota_record_deletion(text, uuid, bigint, boolean) from public, anon, authenticated;

create or replace function public.ota_delete_rows(p_rows jsonb)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  r       record;
  v_test  boolean := public.ota_is_test();
  done    integer := 0;
  kept    jsonb := '[]';
begin
  if not public.ota_is_staff() then
    raise exception 'Only signed-in staff can delete' using errcode = '42501';
  end if;
  for r in select x."table" as tbl, x.id, x.at
             from jsonb_to_recordset(coalesce(p_rows, '[]'::jsonb)) as x("table" text, id uuid, at bigint)
  loop
    if public.ota_record_deletion(r.tbl, r.id, coalesce(r.at, 0), v_test) then
      done := done + 1;
    else
      kept := kept || to_jsonb(r.id::text);
    end if;
  end loop;
  -- Tombstones only need to outlive the longest time a device stays offline.
  delete from public.ota_deletions where updated_at < now() - interval '400 days';
  delete from public.test_ota_deletions where updated_at < now() - interval '400 days';
  return jsonb_build_object('deleted', done, 'kept', kept);
end;
$$;

revoke all on function public.ota_delete_rows(jsonb) from public, anon;
grant execute on function public.ota_delete_rows(jsonb) to authenticated;

-- Each table reads the tombstones of its own kind (test_* → test_ota_deletions).
create or replace function public.ota_tombstone_guard()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  stamp bigint;
begin
  if left(tg_table_name, 5) = 'test_' then
    select d.client_updated_at into stamp from public.test_ota_deletions d where d.id = new.id;
  else
    select d.client_updated_at into stamp from public.ota_deletions d where d.id = new.id and not d.is_test;
  end if;
  if stamp is null then
    return new;
  end if;
  if stamp >= new.client_updated_at then
    return null;
  end if;
  if left(tg_table_name, 5) = 'test_' then
    delete from public.test_ota_deletions where id = new.id;
  else
    delete from public.ota_deletions where id = new.id;
  end if;
  return new;
end;
$$;

create or replace function public.ota_soft_delete_to_hard()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if left(tg_table_name, 5) = 'test_' then
    perform public.ota_record_deletion(substr(tg_table_name, 6), new.id, new.client_updated_at, true);
  else
    perform public.ota_record_deletion(tg_table_name, new.id, new.client_updated_at, false);
  end if;
  return null;
end;
$$;

revoke all on function public.ota_tombstone_guard() from public, anon, authenticated;
revoke all on function public.ota_soft_delete_to_hard() from public, anon, authenticated;

-- ---------------------------------------------------------------- triggers on the test tables
do $$
declare
  t text;
  before_write text;
begin
  foreach t in array array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines'] loop
    before_write := case
      when t in ('buyers', 'suppliers') then 'ota_catalog_before_write'
      when t in ('orders', 'payment_advices') then 'ota_orders_before_write'
      else 'ota_sync_before_write' end;
    execute format('drop trigger if exists %I on public.%I', 'test_' || t || '_before_write', 'test_' || t);
    execute format('create trigger %I before insert or update on public.%I for each row execute function public.%I()',
      'test_' || t || '_before_write', 'test_' || t, before_write);
    execute format('drop trigger if exists %I on public.%I', 'test_' || t || '_tombstone_guard', 'test_' || t);
    execute format('create trigger %I before insert on public.%I for each row execute function public.ota_tombstone_guard()',
      'test_' || t || '_tombstone_guard', 'test_' || t);
    execute format('drop trigger if exists %I on public.%I', 'test_' || t || '_soft_delete_to_hard', 'test_' || t);
    execute format('create trigger %I after insert or update on public.%I for each row when (new.deleted)
         execute function public.ota_soft_delete_to_hard()',
      'test_' || t || '_soft_delete_to_hard', 'test_' || t);
  end loop;
  foreach t in array array['order_counters', 'advice_counters'] loop
    execute format('drop trigger if exists %I on public.%I', 'test_' || t || '_forward_only', 'test_' || t);
    execute format('create trigger %I before update or delete on public.%I for each row execute function public.ota_counter_forward_only()',
      'test_' || t || '_forward_only', 'test_' || t);
  end loop;
end;
$$;

-- ---------------------------------------------------------------- numbers
create or replace function public.ota_allocate_order_no(p_date date default null)
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_fy     text := public.ota_fy_key(coalesce(p_date, (now() at time zone 'Asia/Kolkata')::date));
  v_test   boolean := public.ota_is_test();
  v_serial integer;
  v_no     text;
begin
  if not public.ota_is_staff() then
    raise exception 'Only signed-in staff can number orders' using errcode = '42501';
  end if;

  if v_test then
    insert into public.test_order_counters (fy, is_test, last_serial)
    select v_fy, true, coalesce(max(right(order_no, 4)::integer), 0)
    from public.test_orders
    where order_no ~ ('^T' || v_fy || '[0-9]{4}$')
    on conflict (fy, is_test) do nothing;
  else
    insert into public.order_counters (fy, is_test, last_serial)
    select v_fy, false, coalesce(max(right(order_no, 4)::integer), 0)
    from public.orders
    where not is_test and order_no ~ ('^' || v_fy || '[0-9]{4}$')
    on conflict (fy, is_test) do nothing;
  end if;

  loop
    v_serial := null;
    if v_test then
      update public.test_order_counters
         set last_serial = last_serial + 1, updated_at = now()
       where fy = v_fy and last_serial < 9999
      returning last_serial into v_serial;
    else
      update public.order_counters
         set last_serial = last_serial + 1, updated_at = now()
       where fy = v_fy and not is_test and last_serial < 9999
      returning last_serial into v_serial;
    end if;

    if v_serial is null then
      raise exception 'ORDER_LIMIT_REACHED: all 9999 order numbers for FY % are used',
        left(v_fy, 2) || '-' || right(v_fy, 2)
        using errcode = 'P0001';
    end if;

    if v_test then
      v_no := 'T' || v_fy || lpad(v_serial::text, 4, '0');
      exit when not exists (select 1 from public.test_orders where order_no = v_no);
    else
      v_no := v_fy || lpad(v_serial::text, 4, '0');
      exit when not exists (select 1 from public.orders where not is_test and order_no = v_no);
    end if;
  end loop;

  return v_no;
end;
$$;

create or replace function public.ota_allocate_advice_no(p_date date default null)
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_fy     text := public.ota_fy_key(coalesce(p_date, (now() at time zone 'Asia/Kolkata')::date));
  v_test   boolean := public.ota_is_test();
  v_serial integer;
  v_no     text;
begin
  if not public.ota_is_staff() then
    raise exception 'Only signed-in staff can number payment advices' using errcode = '42501';
  end if;

  if v_test then
    insert into public.test_advice_counters (fy, is_test, last_serial)
    select v_fy, true, coalesce(max(right(advice_no, 4)::integer), 0)
    from public.test_payment_advices
    where advice_no ~ ('^T' || v_fy || '[0-9]{4}$')
    on conflict (fy, is_test) do nothing;
  else
    insert into public.advice_counters (fy, is_test, last_serial)
    select v_fy, false, coalesce(max(right(advice_no, 4)::integer), 0)
    from public.payment_advices
    where not is_test and advice_no ~ ('^' || v_fy || '[0-9]{4}$')
    on conflict (fy, is_test) do nothing;
  end if;

  loop
    v_serial := null;
    if v_test then
      update public.test_advice_counters
         set last_serial = last_serial + 1, updated_at = now()
       where fy = v_fy and last_serial < 9999
      returning last_serial into v_serial;
    else
      update public.advice_counters
         set last_serial = last_serial + 1, updated_at = now()
       where fy = v_fy and not is_test and last_serial < 9999
      returning last_serial into v_serial;
    end if;

    if v_serial is null then
      raise exception 'ADVICE_LIMIT_REACHED: all 9999 payment advice numbers for FY % are used',
        left(v_fy, 2) || '-' || right(v_fy, 2)
        using errcode = 'P0001';
    end if;

    if v_test then
      v_no := 'T' || v_fy || lpad(v_serial::text, 4, '0');
      exit when not exists (select 1 from public.test_payment_advices where advice_no = v_no);
    else
      v_no := v_fy || lpad(v_serial::text, 4, '0');
      exit when not exists (select 1 from public.payment_advices where not is_test and advice_no = v_no);
    end if;
  end loop;

  return v_no;
end;
$$;

revoke all on function public.ota_allocate_order_no(date) from public, anon;
grant execute on function public.ota_allocate_order_no(date) to authenticated;
revoke all on function public.ota_allocate_advice_no(date) from public, anon;
grant execute on function public.ota_allocate_advice_no(date) to authenticated;

-- ---------------------------------------------------------------- clear test data
-- Empties the test tables; the test counters stay, so T-numbers are never reused.
create or replace function public.ota_clear_test_data()
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  n jsonb;
begin
  if not public.ota_is_test() then
    raise exception 'Only the test account can clear test data' using errcode = '42501';
  end if;
  n := jsonb_build_object(
    'advice_lines',    (select count(*) from public.test_advice_lines),
    'payment_advices', (select count(*) from public.test_payment_advices),
    'order_bills',     (select count(*) from public.test_order_bills),
    'bills',           (select count(*) from public.test_bills),
    'orders',          (select count(*) from public.test_orders),
    'buyers',          (select count(*) from public.test_buyers),
    'suppliers',       (select count(*) from public.test_suppliers)
  );
  truncate public.test_advice_lines, public.test_payment_advices, public.test_order_bills, public.test_bills,
           public.test_orders, public.test_buyers, public.test_suppliers, public.test_ota_deletions;
  return n;
end;
$$;

revoke all on function public.ota_clear_test_data() from public, anon;
grant execute on function public.ota_clear_test_data() to authenticated;

notify pgrst, 'reload schema';
