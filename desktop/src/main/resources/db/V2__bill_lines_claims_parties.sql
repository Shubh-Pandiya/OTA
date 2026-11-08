-- v1.1: bill item lines and parties, claim / defect deduction on advice lines.
-- Same columns as supabase/migrations/20261110000000_bill_lines_claims_images.sql and
-- 20261114000000_bill_parties.sql (advice images stay on the phone and never reach the laptop).

alter table bills add column if not exists items    jsonb not null default '[]';
alter table bills add column if not exists buyer    text  not null default '';
alter table bills add column if not exists supplier text  not null default '';

alter table advice_lines add column if not exists claim      numeric(14, 2);
alter table advice_lines add column if not exists claim_note text not null default '';

-- Bills and advice lines pulled by v1.0 were stored without these fields. Read them all again:
-- forgetting the stored cloud version makes the next pull re-apply every row still in the cloud
-- (rows with unpushed edits keep theirs; rows already purged from the cloud keep what they have).
update bills set server_updated_at = null where not sync_pending;
update advice_lines set server_updated_at = null where not sync_pending;
delete from kv where key in ('cursor.bills', 'cursor.advice_lines');
