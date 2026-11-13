-- Hard deletes: nothing is kept with deleted = true any more (docs/desktop-sync-contract.md).
-- Deletes made on this laptop wait in pending_deletions until the cloud has them.
create table if not exists pending_deletions (
  id         uuid primary key,
  tbl        text not null,
  deleted_at bigint not null
);

-- Soft deletes this laptop made but never uploaded are uploaded as hard deletes. Orders and advices
-- still waiting for their number never reached the cloud, so there is nothing to tell it.
insert into pending_deletions (id, tbl, deleted_at)
select id, 'buyers', client_updated_at from buyers where deleted and sync_pending
union all select id, 'suppliers', client_updated_at from suppliers where deleted and sync_pending
union all select id, 'orders', client_updated_at from orders where deleted and sync_pending and order_no <> ''
union all select id, 'bills', client_updated_at from bills where deleted and sync_pending
union all select id, 'order_bills', client_updated_at from order_bills where deleted and sync_pending
union all select id, 'payment_advices', client_updated_at from payment_advices where deleted and sync_pending and advice_no <> ''
union all select id, 'advice_lines', client_updated_at from advice_lines l where deleted and sync_pending
  and exists (select 1 from payment_advices a where a.id = l.advice_id and a.advice_no <> '')
on conflict (id) do nothing;

-- Links and lines of deleted rows go with them, then the deleted rows themselves.
delete from order_bills l where l.deleted
  or exists (select 1 from orders o where o.id = l.order_id and o.deleted)
  or exists (select 1 from bills b where b.id = l.bill_id and b.deleted);
delete from advice_lines l where l.deleted
  or exists (select 1 from payment_advices a where a.id = l.advice_id and a.deleted)
  or exists (select 1 from bills b where b.id = l.bill_id and b.deleted);
delete from payment_advices where deleted;
delete from bills where deleted;
delete from orders where deleted;
delete from buyers where deleted;
delete from suppliers where deleted;

-- Accounts: a deleted voucher goes completely; its audit history shrinks to one line saying who
-- deleted which voucher number and when.
alter table acc_audit add column if not exists by_user text not null default '';
delete from acc_audit where voucher_id in (select id from acc_vouchers where deleted) and action not in ('deleted', 'auto-removed');
update acc_audit set before = null, after = null where voucher_id in (select id from acc_vouchers where deleted);
delete from acc_vouchers where deleted;
