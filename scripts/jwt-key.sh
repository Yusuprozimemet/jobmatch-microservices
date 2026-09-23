#!/bin/sh
# Writes the RSA key identity signs tokens with, unless the file already holds one (Day 12).
#
# Compose runs this on every start, in the jwt-key service, against the jwt-keys volume: the key
# is made on the first start and kept until `docker compose down -v`. A new key on each start
# would sign every user out. By hand, for a backend started outside compose:
#
#   scripts/jwt-key.sh backend/.jwt/private.pem
#   export JWT_PRIVATE_KEY_FILE="$PWD/backend/.jwt/private.pem"
#
# The key is a 2048-bit RSA private key in a PKCS#8 PEM, which is what the backend reads. It is
# never committed: backend/.jwt/ is gitignored, and production's comes from its secret store.
set -eu

key="${1:?usage: jwt-key.sh <path of the private key to write>}"

if [ -s "$key" ]; then
    echo "jwt-key: keeping the key in $key"
    exit 0
fi

mkdir -p "$(dirname "$key")"
umask 077
openssl genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$key"
# The backend image runs as UID 1000. Only root can hand the file over, and by hand it is
# already the caller's, so a refused chown is not an error.
chown 1000 "$key" 2>/dev/null || true
echo "jwt-key: wrote a new key to $key"
