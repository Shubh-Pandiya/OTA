-- The test account's data lives in its own schema (ota_test, same tables as public), so a laptop
-- can hold real and test data side by side and each login sees only its own (README "Test account").
-- Every migration runs once per schema; this one only acts in ota_test, on a laptop that was signed
-- in as the test account before: its synced rows, delete queue, sync cursors and counters were in
-- public and move here. The Accounts books stay where they are (they were admin-only then).
do $$
declare
  t text;
  cols text;
begin
  if current_schema() <> 'ota_test' then return; end if;
  if coalesce((select value from public.kv where key = 'data.mode'), 'production') <> 'test' then return; end if;

  -- By this schema's own columns: public may already have columns added by later migrations.
  for t in select unnest(array['buyers', 'suppliers', 'orders', 'bills', 'order_bills', 'payment_advices', 'advice_lines', 'pending_deletions']) loop
    select string_agg(quote_ident(column_name), ', ' order by ordinal_position) into cols
      from information_schema.columns where table_schema = 'ota_test' and table_name = t;
    execute format('insert into ota_test.%I (%s) select %s from public.%I on conflict do nothing', t, cols, cols, t);
  end loop;
  insert into kv select * from public.kv where key like 'cursor.%' or key like 'counter.%' or key = 'sync.last'
    on conflict (key) do update set value = excluded.value;

  delete from public.advice_lines;
  delete from public.payment_advices;
  delete from public.order_bills;
  delete from public.bills;
  delete from public.orders;
  delete from public.buyers;
  delete from public.suppliers;
  delete from public.pending_deletions;
  delete from public.kv where key like 'cursor.%' or key like 'counter.%' or key = 'sync.last';
end $$;
