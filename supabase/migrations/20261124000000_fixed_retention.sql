-- OTA Business: settled records leave the cloud a fixed 7 days after settling; no setting.
--
-- Run after 20261123000000_bill_gst.sql. Safe to run more than once.
--
-- The desktop keeps everything and the cloud keeps every open item however old, so the retention
-- days were only a short grace period before a settled, archived group is purged. It is now fixed
-- at 7 days. The column stays so older apps that still send it keep working: whatever they write,
-- the settings trigger keeps it at 7, and the purge (which reads it) always uses 7 days.
-- The test_ tables use the same purge and the same setting, so they follow.

begin;

alter table public.app_settings alter column retention_days set default 7;

create or replace function public.ota_settings_before_update()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.id := true;
  new.retention_days := 7;
  new.updated_at := clock_timestamp();
  new.updated_by := auth.uid();
  return new;
end;
$$;

update public.app_settings set retention_days = 7 where id and retention_days <> 7;

commit;

notify pgrst, 'reload schema';
