-- OTA Business: transport company on bills.
--
-- Run after 20261114000000_bill_parties.sql. Safe to run more than once.
--
-- The supplier sometimes sends goods through a different transport than the one on the order,
-- so the bill records the transport company it actually came by (next to the LR / bilty no).
-- Nullable text: null = not entered, so existing rows stay as they are and stay small.

alter table public.bills add column if not exists transport text;

notify pgrst, 'reload schema';
