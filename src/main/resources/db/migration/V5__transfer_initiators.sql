-- Who moved the money (ADR-0015): the user who owns the source account, or NULL for internal
-- transfers such as deposits. Idempotency keys become per initiator (ADR-0008).

-- Customer transfers stored before this migration have no recorded initiator, and guessing one
-- would rewrite history. Only development databases can be in this state.
DO
$$
BEGIN
    IF EXISTS (SELECT 1
               FROM transfers t
               JOIN accounts a ON a.id = t.source_account_id
               WHERE a.type = 'CUSTOMER') THEN
        RAISE EXCEPTION 'customer transfers without an initiator exist: reset the development '
            'database (docker compose down -v), see ADR-0015';
    END IF;
END;
$$;

ALTER TABLE transfers ADD COLUMN initiated_by UUID;

ALTER TABLE transfers
    ADD CONSTRAINT transfers_initiator_fk FOREIGN KEY (initiated_by) REFERENCES users (id),
    DROP CONSTRAINT transfers_idempotency_key_unique,
    -- One user cannot replay or block another's keys. NULLS NOT DISTINCT keeps internal keys
    -- (NULL initiator) unique among themselves. Losing the race on this constraint is still how a
    -- concurrent retry is detected, and its index serves the replay lookup.
    ADD CONSTRAINT transfers_initiator_idempotency_key_unique
        UNIQUE NULLS NOT DISTINCT (initiated_by, idempotency_key);

-- The application checks ownership before locking; this is the last line of defense if that check
-- is ever wrong. A CHECK constraint cannot read another table, so a trigger does it. owner_id never
-- changes (ADR-0015), so reading it without a lock is safe.
CREATE FUNCTION require_initiator_owns_source() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.initiated_by IS DISTINCT FROM
       (SELECT owner_id FROM accounts WHERE id = NEW.source_account_id) THEN
        RAISE EXCEPTION 'transfers_initiator_owns_source: the initiator must own the source account'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'transfers_initiator_owns_source';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transfers_initiator_owns_source
    BEFORE INSERT OR UPDATE ON transfers
    FOR EACH ROW EXECUTE FUNCTION require_initiator_owns_source();

-- No grant change: the application role has INSERT on transfers but never UPDATE (ADR-0012).
