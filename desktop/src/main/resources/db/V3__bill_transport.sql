-- v1.1.1: transport company on bills (same column as supabase/migrations/20261115000000_bill_transport.sql).
alter table bills add column if not exists transport text not null default '';

-- Re-read bills already pulled so their transport arrives (rows with unpushed edits keep theirs).
update bills set server_updated_at = null where not sync_pending;
delete from kv where key = 'cursor.bills';
