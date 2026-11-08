-- OTA Business: agency-wide order numbers per financial year.
--
-- New orders are numbered {FY start YY}-{FY end YY}{4-digit serial}, e.g. 26-270001 is the first
-- order of FY 2026-27 (1 April 2026 – 31 March 2027). The serial restarts at 0001 every 1 April and
-- stops at 9999. One counter serves every phone and the desktop: the app calls
-- ota_allocate_order_no when it saves an order; offline it saves the order with a pending number
-- and calls this on the next sync.
--
-- Older orders keep their numbers (e.g. 120261107001); nothing here changes them.
-- Safe to run more than once.

-- ---------------------------------------------------------------- counters
-- One row per financial year ('26-27'). Never purged; apps can read it but only the function
-- below changes it.
create table if not exists public.order_counters (
  fy          text primary key check (fy ~ '^[0-9]{2}-[0-9]{2}$'),
  last_serial integer not null default 0 check (last_serial between 0 and 9999),
  updated_at  timestamptz not null default now()
);

alter table public.order_counters enable row level security;

drop policy if exists "staff can read order counters" on public.order_counters;
create policy "staff can read order counters" on public.order_counters
  for select to authenticated using ((select public.ota_is_staff()));

revoke all on public.order_counters from anon;
revoke insert, update, delete, truncate on public.order_counters from authenticated;
grant select on public.order_counters to authenticated;

-- '26-27' for any date from 1 Apr 2026 to 31 Mar 2027.
create or replace function public.ota_fy_key(p_date date)
returns text
language sql
immutable
set search_path = ''
as $$
  select to_char(y % 100, 'FM00') || '-' || to_char((y + 1) % 100, 'FM00')
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
      raise exception 'ORDER_LIMIT_REACHED: all 9999 order numbers for FY % are used', v_fy
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

-- Backstop: a financial-year number can exist only once.
create unique index if not exists orders_fy_order_no_key on public.orders (order_no)
  where order_no ~ '^[0-9]{2}-[0-9]{6}$';

notify pgrst, 'reload schema';
