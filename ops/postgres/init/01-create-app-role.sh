#!/bin/sh
# Creates the role the application connects as (ADR-0012). It owns nothing, so it cannot change
# the schema or disable the ledger triggers; migrations grant it row access table by table.
#
# The postgres image runs this once, when the data volume is first initialized. psql quotes the
# variables (:"name" as an identifier, :'name' as a literal), so the values cannot inject SQL.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_user="$APP_DB_USER" -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
  PASSWORD :'app_password';
SQL
