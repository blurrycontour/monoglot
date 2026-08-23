#!/usr/bin/env bash
# Clean checkout -> working instance with Klartext episodes fully processed.
# Idempotent: safe to re-run.
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

say "Building images"
docker compose build

say "Starting postgres and api (migrations run on api startup)"
docker compose up -d postgres api
until curl -fsS "http://localhost:$(grep -E '^API_PORT=' .env | cut -d= -f2)/api/health" >/dev/null 2>&1; do
  printf '.'; sleep 2
done
echo " api healthy"

say "Importing Folkets lexikon (~40k entries)"
docker compose run --rm api import-dictionary

say "Importing SALDO morphology (~250MB download, ~1.7M forms)"
docker compose run --rm api import-morphology

say "Verifying morphology: husen -> hus, gick -> ga"
TOKEN=$(grep -E '^AUTH_TOKEN=' .env | cut -d= -f2)
PORT=$(grep -E '^API_PORT=' .env | cut -d= -f2)
for w in husen gick; do
  echo -n "  $w: "
  curl -fsS -H "Authorization: Bearer $TOKEN" "http://localhost:$PORT/api/lookup?w=$w" \
    | head -c 160
  echo
done

say "Starting the transcription worker and preloading KB-Whisper"
docker compose up -d worker
until docker compose exec -T worker curl -fsS http://localhost:9000/health >/dev/null 2>&1; do
  printf '.'; sleep 2
done
docker compose exec -T worker curl -fsS -X POST http://localhost:9000/warm >/dev/null
echo " model ready"

say "Fetching the episode list and downloading audio"
say "This part is quick. Transcription is the slow step and runs in the background."
docker compose run --rm api ingest discover
docker compose run --rm api ingest download

# Transcription takes roughly 20s per minute of audio on CPU, so blocking on it
# here would mean a ten minute wait before you could open the app. Hand it to
# the running API instead: it transcribes in the background and the app shows
# progress on the library screen.
say "Starting transcription in the background"
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:$PORT/api/admin/ingest" >/dev/null && echo "  started"

LAN_IP=$(ip route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')
LAN_IP=${LAN_IP:-<this-machine-lan-ip>}

cat <<EOF

Ready. Transcription is running in the background; episodes appear in the app
as they finish, and the library screen shows how many are still processing.

  Install the app:  http://$LAN_IP:$PORT/download
  Server URL:       http://$LAN_IP:$PORT
  Auth token:       $TOKEN

If the install page says no APK has been built yet, run:

  ./scripts/android.sh

Watch progress:     docker compose logs -f api worker
EOF
