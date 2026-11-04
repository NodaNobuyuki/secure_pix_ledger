-- Every customer account belongs to exactly one user; internal accounts belong to no one
-- (ADR-0015).

-- Accounts opened before ownership existed have no one to belong to, and inventing an owner would
-- hand someone money that was never theirs. Only development databases can be in this state.
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM accounts WHERE type = 'CUSTOMER') THEN
        RAISE EXCEPTION 'customer accounts without an owner exist: reset the development database '
            '(docker compose down -v), see ADR-0015';
    END IF;
END;
$$;

ALTER TABLE accounts ADD COLUMN owner_id UUID;

ALTER TABLE accounts
    ADD CONSTRAINT accounts_owner_fk FOREIGN KEY (owner_id) REFERENCES users (id),
    ADD CONSTRAINT accounts_owner_iff_customer CHECK ((type = 'SYSTEM') = (owner_id IS NULL));

-- GET /accounts lists by owner; PostgreSQL does not index foreign keys on its own.
CREATE INDEX accounts_owner_idx ON accounts (owner_id);

-- No grant change: V2 granted UPDATE on balance_minor and status only, so the application can
-- never move an account to another owner, and reading the owner without a lock is safe.
