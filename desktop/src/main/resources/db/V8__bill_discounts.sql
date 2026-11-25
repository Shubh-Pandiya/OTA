-- v1.15.0: discount lines taken off a bill's items total before GST
-- (same column as supabase/migrations/20261125000000_bill_discounts.sql). Null = none.
alter table bills add column if not exists discounts jsonb;
