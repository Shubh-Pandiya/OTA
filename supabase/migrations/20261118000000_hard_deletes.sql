-- Hard deletes everywhere, with a compact deletion log so deletes still reach every device.
--
-- 1. public.ota_deletions keeps one small row per deleted record: (id, table, delete time on the
--    deleting device's clock, server cursor time, is_test). Devices pull it like any other table
--    (cursor = updated_at, id) and remove the row locally unless they hold a newer edit.
-- 2. ota_delete_rows(p_rows) is the only way to delete: for each {table, id, at} it removes the row
--    (and its links / advice lines) unless the cloud copy was edited after `at`, and logs a tombstone.
-- 3. A stale device can't bring a deleted row back: inserting/upserting a row whose tombstone is as
--    new as or newer than the row is silently skipped. An edit made after the delete wins (and clears
--    the tombstone).
-- 4. Older apps still soft-delete (deleted = true). Such a write is turned into a real delete plus a
--    tombstone, so no deleted = true row is ever kept. The deleted column stays (always false) only so
--    older apps keep working until they are updated.
-- 5. Retention purge is unchanged: it only frees the cloud, writes no tombstones, so the desktop keeps
--    purged rows. Tombstones older than 400 days are pruned.
-- 6. Every row that is already soft-deleted is removed now (a tombstone is kept for each).
--
-- Safe to run more than once.

-- ---------------------------------------------------------------- deletion log
create table if not exists public.ota_deletions (
  id                uuid primary key,
  tbl               text not null check (tbl in ('buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines')),
  client_updated_at bigint not null default 0,
  updated_at        timestamptz not null default clock_timestamp(),
  is_test           boolean not null default false
);

create index if not exists ota_deletions_updated_at_idx on public.ota_deletions (updated_at, id);

alter table public.ota_deletions enable row level security;

drop policy if exists "staff can read deletions" on public.ota_deletions;
create policy "staff can read deletions" on public.ota_deletions
  for select to authenticated using ((select public.ota_is_staff()));

drop policy if exists "test and production rows are separate" on public.ota_deletions;
create policy "test and production rows are separate" on public.ota_deletions as restrictive for all to authenticated
  using (is_test = (select public.ota_is_test()))
  with check (is_test = (select public.ota_is_test()));

-- Written only by the functions below.
revoke all on public.ota_deletions from anon;
revoke insert, update, delete, truncate on public.ota_deletions from authenticated;
grant select on public.ota_deletions to authenticated;

-- ---------------------------------------------------------------- one delete
-- Deletes p_id from p_tbl unless the cloud holds an edit newer than p_at, cascades to the rows that
-- only make sense with it, and logs a tombstone. Returns false when a newer edit won.
create or replace function public.ota_record_deletion(p_tbl text, p_id uuid, p_at bigint, p_test boolean)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  cur   bigint;
  child record;
begin
  if p_tbl not in ('buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines') then
    raise exception 'Unknown table %', p_tbl using errcode = '22023';
  end if;
  execute format('select client_updated_at from public.%I where id = $1 and is_test = $2', p_tbl)
    into cur using p_id, p_test;
  if cur is not null and cur > p_at then
    return false;
  end if;

  -- Children go with their parent, stamped no older than their own last edit.
  if p_tbl = 'orders' then
    for child in select id, client_updated_at from public.order_bills where order_id = p_id and is_test = p_test loop
      perform public.ota_record_deletion('order_bills', child.id, greatest(p_at, child.client_updated_at), p_test);
    end loop;
  elsif p_tbl = 'bills' then
    for child in select id, client_updated_at from public.order_bills where bill_id = p_id and is_test = p_test loop
      perform public.ota_record_deletion('order_bills', child.id, greatest(p_at, child.client_updated_at), p_test);
    end loop;
    for child in select id, client_updated_at from public.advice_lines where bill_id = p_id and is_test = p_test loop
      perform public.ota_record_deletion('advice_lines', child.id, greatest(p_at, child.client_updated_at), p_test);
    end loop;
  elsif p_tbl = 'payment_advices' then
    for child in select id, client_updated_at from public.advice_lines where advice_id = p_id and is_test = p_test loop
      perform public.ota_record_deletion('advice_lines', child.id, greatest(p_at, child.client_updated_at), p_test);
    end loop;
  end if;

  execute format('delete from public.%I where id = $1 and is_test = $2', p_tbl) using p_id, p_test;

  insert into public.ota_deletions as d (id, tbl, client_updated_at, updated_at, is_test)
  values (p_id, p_tbl, p_at, clock_timestamp(), p_test)
  on conflict (id) do update
     set client_updated_at = excluded.client_updated_at, updated_at = excluded.updated_at, tbl = excluded.tbl
   where d.is_test = excluded.is_test and d.client_updated_at < excluded.client_updated_at;
  return true;
end;
$$;

revoke all on function public.ota_record_deletion(text, uuid, bigint, boolean) from public, anon, authenticated;

-- ---------------------------------------------------------------- the delete RPC
-- p_rows: [{"table": "orders", "id": "…", "at": 1731000000000}, …]. Returns {deleted, kept} where
-- kept lists ids whose cloud copy was edited after the delete (that edit wins).
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
  return jsonb_build_object('deleted', done, 'kept', kept);
end;
$$;

revoke all on function public.ota_delete_rows(jsonb) from public, anon;
grant execute on function public.ota_delete_rows(jsonb) to authenticated;

-- ---------------------------------------------------------------- guards
-- Before a row is written: a stale copy of something deleted later is dropped; a newer re-creation
-- clears the old tombstone.
create or replace function public.ota_tombstone_guard()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  stamp bigint;
begin
  select d.client_updated_at into stamp
    from public.ota_deletions d
   where d.id = new.id and d.is_test = public.ota_is_test();
  if stamp is null then
    return new;
  end if;
  if stamp >= new.client_updated_at then
    return null;
  end if;
  delete from public.ota_deletions where id = new.id;
  return new;
end;
$$;

-- After a row is written with deleted = true (an app from before hard deletes): delete it for real.
create or replace function public.ota_soft_delete_to_hard()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform public.ota_record_deletion(tg_table_name, new.id, new.client_updated_at, new.is_test);
  return null;
end;
$$;

revoke all on function public.ota_tombstone_guard() from public, anon, authenticated;
revoke all on function public.ota_soft_delete_to_hard() from public, anon, authenticated;

do $$
declare
  t text;
begin
  foreach t in array array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines'] loop
    execute format('drop trigger if exists %I on public.%I', t || '_tombstone_guard', t);
    execute format(
      'create trigger %I before insert on public.%I for each row execute function public.ota_tombstone_guard()',
      t || '_tombstone_guard', t
    );
    execute format('drop trigger if exists %I on public.%I', t || '_soft_delete_to_hard', t);
    execute format(
      'create trigger %I after insert or update on public.%I for each row when (new.deleted)
         execute function public.ota_soft_delete_to_hard()',
      t || '_soft_delete_to_hard', t
    );
  end loop;
end;
$$;

-- ---------------------------------------------------------------- clear test data
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
  delete from public.order_counters where is_test;
  delete from public.advice_counters where is_test;
  return n;
end;
$$;

revoke all on function public.ota_clear_test_data() from public, anon;
grant execute on function public.ota_clear_test_data() to authenticated;

-- ---------------------------------------------------------------- rows already soft-deleted
-- Links and lines of a deleted order / bill / advice go too. Each removed row leaves a tombstone so
-- a device that still holds it removes it on its next sync instead of uploading it again.
do $$
declare
  t text;
  c integer;
  n jsonb := '{}';
begin
  insert into public.ota_deletions (id, tbl, client_updated_at, is_test)
  select l.id, 'order_bills', l.client_updated_at, l.is_test from public.order_bills l
   where l.deleted
      or exists (select 1 from public.orders o where o.id = l.order_id and o.deleted)
      or exists (select 1 from public.bills b where b.id = l.bill_id and b.deleted)
  on conflict (id) do nothing;
  insert into public.ota_deletions (id, tbl, client_updated_at, is_test)
  select l.id, 'advice_lines', l.client_updated_at, l.is_test from public.advice_lines l
   where l.deleted
      or exists (select 1 from public.payment_advices a where a.id = l.advice_id and a.deleted)
      or exists (select 1 from public.bills b where b.id = l.bill_id and b.deleted)
  on conflict (id) do nothing;

  delete from public.order_bills l
   where l.deleted
      or exists (select 1 from public.orders o where o.id = l.order_id and o.deleted)
      or exists (select 1 from public.bills b where b.id = l.bill_id and b.deleted);
  get diagnostics c = row_count; n := n || jsonb_build_object('order_bills', c);
  delete from public.advice_lines l
   where l.deleted
      or exists (select 1 from public.payment_advices a where a.id = l.advice_id and a.deleted)
      or exists (select 1 from public.bills b where b.id = l.bill_id and b.deleted);
  get diagnostics c = row_count; n := n || jsonb_build_object('advice_lines', c);

  foreach t in array array['payment_advices', 'bills', 'orders', 'buyers', 'suppliers'] loop
    execute format(
      'insert into public.ota_deletions (id, tbl, client_updated_at, is_test)
       select id, %L, client_updated_at, is_test from public.%I where deleted on conflict (id) do nothing',
      t, t
    );
    execute format('delete from public.%I where deleted', t);
    get diagnostics c = row_count; n := n || jsonb_build_object(t, c);
  end loop;
  raise notice 'Removed soft-deleted rows: %', n;
end;
$$;
