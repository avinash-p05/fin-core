-- FinCore schema: customers and accounts.
-- Monetary values use NUMERIC(19,2): INR with paise precision, never floating point.

CREATE TABLE customers (
    id          UUID PRIMARY KEY,
    full_name   VARCHAR(200) NOT NULL,
    email       VARCHAR(320) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_email UNIQUE (email)
);

CREATE TABLE accounts (
    id           UUID PRIMARY KEY,
    customer_id  UUID REFERENCES customers (id),
    account_type VARCHAR(20)   NOT NULL,
    display_name VARCHAR(200)  NOT NULL,
    currency     VARCHAR(3)    NOT NULL DEFAULT 'INR',
    balance      NUMERIC(19,2) NOT NULL DEFAULT 0,
    status       VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_accounts_type      CHECK (account_type IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT ck_accounts_status    CHECK (status IN ('ACTIVE', 'FROZEN')),
    CONSTRAINT ck_accounts_currency  CHECK (currency = 'INR'),
    -- Customer accounts can never be overdrawn, even if application code has a bug.
    CONSTRAINT ck_accounts_no_overdraft CHECK (account_type = 'SYSTEM' OR balance >= 0),
    CONSTRAINT ck_accounts_customer  CHECK (account_type = 'SYSTEM' OR customer_id IS NOT NULL)
);

CREATE INDEX ix_accounts_customer ON accounts (customer_id);

-- The system funding account is the counterparty for opening balances.
-- Its balance is allowed to go negative: it represents money injected into the simulation.
INSERT INTO accounts (id, customer_id, account_type, display_name, currency, balance, status)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM', 'System funding account', 'INR', 0, 'ACTIVE');
