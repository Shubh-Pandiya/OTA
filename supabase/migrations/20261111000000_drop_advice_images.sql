-- OTA Business: advice images stay on the phone that attached them (they are only used to
-- build the advice PDF), so the cloud no longer stores or lists them. This removes what
-- 20261110000000_bill_lines_claims_images.sql added for images. Bill item lines and the
-- claim columns from that file stay.
--
-- Run once in the Supabase dashboard: SQL Editor > New query > paste this file > Run.
-- Safe to re-run.

-- ---------------------------------------------------------------- storage policies
drop policy if exists "staff can read advice images" on storage.objects;
drop policy if exists "staff can add advice images" on storage.objects;
drop policy if exists "staff can replace advice images" on storage.objects;
drop policy if exists "staff can remove advice images" on storage.objects;

-- ---------------------------------------------------------------- storage bucket
-- Nothing was ever uploaded by the app, so the bucket is empty. Newer Supabase projects
-- block direct deletes from storage tables; if that happens a notice is printed and the
-- empty bucket can be removed from Storage > advice-images > Delete bucket (harmless if left).
do $$
begin
  delete from storage.objects where bucket_id = 'advice-images';
  delete from storage.buckets where id = 'advice-images';
exception when others then
  raise notice 'advice-images bucket not removed (%). Delete it from the Storage page if you like.', sqlerrm;
end
$$;

-- ---------------------------------------------------------------- advice image ids
alter table public.payment_advices drop column if exists images;

-- ---------------------------------------------------------------- cloud status
-- Back to the database-only figures.
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
    'percent', round(100.0 * pg_database_size(current_database()) / (s.storage_limit_mb * 1048576.0), 1)
  )
  from public.app_settings s
  where s.id and public.ota_is_staff()
$$;

revoke all on function public.ota_cloud_status() from public, anon;
grant execute on function public.ota_cloud_status() to authenticated;

notify pgrst, 'reload schema';
