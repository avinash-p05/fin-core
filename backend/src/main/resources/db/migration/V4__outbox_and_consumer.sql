-- Transactional outbox (producer side) and idempotent consumer bookkeeping.

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- The publisher only scans due PENDING rows.
CREATE INDEX ix_outbox_pending ON outbox_events (next_attempt_at, created_at) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_aggregate ON outbox_events (aggregate_id);

-- Consumer de-duplication: one row per (consumer, event). Inserted in the same transaction as the
-- consumer's business effect, so a redelivered event is detected and skipped.
CREATE TABLE processed_events (
    consumer     VARCHAR(100) NOT NULL,
    event_id     UUID         NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- The downstream business effect: customer-facing notifications per affected account.
CREATE TABLE payment_notifications (
    id                UUID PRIMARY KEY,
    event_id          UUID          NOT NULL,
    account_id        UUID          NOT NULL REFERENCES accounts (id),
    payment_id        UUID          NOT NULL,
    notification_type VARCHAR(30)   NOT NULL,
    amount            NUMERIC(19,2) NOT NULL,
    message           VARCHAR(500)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_event_account UNIQUE (event_id, account_id)
);

CREATE INDEX ix_notifications_account ON payment_notifications (account_id, created_at DESC);
