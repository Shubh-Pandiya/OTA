-- OTA Business: drop the is_test column. Test data has its own test_ tables since
-- 20261120000000_test_tables.sql, so the flag is redundant everywhere.
--
-- Run after 20261120000000_test_tables.sql (don't re-run that one afterwards). Safe to run more than once.
--
-- - No app (any release) sends or selects is_test, so sync keeps working with no app update.
-- - Refuses to run if a production table still holds a test row (nothing is lost or mixed).
-- - Production tables refuse the test login, test_ tables allow only it (RLS by login, not by column).
-- - Counters are keyed by fy alone and stay forward-only; FY numbers stay unique per table.

begin;

-- ---------------------------------------------------------------- nothing left to move
do $$
declare
  t text;
  n bigint;
begin
  foreach t in array array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines',
                           'ota_deletions', 'order_counters', 'advice_counters'] loop
    if exists (select 1 from information_schema.columns
                where table_schema = 'public' and table_name = t and column_name = 'is_test') then
      execute format('select count(*) from public.%I where is_test', t) into n;
      if n > 0 then
        raise exception 'public.% still has % test row(s): run 20261120000000_test_tables.sql first', t, n;
      end if;
    end if;
  end loop;
end;
$$;

-- ---------------------------------------------------------------- functions without is_test
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
    insert into public.test_ota_deletions as d (id, tbl, client_updated_at, updated_at)
    values (p_id, p_tbl, p_at, clock_timestamp())
    on conflict (id) do update
       set client_updated_at = excluded.client_updated_at, updated_at = excluded.updated_at, tbl = excluded.tbl
     where d.client_updated_at < excluded.client_updated_at;
  else
    insert into public.ota_deletions as d (id, tbl, client_updated_at, updated_at)
    values (p_id, p_tbl, p_at, clock_timestamp())
    on conflict (id) do update
       set client_updated_at = excluded.client_updated_at, updated_at = excluded.updated_at, tbl = excluded.tbl
     where d.client_updated_at < excluded.client_updated_at;
  end if;
  return true;
end;
$$;

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
    select d.client_updated_at into stamp from public.ota_deletions d where d.id = new.id;
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
    insert into public.test_order_counters (fy, last_serial)
    select v_fy, coalesce(max(right(order_no, 4)::integer), 0)
    from public.test_orders
    where order_no ~ ('^T' || v_fy || '[0-9]{4}$')
    on conflict (fy) do nothing;
  else
    insert into public.order_counters (fy, last_serial)
    select v_fy, coalesce(max(right(order_no, 4)::integer), 0)
    from public.orders
    where order_no ~ ('^' || v_fy || '[0-9]{4}$')
    on conflict (fy) do nothing;
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
       where fy = v_fy and last_serial < 9999
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
      exit when not exists (select 1 from public.orders where order_no = v_no);
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
    insert into public.test_advice_counters (fy, last_serial)
    select v_fy, coalesce(max(right(advice_no, 4)::integer), 0)
    from public.test_payment_advices
    where advice_no ~ ('^T' || v_fy || '[0-9]{4}$')
    on conflict (fy) do nothing;
  else
    insert into public.advice_counters (fy, last_serial)
    select v_fy, coalesce(max(right(advice_no, 4)::integer), 0)
    from public.payment_advices
    where advice_no ~ ('^' || v_fy || '[0-9]{4}$')
    on conflict (fy) do nothing;
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
       where fy = v_fy and last_serial < 9999
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
      exit when not exists (select 1 from public.payment_advices where advice_no = v_no);
    end if;
  end loop;

  return v_no;
end;
$$;

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

revoke all on function public.ota_record_deletion(text, uuid, bigint, boolean) from public, anon, authenticated;
revoke all on function public.ota_tombstone_guard() from public, anon, authenticated;
revoke all on function public.ota_allocate_order_no(date) from public, anon;
grant execute on function public.ota_allocate_order_no(date) to authenticated;
revoke all on function public.ota_allocate_advice_no(date) from public, anon;
grant execute on function public.ota_allocate_advice_no(date) to authenticated;
revoke all on function public.ota_mark_archived(text, jsonb) from public, anon;
grant execute on function public.ota_mark_archived(text, jsonb) to authenticated;
revoke all on function public.ota_purge_archived_orders() from public, anon;
grant execute on function public.ota_purge_archived_orders() to authenticated;

-- ---------------------------------------------------------------- the tag trigger goes
do $$
declare
  t text;
begin
  foreach t in array array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines'] loop
    execute format('drop trigger if exists %I on public.%I', t || '_test_tag', t);
  end loop;
end;
$$;
drop function if exists public.ota_test_tag();

-- ---------------------------------------------------------------- production is for production logins
do $$
declare
  t text;
begin
  foreach t in array array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines', 'ota_deletions'] loop
    execute format('drop policy if exists "test and production rows are separate" on public.%I', t);
    execute format(
      'create policy "test and production rows are separate" on public.%I as restrictive for all to authenticated
         using (not (select public.ota_is_test())) with check (not (select public.ota_is_test()))',
      t
    );
  end loop;
  foreach t in array array['order_counters', 'advice_counters'] loop
    execute format('drop policy if exists "test and production counters are separate" on public.%I', t);
    execute format(
      'create policy "test and production counters are separate" on public.%I as restrictive for select to authenticated
         using (not (select public.ota_is_test()))',
      t
    );
  end loop;
end;
$$;

-- ---------------------------------------------------------------- drop the column
-- Also drops what is built on it: the (fy, is_test) counter keys, the (is_test, number) indexes and
-- the test_ tables' check (is_test) constraints. They are replaced just below.
do $$
declare
  t text;
begin
  foreach t in array array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines',
                           'ota_deletions', 'order_counters', 'advice_counters'] loop
    execute format('alter table public.%I drop column if exists is_test', t);
    execute format('alter table public.%I drop column if exists is_test', 'test_' || t);
  end loop;
  foreach t in array array['order_counters', 'advice_counters', 'test_order_counters', 'test_advice_counters'] loop
    if not exists (select 1 from pg_constraint where conrelid = format('public.%I', t)::regclass and contype = 'p') then
      execute format('alter table public.%I add constraint %I primary key (fy)', t, t || '_pkey');
    end if;
  end loop;
end;
$$;

-- An FY number is unique among real orders / advices (test ones have their own T-number index).
create unique index if not exists orders_fy_order_no_key on public.orders (order_no)
  where order_no ~ '^[0-9]{8}$'
    and substr(order_no, 3, 2)::integer = (substr(order_no, 1, 2)::integer + 1) % 100;
create unique index if not exists payment_advices_fy_advice_no_key on public.payment_advices (advice_no)
  where advice_no ~ '^[0-9]{8}$'
    and substr(advice_no, 3, 2)::integer = (substr(advice_no, 1, 2)::integer + 1) % 100;

commit;

notify pgrst, 'reload schema';
