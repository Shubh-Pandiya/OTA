-- OTA Business: agency-wide payment advice numbers per financial year.
--
-- Run after 20261113000000_fy_order_numbers_no_dash.sql. New payment advices are numbered like
-- orders, from their own counter: {FY start YY}{FY end YY}{4-digit serial}, e.g. 26270001 is the
-- first advice of FY 2026-27 (1 April 2026 – 31 March 2027). The serial restarts at 0001 every
-- 1 April and stops at 9999. The apps show and print it as "PA-26270001".
--
-- The apps call ota_allocate_advice_no when they save a new advice; offline they save it as
-- "Pending number" and call this on the next sync, before the advice is uploaded.
--
-- Existing advices keep their numbers (e.g. PA120261108001); nothing here changes them.
-- Safe to run more than once.

-- ---------------------------------------------------------------- counters
-- One row per financial year ('2627'). Never purged; apps can read it but only the function
-- below changes it.
create table if not exists public.advice_counters (
  fy          text primary key check (fy ~ '^[0-9]{4}$'),
  last_serial integer not null default 0 check (last_serial between 0 and 9999),
  updated_at  timestamptz not null default now()
);

alter table public.advice_counters enable row level security;

drop policy if exists "staff can read advice counters" on public.advice_counters;
create policy "staff can read advice counters" on public.advice_counters
  for select to authenticated using ((select public.ota_is_staff()));

revoke all on public.advice_counters from anon;
revoke insert, update, delete, truncate on public.advice_counters from authenticated;
grant select on public.advice_counters to authenticated;

-- ---------------------------------------------------------------- allocation
-- Hands out the next advice number for the financial year of p_date (default: today in India).
-- The counter row is locked by the UPDATE, so concurrent callers each get a different number.
-- A number already used by an advice (e.g. restored) is skipped.
create or replace function public.ota_allocate_advice_no(p_date date default null)
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
    raise exception 'Only signed-in staff can number payment advices' using errcode = '42501';
  end if;

  -- First advice of the year: start after any advice already numbered in this year's format.
  insert into public.advice_counters (fy, last_serial)
  select v_fy, coalesce(max(right(advice_no, 4)::integer), 0)
  from public.payment_advices
  where advice_no ~ ('^' || v_fy || '[0-9]{4}$')
  on conflict (fy) do nothing;

  loop
    update public.advice_counters
       set last_serial = last_serial + 1, updated_at = now()
     where fy = v_fy and last_serial < 9999
    returning last_serial into v_serial;

    if v_serial is null then
      raise exception 'ADVICE_LIMIT_REACHED: all 9999 payment advice numbers for FY % are used',
        left(v_fy, 2) || '-' || right(v_fy, 2)
        using errcode = 'P0001';
    end if;

    v_no := v_fy || lpad(v_serial::text, 4, '0');
    exit when not exists (select 1 from public.payment_advices where advice_no = v_no);
  end loop;

  return v_no;
end;
$$;

revoke all on function public.ota_allocate_advice_no(date) from public, anon;
grant execute on function public.ota_allocate_advice_no(date) to authenticated;

-- Backstop: a financial-year advice number can exist only once. Same test as the apps: 8 digits
-- where the second pair of digits is the year after the first (2627…, 9900…).
create unique index if not exists payment_advices_fy_advice_no_key on public.payment_advices (advice_no)
  where advice_no ~ '^[0-9]{8}$'
    and substr(advice_no, 3, 2)::integer = (substr(advice_no, 1, 2)::integer + 1) % 100;

notify pgrst, 'reload schema';
