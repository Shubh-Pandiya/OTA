-- v1.15.1: a remark on the whole bill that stays on this laptop. It is not a cloud column: sync
-- never sends it, and a pulled bill only updates the synced columns, so the remark survives. Null = none.
alter table bills add column if not exists local_remark text;
