-- OTA Business: bills added on their own (without an order) keep their buyer and supplier.
--
-- Run after 20261113000000_fy_order_numbers_no_dash.sql.
--
-- What this does:
--   1. Adds bills.buyer and bills.supplier (nullable text). Null means "take the parties from the
--      bill's orders", which is what every existing bill does, so no row is rewritten. The apps
--      set them only for bills entered from the Bills section, so they can be paid through an
--      advice even with no order linked.
--   2. Removes the test order made while checking the dashless format (26270001, already deleted
--      in the apps) and restarts the FY 2026-27 counter from the highest number still used, so
--      the first real order of the year is 26270001.
-- Safe to run more than once; a re-run never moves the counter back.

-- ---------------------------------------------------------------- 1. bill parties
alter table public.bills
  add column if not exists buyer text,
  add column if not exists supplier text;

-- ---------------------------------------------------------------- 2. test order and counter
delete from public.order_bills
 where order_id = 'bc1c1245-e2ee-43d1-9324-2c82e73e0aee';
delete from public.orders
 where id = 'bc1c1245-e2ee-43d1-9324-2c82e73e0aee'
   and order_no = '26270001'
   and deleted;

update public.order_counters c
   set last_serial = coalesce((select max(right(o.order_no, 4)::integer)
                                 from public.orders o
                                where o.order_no ~ '^2627[0-9]{4}$'), 0),
       updated_at = now()
 where c.fy = '2627'
   -- Only the first run could lower it; numbers are never reused (20261119000000).
   and not exists (select 1 from pg_trigger where tgname = 'order_counters_forward_only');

notify pgrst, 'reload schema';
