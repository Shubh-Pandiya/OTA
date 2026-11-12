-- v1.4.0: the agency's own double-entry books. Local only: never synced to the cloud and never
-- touched by archive/retention. Runs inside the migration transaction with every other step.

-- Chart of accounts. Buyers and suppliers each get a party account (party_type + party_key, the
-- normalised first line of the name). opening_balance is signed: positive = Dr, negative = Cr.
create table if not exists acc_accounts (
    id uuid primary key,
    code text not null default '',
    name text not null,
    grp text not null check (grp in ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    subgroup text not null default '',
    cash_bank boolean not null default false,
    party_type text check (party_type in ('BUYER', 'SUPPLIER')),
    party_key text,
    commission_percent numeric(6, 3) not null default 0 check (commission_percent >= 0 and commission_percent <= 100),
    opening_balance numeric(14, 2) not null default 0,
    system_key text unique,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    check ((party_type is null) = (party_key is null))
);
create unique index if not exists acc_accounts_party_key on acc_accounts (party_type, party_key) where party_type is not null;
create unique index if not exists acc_accounts_name_key on acc_accounts (lower(name)) where party_type is null;

-- Vouchers. A commission voucher points at its bill: the unique index keeps it to one per bill, ever
-- (a deleted bill soft-deletes its voucher; restoring the bill revives the same one).
create table if not exists acc_vouchers (
    id uuid primary key,
    voucher_no text not null,
    vtype text not null check (vtype in ('JOURNAL', 'PAYMENT', 'RECEIPT', 'CONTRA', 'COMMISSION')),
    vdate date not null,
    narration text not null default '',
    source_bill_id uuid,
    deleted boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create unique index if not exists acc_vouchers_no_key on acc_vouchers (voucher_no);
create unique index if not exists acc_vouchers_bill_key on acc_vouchers (source_bill_id) where source_bill_id is not null;
create index if not exists acc_vouchers_date_idx on acc_vouchers (vdate) where not deleted;

create table if not exists acc_voucher_lines (
    id uuid primary key,
    voucher_id uuid not null references acc_vouchers (id) on delete cascade,
    line_no integer not null,
    account_id uuid not null references acc_accounts (id),
    dr numeric(14, 2) not null default 0 check (dr >= 0),
    cr numeric(14, 2) not null default 0 check (cr >= 0),
    note text not null default '',
    check ((dr > 0) <> (cr > 0)),
    unique (voucher_id, line_no)
);
create index if not exists acc_voucher_lines_account_idx on acc_voucher_lines (account_id);

-- Every create, edit and delete of a voucher, with the voucher as it was and as it became.
create table if not exists acc_audit (
    id bigserial primary key,
    at timestamptz not null default now(),
    action text not null,
    voucher_id uuid,
    voucher_no text not null default '',
    before jsonb,
    after jsonb
);
create index if not exists acc_audit_voucher_idx on acc_audit (voucher_id);

-- A sensible default chart; every account can be renamed, re-coded or deactivated later.
insert into acc_accounts (id, code, name, grp, subgroup, cash_bank, system_key)
select md5('ota-acc:' || k)::uuid, code, name, grp, sub, cb, k
from (values
    ('cash',        '1001', 'Cash in hand',               'ASSET',     'Cash & bank',          true),
    ('bank',        '1002', 'Bank account',               'ASSET',     'Cash & bank',          true),
    ('furniture',   '1101', 'Furniture & equipment',      'ASSET',     'Fixed assets',         false),
    ('advances',    '1201', 'Advances & deposits',        'ASSET',     'Current assets',       false),
    ('creditors',   '2001', 'Sundry creditors',           'LIABILITY', 'Current liabilities',  false),
    ('taxes',       '2002', 'Duties & taxes',             'LIABILITY', 'Current liabilities',  false),
    ('loans',       '2101', 'Loans',                      'LIABILITY', 'Loans',                false),
    ('capital',     '3001', 'Capital account',            'EQUITY',    'Capital',              false),
    ('drawings',    '3002', 'Drawings',                   'EQUITY',    'Capital',              false),
    ('commission',  '4001', 'Commission income',          'INCOME',    'Direct income',        false),
    ('interest',    '4101', 'Interest received',          'INCOME',    'Indirect income',      false),
    ('other_income','4102', 'Other income',               'INCOME',    'Indirect income',      false),
    ('salaries',    '5001', 'Salaries',                   'EXPENSE',   'Indirect expenses',    false),
    ('rent',        '5002', 'Rent',                       'EXPENSE',   'Indirect expenses',    false),
    ('travel',      '5003', 'Travel & conveyance',        'EXPENSE',   'Indirect expenses',    false),
    ('telephone',   '5004', 'Telephone & internet',       'EXPENSE',   'Indirect expenses',    false),
    ('printing',    '5005', 'Printing & stationery',      'EXPENSE',   'Indirect expenses',    false),
    ('electricity', '5006', 'Electricity',                'EXPENSE',   'Indirect expenses',    false),
    ('bank_charges','5007', 'Bank charges',               'EXPENSE',   'Indirect expenses',    false),
    ('office',      '5008', 'Office expenses',            'EXPENSE',   'Indirect expenses',    false)
) as d (k, code, name, grp, sub, cb)
on conflict do nothing;
