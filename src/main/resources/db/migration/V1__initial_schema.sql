-- Initial ledger schema (ADR-0006, ADR-0008, ADR-0009).
-- The domain model enforces the same rules; these constraints are the last line of defense if
-- application code is wrong. Constraint names are explicit so violations say which rule failed.

CREATE TABLE accounts (
    id            UUID        NOT NULL,
    type          VARCHAR(16) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    currency      CHAR(3)     NOT NULL,
    balance_minor BIGINT      NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT accounts_pk PRIMARY KEY (id),
    CONSTRAINT accounts_type_valid CHECK (type IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT accounts_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT accounts_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
    -- Overdraft is impossible for customers even if the application has a bug.
    CONSTRAINT accounts_customer_balance_non_negative CHECK (type = 'SYSTEM' OR balance_minor >= 0)
);

CREATE TABLE transfers (
    id                     UUID        NOT NULL,
    idempotency_key        UUID        NOT NULL,
    -- Hex SHA-256 of the canonical request (ADR-0008).
    request_hash           CHAR(64)    NOT NULL,
    source_account_id      UUID        NOT NULL,
    destination_account_id UUID        NOT NULL,
    amount_minor           BIGINT      NOT NULL,
    currency               CHAR(3)     NOT NULL,
    status                 VARCHAR(16) NOT NULL,
    rejection_reason       VARCHAR(32),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transfers_pk PRIMARY KEY (id),
    -- Losing the race on this constraint is how a concurrent retry is detected (ADR-0008).
    CONSTRAINT transfers_idempotency_key_unique UNIQUE (idempotency_key),
    CONSTRAINT transfers_source_fk FOREIGN KEY (source_account_id) REFERENCES accounts (id),
    CONSTRAINT transfers_destination_fk FOREIGN KEY (destination_account_id) REFERENCES accounts (id),
    CONSTRAINT transfers_request_hash_hex CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT transfers_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT transfers_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT transfers_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT transfers_status_valid CHECK (status IN ('COMPLETED', 'REJECTED')),
    CONSTRAINT transfers_rejection_reason_valid
        CHECK (rejection_reason IN ('INSUFFICIENT_FUNDS', 'SOURCE_INACTIVE', 'DESTINATION_INACTIVE')),
    -- Mirrors the Transfer constructor: a reason exactly when the transfer was rejected.
    CONSTRAINT transfers_rejection_reason_iff_rejected
        CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL))
);

CREATE TABLE ledger_entries (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY,
    transfer_id  UUID        NOT NULL,
    account_id   UUID        NOT NULL,
    -- Signed: negative for the debit, positive for the credit. Currency is the account's.
    amount_minor BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_pk PRIMARY KEY (id),
    CONSTRAINT ledger_entries_transfer_fk FOREIGN KEY (transfer_id) REFERENCES transfers (id),
    CONSTRAINT ledger_entries_account_fk FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ledger_entries_amount_non_zero CHECK (amount_minor <> 0)
);

-- PostgreSQL does not index foreign keys on its own; both are used to sum and list entries.
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id);
CREATE INDEX ledger_entries_transfer_idx ON ledger_entries (transfer_id);

-- Append-only ledger: corrections are new transfers, never edits (ADR-0006).
CREATE FUNCTION reject_ledger_entry_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER ledger_entries_no_update_or_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_entry_change();

-- TRUNCATE skips row-level triggers, so it needs its own statement-level one.
CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_entry_change();

-- The only source of money (ADR-0006). Its id is fixed so code can reference it directly
-- (SystemAccounts.FUNDING_ID); it starts at zero like every other account.
INSERT INTO accounts (id, type, status, currency, balance_minor)
VALUES ('00000000-0000-0000-0000-000000000001', 'SYSTEM', 'ACTIVE', 'BRL', 0);
