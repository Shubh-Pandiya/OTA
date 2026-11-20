-- v1.12.0: GST % included in a bill's amount (same column as supabase/migrations/20261123000000_bill_gst.sql).
-- Null = entered before GST was recorded: its amount stays the commission base, as before.
alter table bills add column if not exists gst_percent numeric(5,2);
