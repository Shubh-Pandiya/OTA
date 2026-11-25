-- OTA Business: discount lines on bills, taken off the items total before GST.
--
-- Run after 20261124000000_fixed_retention.sql. Safe to run more than once.
--
-- A bill's amount is now (items total - discounts) + GST % of that. Each discount line is a cash
-- discount or another discount with a remark, as a % of the items total and/or rupees, kept
-- compactly inside the bill row, e.g.
--   [{"k":"cd","p":2,"a":200},{"k":"o","a":150,"r":"Festival offer"}]
-- Null = no discount lines. Bills entered earlier keep cd_percent / cd_amount (a cash discount
-- taken at payment); newer apps leave those empty when a bill has discount lines, so nothing is
-- discounted twice. Older apps keep working: they don't send the column, so it is left alone.

alter table public.bills add column if not exists discounts jsonb
  check (discounts is null or (jsonb_typeof(discounts) = 'array' and jsonb_array_length(discounts) <= 20));
alter table public.test_bills add column if not exists discounts jsonb
  check (discounts is null or (jsonb_typeof(discounts) = 'array' and jsonb_array_length(discounts) <= 20));

notify pgrst, 'reload schema';
