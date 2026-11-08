-- OTA Business desktop: local database (the system of record).
-- Mirrors the cloud tables in supabase/migrations plus a few local-only columns:
--   server_updated_at   the row's cloud updated_at exactly as pulled/pushed (text, compared verbatim)
--   archived_updated_at server_updated_at at the moment the row was marked archived in the cloud
--   sync_pending        changed here and not yet pushed
-- The desktop keeps every row it ever pulled, even links/lines whose parents it has not seen yet,
-- so there are deliberately no foreign keys between orders, bills, links, advices and lines.

create table if not exists kv (
  key   text primary key,
  value text not null
);

create table if not exists buyers (
  id                uuid primary key,
  name              text not null,
  address           text not null default '',
  city              text not null default '',
  phone             text not null default '',
  gstin             text not null default '',
  bank_account_name text not null default '',
  bank_name         text not null default '',
  bank_account_no   text not null default '',
  bank_ifsc         text not null default '',
  bank_branch       text not null default '',
  bank_upi          text not null default '',
  deleted           boolean not null default false,
  created_at        bigint not null default 0,
  client_updated_at bigint not null default 0,
  server_updated_at text,
  sync_pending      boolean not null default false
);
create table if not exists suppliers (like buyers including all);

create table if not exists orders (
  id                  uuid primary key,
  order_no            text not null,
  order_date          text not null default '',
  order_day           date,
  buyer               text not null default '',
  supplier            text not null default '',
  items               jsonb not null default '[]',
  remarks             text not null default '',
  delivery_ready      text not null default '',
  transport_to        text not null default '',
  cash_payment        text not null default '',
  less_disc           text not null default '',
  side                text not null default '',
  deleted             boolean not null default false,
  client_updated_at   bigint not null default 0,
  server_updated_at   text,
  archived_updated_at text,
  sync_pending        boolean not null default false,
  created_at          timestamptz not null default now()
);
create index if not exists orders_no_idx on orders (order_no);
create index if not exists orders_day_idx on orders (order_day);

create table if not exists bills (
  id                  uuid primary key,
  bill_no             text not null,
  bill_key            text not null default '',
  bill_date           date,
  amount              numeric(14, 2),
  lr_no               text not null default '',
  cd_percent          numeric(6, 3),
  cd_amount           numeric(14, 2),
  deleted             boolean not null default false,
  client_updated_at   bigint not null default 0,
  server_updated_at   text,
  archived_updated_at text,
  sync_pending        boolean not null default false
);
create index if not exists bills_key_idx on bills (bill_key);

create table if not exists order_bills (
  id                  uuid primary key,
  order_id            uuid not null,
  bill_id             uuid not null,
  deleted             boolean not null default false,
  client_updated_at   bigint not null default 0,
  server_updated_at   text,
  archived_updated_at text,
  sync_pending        boolean not null default false
);
create index if not exists order_bills_order_idx on order_bills (order_id);
create index if not exists order_bills_bill_idx on order_bills (bill_id);

create table if not exists payment_advices (
  id                  uuid primary key,
  advice_no           text not null,
  pay_date            date,
  buyer               text not null default '',
  supplier            text not null default '',
  mode                text not null default 'CASH',
  reference           text not null default '',
  balance             numeric(14, 2),
  balance_side        text,
  remarks             text not null default '',
  deleted             boolean not null default false,
  client_updated_at   bigint not null default 0,
  server_updated_at   text,
  archived_updated_at text,
  sync_pending        boolean not null default false
);
create index if not exists advices_no_idx on payment_advices (advice_no);

create table if not exists advice_lines (
  id                  uuid primary key,
  advice_id           uuid not null,
  bill_id             uuid not null,
  paid                numeric(14, 2) not null default 0,
  cd_percent          numeric(6, 3),
  cd_amount           numeric(14, 2),
  goods_return        numeric(14, 2),
  rate_diff           numeric(14, 2),
  deleted             boolean not null default false,
  client_updated_at   bigint not null default 0,
  server_updated_at   text,
  archived_updated_at text,
  sync_pending        boolean not null default false
);
create index if not exists advice_lines_advice_idx on advice_lines (advice_id);
create index if not exists advice_lines_bill_idx on advice_lines (bill_id);

-- Same as SearchText.partyKey on the phone: first line, lower case, single spaces.
create or replace function ota_party_key(t text) returns text language sql immutable as $$
  select lower(btrim(regexp_replace(split_part(coalesce(t, ''), E'\n', 1), '\s+', ' ', 'g')))
$$;

-- Same as SearchText.billKey: trimmed, single spaces, upper case.
create or replace function ota_bill_key(t text) returns text language sql immutable as $$
  select upper(btrim(regexp_replace(coalesce(t, ''), '\s+', ' ', 'g')))
$$;
