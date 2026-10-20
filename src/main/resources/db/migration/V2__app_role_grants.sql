-- Row access for the application role (ADR-0012). The role is provisioned outside migrations and
-- named through the app_role Flyway placeholder. It owns nothing, so it cannot change the schema
-- or disable the ledger triggers, and there is no DELETE or TRUNCATE: history is append-only.

GRANT SELECT, INSERT ON accounts, transfers, ledger_entries TO "${app_role}";

-- Hibernate writes every updatable column of an entity on update, so both are granted; type,
-- currency and created_at are mapped updatable = false and stay read-only (an account can never
-- become SYSTEM). Row locks (FOR UPDATE) also need this grant.
GRANT UPDATE (balance_minor, status) ON accounts TO "${app_role}";
