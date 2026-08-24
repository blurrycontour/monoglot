#!/usr/bin/env bash
# Development convenience: checkout -> running instance.
#
# The heavy lifting is no longer here. The API imports the dictionary and word
# forms itself on first start and then fetches the first episodes, so a
# production host needs only docker-compose.yml and a .env:
#
#   docker compose up -d
#
# This script exists for a checkout: it seeds .env with a real token, builds
# the images from source, and waits for the server to answer.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

say() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

if [ ! -f .env ]; then
  say "Creating .env with a fresh auth token"
  cp .env.example .env
  if command -v openssl >/dev/null; then
    sed -i "s|^AUTH_TOKEN=.*|AUTH_TOKEN=$(openssl rand -hex 32)|" .env
  fi
fi

PORT=$(grep -E '^API_PORT=' .env | cut -d= -f2)
TOKEN=$(grep -E '^AUTH_TOKEN=' .env | cut -d= -f2)

say "Building images from this checkout"
docker compose build

say "Starting"
docker compose up -d

printf 'waiting for the api'
until curl -fsS "http://localhost:$PORT/api/health" >/dev/null 2>&1; do
  printf '.'; sleep 2
done
echo " healthy"

LAN_IP=$(ip route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')
LAN_IP=${LAN_IP:-<this-machine-lan-ip>}

cat <<EOF

Running. On a fresh database the API is now importing Folkets lexikon and the
SALDO word forms (~250MB, a few minutes), then fetching the first episodes.
Progress shows in the app and in the logs; transcription continues in the
background after that.

  Install the app:  http://$LAN_IP:$PORT/download
  Server URL:       http://$LAN_IP:$PORT
  Auth token:       $TOKEN

If the install page says no APK has been built yet, run:

  ./scripts/android.sh

Watch progress:     docker compose logs -f api worker
EOF
