-- The test account's data lives in its own schema (ota_test, same tables as public), so a laptop
-- can hold real and test data side by side and each login sees only its own (README "Test account").
-- Every migration runs once per schema; this one only acts in ota_test, on a laptop that was signed
-- in as the test account before: its synced rows, delete queue, sync cursors and counters were in
-- public and move here. The Accounts books stay where they are (they were admin-only then).
do $$
begin
  if current_schema() <> 'ota_test' then return; end if;
  if coalesce((select value from public.kv where key = 'data.mode'), 'production') <> 'test' then return; end if;

  insert into buyers select * from public.buyers on conflict do nothing;
  insert into suppliers select * from public.suppliers on conflict do nothing;
  insert into orders select * from public.orders on conflict do nothing;
  insert into bills select * from public.bills on conflict do nothing;
  insert into order_bills select * from public.order_bills on conflict do nothing;
  insert into payment_advices select * from public.payment_advices on conflict do nothing;
  insert into advice_lines select * from public.advice_lines on conflict do nothing;
  insert into pending_deletions select * from public.pending_deletions on conflict do nothing;
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
