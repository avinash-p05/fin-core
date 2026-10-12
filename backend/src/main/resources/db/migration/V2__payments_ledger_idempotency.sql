-- Payments, double-entry ledger and durable idempotency.

CREATE TABLE payments (
    id                     UUID PRIMARY KEY,
    source_account_id      UUID          NOT NULL REFERENCES accounts (id),
    destination_account_id UUID          NOT NULL REFERENCES accounts (id),
    amount                 NUMERIC(19,2) NOT NULL,
    currency               VARCHAR(3)    NOT NULL DEFAULT 'INR',
    description            VARCHAR(500),
    status                 VARCHAR(30)   NOT NULL,
    failure_reason         VARCHAR(50),
    refunded_amount        NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    version                BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payments_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT ck_payments_currency CHECK (currency = 'INR'),
    -- Refunds can never exceed the original payment amount.
    CONSTRAINT ck_payments_refund_bounds CHECK (refunded_amount >= 0 AND refunded_amount <= amount),
    CONSTRAINT ck_payments_status CHECK (status IN
        ('PENDING_REVIEW', 'COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'DECLINED', 'FAILED'))
);

CREATE INDEX ix_payments_source ON payments (source_account_id, created_at DESC);
CREATE INDEX ix_payments_destination ON payments (destination_account_id, created_at DESC);
CREATE INDEX ix_payments_status ON payments (status);
CREATE INDEX ix_payments_created ON payments (created_at DESC);

-- One row per client-supplied Idempotency-Key. The primary key makes concurrent duplicates
-- serialise: the second INSERT waits for the first transaction and then sees the conflict.
CREATE TABLE idempotency_keys (
    idempotency_key     VARCHAR(100) PRIMARY KEY,
    scope               VARCHAR(20)  NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    resource_id         UUID,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Accounting convention (see docs/architecture.md):
--   every account's balance = SUM(credits) - SUM(debits).
--   A transfer DEBITs the payer and CREDITs the payee.
CREATE TABLE ledger_transactions (
    id               UUID PRIMARY KEY,
    transaction_type VARCHAR(30)  NOT NULL,
    payment_id       UUID REFERENCES payments (id),
    refund_id        UUID,
    description      VARCHAR(500),
    posted_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_tx_type CHECK (transaction_type IN ('OPENING_BALANCE', 'TRANSFER', 'REFUND'))
);

CREATE INDEX ix_ledger_tx_payment ON ledger_transactions (payment_id);

CREATE TABLE ledger_entries (
    id                    UUID PRIMARY KEY,
    ledger_transaction_id UUID          NOT NULL REFERENCES ledger_transactions (id),
    account_id            UUID          NOT NULL REFERENCES accounts (id),
    direction             VARCHAR(6)    NOT NULL,
    amount                NUMERIC(19,2) NOT NULL,
    currency              VARCHAR(3)    NOT NULL DEFAULT 'INR',
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entry_amount CHECK (amount > 0),
    CONSTRAINT ck_ledger_entry_currency CHECK (currency = 'INR')
);

CREATE INDEX ix_ledger_entries_account ON ledger_entries (account_id, created_at DESC);
CREATE INDEX ix_ledger_entries_tx ON ledger_entries (ledger_transaction_id);

-- Posted ledger rows are append-only. Corrections are made with new compensating transactions.
CREATE FUNCTION fincore_reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger rows are immutable: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION fincore_reject_ledger_mutation();

CREATE TRIGGER trg_ledger_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION fincore_reject_ledger_mutation();

-- Double-entry invariant checked by the database at COMMIT time (deferred constraint trigger):
-- every ledger transaction must have at least two entries and SUM(debits) = SUM(credits).
CREATE FUNCTION fincore_check_ledger_balanced() RETURNS trigger AS $$
DECLARE
    debits  NUMERIC(19,2);
    credits NUMERIC(19,2);
    entries INT;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*)
      INTO debits, credits, entries
      FROM ledger_entries
     WHERE ledger_transaction_id = NEW.ledger_transaction_id;

    IF entries < 2 OR debits <> credits THEN
        RAISE EXCEPTION 'unbalanced ledger transaction %: debits=% credits=% entries=%',
            NEW.ledger_transaction_id, debits, credits, entries
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fincore_check_ledger_balanced();
