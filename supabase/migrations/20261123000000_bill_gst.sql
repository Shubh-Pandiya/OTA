-- OTA Business: GST on bills.
--
-- Run after 20261122000000_keep_open_items.sql. Safe to run more than once.
--
-- The bill amount is the GST-inclusive total: item total + GST % of it. The agency-wide GST %
-- (default 5) lives in app_settings, only an admin may change it, and every staff login reads it
-- through ota_cloud_status. Each bill keeps the GST % it was entered with (bills.gst_percent), so a
-- later rate change never alters old bills; commission is worked out on the value before GST.
-- Null = a bill entered before this change: its amount is used as it is, as before.

alter table public.bills add column if not exists gst_percent numeric(5,2)
  check (gst_percent between 0 and 100);
alter table public.test_bills add column if not exists gst_percent numeric(5,2)
  check (gst_percent between 0 and 100);

alter table public.app_settings add column if not exists gst_percent numeric(5,2) not null default 5
  check (gst_percent between 0 and 100);

grant update (retention_days, storage_limit_mb, warn_percent, gst_percent) on public.app_settings to authenticated;

-- ---------------------------------------------------------------- cloud status
-- Same figures as before plus the GST %, so phones pick it up with the check they already make.
create or replace function public.ota_cloud_status()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'used_bytes', pg_database_size(current_database()),
    'limit_mb', s.storage_limit_mb,
    'warn_percent', s.warn_percent,
    'retention_days', s.retention_days,
    'gst_percent', s.gst_percent,
    'percent', round(100.0 * pg_database_size(current_database()) / (s.storage_limit_mb * 1048576.0), 1)
  )
  from public.app_settings s
  where s.id and public.ota_is_staff()
$$;

revoke all on function public.ota_cloud_status() from public, anon;
grant execute on function public.ota_cloud_status() to authenticated;

notify pgrst, 'reload schema';
