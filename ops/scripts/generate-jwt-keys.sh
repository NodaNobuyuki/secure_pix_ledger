#!/bin/sh
# Generates the RSA key pair that signs access tokens (ADR-0014) into secrets/, which git and the
# Docker build context both ignore. Run it once before `docker compose up`; tests make their own.
#
# It refuses to overwrite an existing pair: a new key invalidates every token already issued, so
# rotating is a deliberate act (delete the files first).
set -eu

dir="${1:-$(cd "$(dirname "$0")/../.." && pwd)/secrets}"
private="$dir/jwt-private.pem"
public="$dir/jwt-public.pem"

if [ -e "$private" ] || [ -e "$public" ]; then
  echo "$dir already holds a JWT key pair; delete it first to rotate the key." >&2
  exit 1
fi

# Only you may enter the directory, which is what keeps the keys private on this machine.
mkdir -p "$dir"
chmod 700 "$dir"

umask 077
openssl genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$private"
openssl pkey -in "$private" -pubout -out "$public"

# Compose mounts the files themselves, and the app reads them as its own unprivileged user, whose
# uid differs from yours. A 0600 file would be unreadable there; the 0700 directory protects them.
chmod 644 "$private" "$public"

echo "Wrote $private and $public"
