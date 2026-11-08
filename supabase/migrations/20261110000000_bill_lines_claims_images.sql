-- OTA Business: bill item lines, claim (defect) deductions on payment advice lines, and
-- cheque / transaction images attached to payment advices.
--
-- Run once in the Supabase dashboard: SQL Editor > New query > paste this file > Run.
-- Needs the three earlier files in supabase/migrations first. Safe to re-run.
--
-- The image parts (bucket, policies, payment_advices.images, image figures in
-- ota_cloud_status) are undone by 20261111000000_drop_advice_images.sql: advice images now
-- stay on the phone. Run that file after this one.
--
-- Rows stay compact: bill lines live inside bills.items (jsonb, same arrays as order items),
-- an advice only lists the ids of its images, and the images themselves go to a private
-- Storage bucket (Storage has its own quota, separate from the 500 MB database).

-- ---------------------------------------------------------------- bill item lines
-- What the supplier actually billed, which can differ from the orders (24 m ordered, 25 m sent).
-- [s.no, particulars, qty, rate] plus the unit code (M/T/P/S/B) when set; null = no lines.
alter table public.bills add column if not exists items jsonb;

-- ---------------------------------------------------------------- claim / defect deduction
-- Amount the buyer cut from a bill for defective goods, with the reason. Counts like the
-- cash discount, goods return and rate difference in the bill's balance.
alter table public.advice_lines add column if not exists claim numeric(14, 2);
alter table public.advice_lines add column if not exists claim_note text not null default '';

-- ---------------------------------------------------------------- advice images
-- Ids of the advice's images in page order, e.g. ["5f0c…", "9a1e…"]; null = none.
-- Each image is advice-images/<advice id>/<image id>.jpg in Storage.
alter table public.payment_advices add column if not exists images jsonb;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('advice-images', 'advice-images', false, 1048576, array['image/jpeg'])
on conflict (id) do update
  set public = false, file_size_limit = 1048576, allowed_mime_types = array['image/jpeg'];

drop policy if exists "staff can read advice images" on storage.objects;
create policy "staff can read advice images" on storage.objects
  for select to authenticated
  using (bucket_id = 'advice-images' and (select public.ota_is_staff()));

drop policy if exists "staff can add advice images" on storage.objects;
create policy "staff can add advice images" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'advice-images' and (select public.ota_is_staff()));

-- Upserts (re-sending an image after a dropped connection) need update as well.
drop policy if exists "staff can replace advice images" on storage.objects;
create policy "staff can replace advice images" on storage.objects
  for update to authenticated
  using (bucket_id = 'advice-images' and (select public.ota_is_staff()))
  with check (bucket_id = 'advice-images' and (select public.ota_is_staff()));

-- Phones remove an image taken off an advice; the desktop removes images once it has copies.
drop policy if exists "staff can remove advice images" on storage.objects;
create policy "staff can remove advice images" on storage.objects
  for delete to authenticated
  using (bucket_id = 'advice-images' and (select public.ota_is_staff()));

-- ---------------------------------------------------------------- cloud status
-- As before, plus the bytes used by advice images (Storage quota, shown on the desktop).
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
    'percent', round(100.0 * pg_database_size(current_database()) / (s.storage_limit_mb * 1048576.0), 1),
    'image_bytes', (select coalesce(sum((o.metadata ->> 'size')::bigint), 0)
                      from storage.objects o where o.bucket_id = 'advice-images'),
    'image_count', (select count(*) from storage.objects o where o.bucket_id = 'advice-images')
  )
  from public.app_settings s
  where s.id and public.ota_is_staff()
$$;

revoke all on function public.ota_cloud_status() from public, anon;
grant execute on function public.ota_cloud_status() to authenticated;

-- Make PostgREST see the new columns straight away.
notify pgrst, 'reload schema';
