-- People who can log in (ADR-0013). Accounts get an owner in a later migration.

CREATE TABLE users (
    id            UUID         NOT NULL,
    -- Stored normalized (lowercase), so this plain UNIQUE makes usernames case-insensitive.
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_pk PRIMARY KEY (id),
    CONSTRAINT users_username_unique UNIQUE (username),
    CONSTRAINT users_username_format CHECK (username ~ '^[a-z0-9._-]{3,32}$'),
    CONSTRAINT users_role_valid CHECK (role IN ('CUSTOMER', 'ADMIN')),
    -- Encoded hashes name their algorithm ({argon2}...); a bare string here would be a bug,
    -- possibly a plaintext password.
    CONSTRAINT users_password_hash_encoded CHECK (password_hash ~ '^\{[a-z0-9]+\}.+$')
);

-- No UPDATE: the application cannot change a username, a hash or a role (ADR-0012).
GRANT SELECT, INSERT ON users TO "${app_role}";
