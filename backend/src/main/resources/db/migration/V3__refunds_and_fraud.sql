-- Refunds (compensating ledger transactions) and fraud screening.

CREATE TABLE refunds (
    id                    UUID PRIMARY KEY,
    payment_id            UUID          NOT NULL REFERENCES payments (id),
    amount                NUMERIC(19,2) NOT NULL,
    reason                VARCHAR(500),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_refunds_amount_positive CHECK (amount > 0)
);

CREATE INDEX ix_refunds_payment ON refunds (payment_id);

ALTER TABLE ledger_transactions
    ADD CONSTRAINT fk_ledger_tx_refund FOREIGN KEY (refund_id) REFERENCES refunds (id);

-- Fraud decision recorded on the payment at authorisation time.
ALTER TABLE payments
    ADD COLUMN fraud_score    INT,
    ADD COLUMN fraud_decision VARCHAR(20),
    ADD COLUMN fraud_rules    VARCHAR(500),
    ADD CONSTRAINT ck_payments_fraud_decision CHECK (fraud_decision IN ('APPROVED', 'REVIEW', 'DECLINED'));

-- Used by the NEW_BENEFICIARY rule.
CREATE INDEX ix_payments_pair ON payments (source_account_id, destination_account_id, status);

CREATE TABLE fraud_alerts (
    id              UUID PRIMARY KEY,
    payment_id      UUID         NOT NULL REFERENCES payments (id),
    risk_score      INT          NOT NULL,
    decision        VARCHAR(20)  NOT NULL,
    triggered_rules VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    reviewed_by     VARCHAR(100),
    review_note     VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_fraud_alerts_payment UNIQUE (payment_id),
    CONSTRAINT ck_fraud_alerts_decision CHECK (decision IN ('REVIEW', 'DECLINED')),
    CONSTRAINT ck_fraud_alerts_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'CLOSED'))
);

CREATE INDEX ix_fraud_alerts_status ON fraud_alerts (status, created_at DESC);
