-- Admin-only cloud clean-up and a hidden test account, in the same database.
--
-- 1. Admins are fixed by sign-in email: varun@ota.com and shubham@ota.com. ota_is_admin() now reads
--    the signed (JWT) email, so a non-admin can't archive, purge or change retention through the API
--    even if a staff row says is_admin. staff.is_admin is kept in step for display.
-- 2. testuser1@ota.com is the test account. Every synced row carries a one-byte is_test flag that the
--    cloud sets on insert (clients can't choose it) and never changes on update. A restrictive RLS
--    policy on each table means production logins only ever see is_test = false rows and the test
--    login only is_test = true rows. Test orders and advices number from their own counters.
-- 3. ota_clear_test_data() lets the test login wipe everything it created.
-- 4. Removes the emulator/CI test rows that were created before this migration (all already
--    soft-deleted) and brings the production FY counters back to the highest real serial.
--
-- Safe to run more than once.

-- ---------------------------------------------------------------- who is who
create or replace function public.ota_is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.ota_is_staff()
     and lower(coalesce(auth.jwt() ->> 'email', '')) in ('varun@ota.com', 'shubham@ota.com')
$$;

create or replace function public.ota_is_test()
returns boolean
language sql
stable
set search_path = ''
as $$
  select lower(coalesce(auth.jwt() ->> 'email', '')) = 'testuser1@ota.com'
$$;

revoke all on function public.ota_is_admin() from public, anon;
grant execute on function public.ota_is_admin() to authenticated;
revoke all on function public.ota_is_test() from public, anon;
grant execute on function public.ota_is_test() to authenticated;

update public.staff s
   set is_admin = lower(u.email) in ('varun@ota.com', 'shubham@ota.com')
  from auth.users u
 where u.id = s.user_id
   and s.is_admin is distinct from (lower(u.email) in ('varun@ota.com', 'shubham@ota.com'));

-- ---------------------------------------------------------------- test tag
-- Set by the cloud: the inserting login decides it, and it never changes afterwards.
create or replace function public.ota_test_tag()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    new.is_test := public.ota_is_test();
  else
    new.is_test := old.is_test;
  end if;
  return new;
end;
$$;

do $$
declare
  t text;
begin
  foreach t in array array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines'] loop
    execute format('alter table public.%I add column if not exists is_test boolean not null default false', t);
    -- Named so it runs after the existing *_before_write trigger (triggers fire in name order).
    execute format('drop trigger if exists %I on public.%I', t || '_test_tag', t);
    execute format(
      'create trigger %I before insert or update on public.%I for each row execute function public.ota_test_tag()',
      t || '_test_tag', t
    );
    execute format('drop policy if exists "test and production rows are separate" on public.%I', t);
    execute format(
      'create policy "test and production rows are separate" on public.%I as restrictive for all to authenticated
         using (is_test = (select public.ota_is_test()))
         with check (is_test = (select public.ota_is_test()))',
      t
    );
  end loop;
end;
$$;

-- ---------------------------------------------------------------- separate test counters
do $$
declare
  t text;
begin
  foreach t in array array['order_counters', 'advice_counters'] loop
    execute format('alter table public.%I add column if not exists is_test boolean not null default false', t);
    if (select array_length(c.conkey, 1) from pg_constraint c
         where c.conrelid = format('public.%I', t)::regclass and c.contype = 'p') = 1 then
      execute format('alter table public.%I drop constraint %I', t, t || '_pkey');
      execute format('alter table public.%I add constraint %I primary key (fy, is_test)', t, t || '_pkey');
    end if;
    execute format('drop policy if exists "test and production counters are separate" on public.%I', t);
    execute format(
      'create policy "test and production counters are separate" on public.%I as restrictive for select to authenticated
         using (is_test = (select public.ota_is_test()))',
      t
    );
  end loop;
end;
$$;

-- An FY number is unique within production and, separately, within test data.
drop index if exists public.orders_fy_order_no_key;
create unique index if not exists orders_fy_order_no_test_key on public.orders (is_test, order_no)
  where order_no ~ '^[0-9]{8}$'
    and substr(order_no, 3, 2)::integer = (substr(order_no, 1, 2)::integer + 1) % 100;

drop index if exists public.payment_advices_fy_advice_no_key;
create unique index if not exists payment_advices_fy_advice_no_test_key on public.payment_advices (is_test, advice_no)
  where advice_no ~ '^[0-9]{8}$'
    and substr(advice_no, 3, 2)::integer = (substr(advice_no, 1, 2)::integer + 1) % 100;

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

  -- First number of the year (or first after this migration): continue after the highest one used.
  insert into public.order_counters (fy, is_test, last_serial)
  select v_fy, v_test, coalesce(max(right(order_no, 4)::integer), 0)
  from public.orders
  where is_test = v_test and order_no ~ ('^' || v_fy || '[0-9]{4}$')
  on conflict (fy, is_test) do nothing;

  loop
    update public.order_counters
       set last_serial = last_serial + 1, updated_at = now()
     where fy = v_fy and is_test = v_test and last_serial < 9999
    returning last_serial into v_serial;

    if v_serial is null then
      raise exception 'ORDER_LIMIT_REACHED: all 9999 order numbers for FY % are used',
        left(v_fy, 2) || '-' || right(v_fy, 2)
        using errcode = 'P0001';
    end if;

    v_no := v_fy || lpad(v_serial::text, 4, '0');
    exit when not exists (select 1 from public.orders where is_test = v_test and order_no = v_no);
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

  insert into public.advice_counters (fy, is_test, last_serial)
  select v_fy, v_test, coalesce(max(right(advice_no, 4)::integer), 0)
  from public.payment_advices
  where is_test = v_test and advice_no ~ ('^' || v_fy || '[0-9]{4}$')
  on conflict (fy, is_test) do nothing;

  loop
    update public.advice_counters
       set last_serial = last_serial + 1, updated_at = now()
     where fy = v_fy and is_test = v_test and last_serial < 9999
    returning last_serial into v_serial;

    if v_serial is null then
      raise exception 'ADVICE_LIMIT_REACHED: all 9999 payment advice numbers for FY % are used',
        left(v_fy, 2) || '-' || right(v_fy, 2)
        using errcode = 'P0001';
    end if;

    v_no := v_fy || lpad(v_serial::text, 4, '0');
    exit when not exists (select 1 from public.payment_advices where is_test = v_test and advice_no = v_no);
  end loop;

  return v_no;
end;
$$;

revoke all on function public.ota_allocate_order_no(date) from public, anon;
grant execute on function public.ota_allocate_order_no(date) to authenticated;
revoke all on function public.ota_allocate_advice_no(date) from public, anon;
grant execute on function public.ota_allocate_advice_no(date) to authenticated;

-- ---------------------------------------------------------------- admin clean-up: production rows only
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
      where t.id = r.id and t.updated_at = r.updated_at and not t.is_test',
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
     where not o.is_test and o.archived_at is not null and o.updated_at <= o.archived_at
       and public.ota_order_day(o.order_date, o.updated_at) < cutoff
    returning o.id
  )
  select coalesce(array_agg(id), '{}') into gone_orders from purged;

  with purged as (
    delete from public.order_bills l
     where not l.is_test and l.archived_at is not null and l.updated_at <= l.archived_at
       and (l.order_id = any(gone_orders)
            or ((l.updated_at at time zone 'Asia/Kolkata')::date < cutoff
                and not exists (select 1 from public.orders o where o.id = l.order_id)))
    returning l.bill_id
  )
  select count(*), coalesce(array_agg(distinct bill_id), '{}') into gone_links, touched from purged;

  with purged as (
    delete from public.payment_advices a
     where not a.is_test and a.archived_at is not null and a.updated_at <= a.archived_at
       and coalesce(a.pay_date, (a.updated_at at time zone 'Asia/Kolkata')::date) < cutoff
    returning a.id
  )
  select coalesce(array_agg(id), '{}') into gone_advices from purged;

  with purged as (
    delete from public.advice_lines l
     where not l.is_test and l.archived_at is not null and l.updated_at <= l.archived_at
       and (l.advice_id = any(gone_advices)
            or ((l.updated_at at time zone 'Asia/Kolkata')::date < cutoff
                and not exists (select 1 from public.payment_advices a where a.id = l.advice_id)))
    returning l.bill_id
  )
  select count(*), coalesce(array_agg(distinct bill_id), '{}') into gone_lines, touched2 from purged;

  delete from public.bills b
   where not b.is_test and b.archived_at is not null and b.updated_at <= b.archived_at
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
grant execute on function public.ota_mark_archived(text, jsonb) to authenticated;
revoke all on function public.ota_purge_archived_orders() from public, anon;
grant execute on function public.ota_purge_archived_orders() to authenticated;

-- ---------------------------------------------------------------- "Clear all my test data"
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
  delete from public.order_counters where is_test;
  delete from public.advice_counters where is_test;
  return n;
end;
$$;

revoke all on function public.ota_clear_test_data() from public, anon;
grant execute on function public.ota_clear_test_data() to authenticated;

-- ---------------------------------------------------------------- earlier test rows
-- Every row below was made by the test login or by emulator/CI runs before this migration and is
-- already soft-deleted (order_bills 0818a894… is an orphan link of a purge test). They are removed
-- rather than retagged, so a real bill numbered e.g. "46" or "SB-901" never clashes with an old test
-- bill's content-derived id. Two real catalog parties are kept and restored (see the end). Matched
-- by exact id; re-running removes nothing more.
do $$
declare
  gone integer := 0;
  c    integer;
begin
  delete from public.advice_lines where deleted and id in (
    '786d2ecb-727d-3142-bd90-7832ded534bc',
    '7e2850e0-bcf5-37d3-895f-75fc04ebdacb',
    'ca19eceb-4b66-3511-ac6f-919838de9992',
    'b04220c1-2acb-3a6f-94a7-c02b9e4faa28',
    '04238f17-1614-34a8-9e2a-48162daed98e',
    '960561ed-e880-3afb-b3cc-2991b2805b86'
  );
  get diagnostics c = row_count; gone := gone + c;

  delete from public.payment_advices where deleted and id in (
    '191aa05c-a98a-45a6-aa4e-f06a831d23bf',
    'd2d7226a-0ec8-4d14-ba48-113f517c7c2e',
    '35350b7a-f103-4bab-9bf7-ab892b67d2d3',
    '132e81de-2f66-4cda-a285-7a692ce3466b',
    'bccfcfa3-a6f8-4a33-9423-b996bfe3dc1e'
  );
  get diagnostics c = row_count; gone := gone + c;

  delete from public.order_bills where id in (
    '0818a894-87c0-4dd1-b37a-e1edb3878289',
    '642d07c7-04f3-3396-895d-33d7c1bc429e',
    'bed6f828-ea7f-3ee2-9b6e-dea87c8c58b6',
    'c1e51a02-21d4-330e-8087-306bcbf46fce',
    '13d2d232-c70f-35b2-9c58-678010c84949',
    '3f27e74e-215a-384c-ad86-075d41376605',
    '10fe2e50-9fc9-3044-9793-f86a9961c35b',
    'b7244a4a-cca8-36a3-ac69-5d2cecaade6c',
    'f758058b-a4d7-368f-975d-4e2923acc469'
  );
  get diagnostics c = row_count; gone := gone + c;

  delete from public.bills b where b.deleted and b.id in (
    '92fad4c4-ffeb-46b2-8e0a-a30552db85c6',
    '64055999-7436-3acd-b91e-2e5881d94814',
    '1221055e-e90b-3bec-ae0a-66a5c0961724',
    '412137e8-a281-3401-9178-6bc8686f71f3',
    '5d7c53a6-e99a-3aa1-a9a0-42d9b083e30a',
    '6dc5467a-9cf4-3c32-a0f1-e7b62618f2bc',
    '713a5a0a-23a6-3b7f-ad64-f668698a0a8b',
    'abe76be8-b5eb-3dfe-9dd6-5191381d1943',
    'b49d1675-5aae-3dd2-be1d-0bc7bde3c21a'
  )
    and not exists (select 1 from public.order_bills l where l.bill_id = b.id)
    and not exists (select 1 from public.advice_lines l where l.bill_id = b.id);
  get diagnostics c = row_count; gone := gone + c;

  delete from public.orders where deleted and id in (
    'a80f923b-806b-4aa1-b3be-015a7868afcb',
    'e73f3b81-dc3e-4888-9a35-b16fe04c34f6',
    '99453813-6d65-4ffc-a225-cbfa67fdaae8',
    'eb20a000-8ac2-4093-a5fb-6a62bd0fb759',
    '8d1ea496-8776-4788-b7d8-6865f4dd23c0',
    '28113ea8-8199-4905-b36a-95600c6912ac',
    '28255814-8b0f-45eb-b434-367916151c34',
    '33229e3a-8a14-496f-8af1-1b40df744a5e',
    '5b3353d2-f2d0-42e4-9e04-f3b0790b21d1',
    '833b8cb3-9d69-4480-a58e-026790c9c3d3',
    'c956c971-496e-46c8-9897-5f83e891996e',
    'a56b8e9d-f477-418e-af05-2ef4cf6a5a9e',
    'e060bc77-0af3-42fc-be77-14ee5a365b14',
    'f8adbbf4-900a-484a-80c9-05291e2abd43',
    'ea59c98b-7b31-4f6e-8161-18d55da0ea7c',
    'be78a740-8d0c-453e-a0c3-833aacaa56b1'
  );
  get diagnostics c = row_count; gone := gone + c;

  delete from public.buyers where deleted and id in (
    'c60d1ef8-e2c9-4bee-853d-ab8288622902',
    'd7c25a7e-1218-4c7b-8244-a0598384c426',
    '03ea49ed-0d46-4843-bbab-899af6d3a603',
    '73c0b3fe-7810-4a66-8308-e39cd21cec70'
  );
  get diagnostics c = row_count; gone := gone + c;

  delete from public.suppliers where deleted and id in (
    '09448e38-b959-4ae5-b3ed-0055830afc4f',
    'a1959831-7179-47c0-8658-1d1072d3850d',
    'a2d32c2e-eca1-44ee-9f46-5fd84f59f205'
  );
  get diagnostics c = row_count; gone := gone + c;

  -- Real parties (kept as production rows): brought back for type-ahead if they were deleted. The
  -- newer client_updated_at makes every device take the restore.
  update public.buyers
     set deleted = false, client_updated_at = (extract(epoch from clock_timestamp()) * 1000)::bigint
   where id = '5f86a631-cc49-4c08-8453-42dd2abbbadc' and deleted and not is_test;
  update public.suppliers
     set deleted = false, client_updated_at = (extract(epoch from clock_timestamp()) * 1000)::bigint
   where id = '45fe80a9-c6be-47d0-97f2-4aa797ba1c3e' and deleted and not is_test;

  -- Only on the run that removed them: put each production counter back to the highest real
  -- serial still in use (0 when none), so the first real order of 2026-27 is 26270001.
  if gone > 0 then
    update public.order_counters k
       set last_serial = coalesce((
             select max(right(o.order_no, 4)::integer) from public.orders o
              where not o.is_test and o.order_no ~ ('^' || k.fy || '[0-9]{4}$')), 0),
           updated_at = now()
     where not k.is_test;
    update public.advice_counters k
       set last_serial = coalesce((
             select max(right(a.advice_no, 4)::integer) from public.payment_advices a
              where not a.is_test and a.advice_no ~ ('^' || k.fy || '[0-9]{4}$')), 0),
           updated_at = now()
     where not k.is_test;
  end if;
  raise notice 'Removed % earlier test rows', gone;
end;
$$;

notify pgrst, 'reload schema';
