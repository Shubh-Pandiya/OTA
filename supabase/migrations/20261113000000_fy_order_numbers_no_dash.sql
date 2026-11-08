-- OTA Business: financial-year order numbers without the dash.
--
-- Run after 20261112000000_fy_order_numbers.sql. New orders are numbered
-- {FY start YY}{FY end YY}{4-digit serial}, e.g. 26270001 is the first order of FY 2026-27.
-- These numbers are always 8 digits, so they never look like the older 12-digit numbers
-- (e.g. 120261107001), which stay as they are.
--
-- What this does:
--   1. Removes the two test orders made while checking the dashed format (26-270001 and
--      26-270002, already deleted in the apps), so the first real order of FY 2026-27 is 26270001.
--   2. Rewrites any other dashed number (26-27xxxx) without the dash.
--   3. Re-keys the counters ('26-27' -> '2627'). Each year's counter restarts from the highest
--      number already used in that year, so no number is handed out twice.
--   4. Updates the numbering functions and the unique index.
-- Safe to run more than once.

-- ---------------------------------------------------------------- 1. test orders
delete from public.order_bills
 where order_id in ('be78a740-8d0c-453e-a0c3-833aacaa56b1', 'ea59c98b-7b31-4f6e-8161-18d55da0ea7c');
delete from public.orders
 where id in ('be78a740-8d0c-453e-a0c3-833aacaa56b1', 'ea59c98b-7b31-4f6e-8161-18d55da0ea7c')
   and order_no in ('26-270001', '26-270002')
   and deleted;

-- ---------------------------------------------------------------- 2. dashed numbers
drop index if exists public.orders_fy_order_no_key;

update public.orders o
   set order_no = replace(o.order_no, '-', '')
 where o.order_no ~ '^[0-9]{2}-[0-9]{6}$'
   and not exists (select 1 from public.orders x where x.order_no = replace(o.order_no, '-', ''));

-- ---------------------------------------------------------------- 3. counters
-- Dropped rather than converted: the next call recreates a year's counter from the highest
-- number already used in that year.
alter table public.order_counters drop constraint if exists order_counters_fy_check;
delete from public.order_counters where fy !~ '^[0-9]{4}$';
alter table public.order_counters add constraint order_counters_fy_check check (fy ~ '^[0-9]{4}$');

-- ---------------------------------------------------------------- 4. functions and index
-- '2627' for any date from 1 Apr 2026 to 31 Mar 2027.
create or replace function public.ota_fy_key(p_date date)
returns text
language sql
immutable
set search_path = ''
as $$
  select to_char(y % 100, 'FM00') || to_char((y + 1) % 100, 'FM00')
  from (select extract(year from p_date)::int - case when extract(month from p_date) < 4 then 1 else 0 end as y) fy
$$;

-- Hands out the next number for the financial year of p_date (default: today in India).
-- The counter row is locked by the UPDATE, so concurrent callers each get a different number.
-- A number already used by an order (e.g. typed by hand or restored) is skipped.
create or replace function public.ota_allocate_order_no(p_date date default null)
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_fy     text := public.ota_fy_key(coalesce(p_date, (now() at time zone 'Asia/Kolkata')::date));
  v_serial integer;
  v_no     text;
begin
  if not public.ota_is_staff() then
    raise exception 'Only signed-in staff can number orders' using errcode = '42501';
  end if;

  -- First order of the year: start after any order already numbered in this year's format.
  insert into public.order_counters (fy, last_serial)
  select v_fy, coalesce(max(right(order_no, 4)::integer), 0)
  from public.orders
  where order_no ~ ('^' || v_fy || '[0-9]{4}$')
  on conflict (fy) do nothing;

  loop
    update public.order_counters
       set last_serial = last_serial + 1, updated_at = now()
     where fy = v_fy and last_serial < 9999
    returning last_serial into v_serial;

    if v_serial is null then
      raise exception 'ORDER_LIMIT_REACHED: all 9999 order numbers for FY % are used',
        left(v_fy, 2) || '-' || right(v_fy, 2)
        using errcode = 'P0001';
    end if;

    v_no := v_fy || lpad(v_serial::text, 4, '0');
    exit when not exists (select 1 from public.orders where order_no = v_no);
  end loop;

  return v_no;
end;
$$;

revoke all on function public.ota_fy_key(date) from public, anon;
grant execute on function public.ota_fy_key(date) to authenticated;
revoke all on function public.ota_allocate_order_no(date) from public, anon;
grant execute on function public.ota_allocate_order_no(date) to authenticated;

-- Backstop: a financial-year number can exist only once. Same test as the apps: 8 digits where
-- the second pair of digits is the year after the first (2627…, 9900…).
create unique index if not exists orders_fy_order_no_key on public.orders (order_no)
  where order_no ~ '^[0-9]{8}$'
    and substr(order_no, 3, 2)::integer = (substr(order_no, 1, 2)::integer + 1) % 100;

notify pgrst, 'reload schema';
