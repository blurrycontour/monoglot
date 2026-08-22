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

say "Running the ingestion pipeline (discover, download, transcribe)"
say "Transcription runs at roughly 3x realtime on CPU; a 5 minute episode takes ~90s."
docker compose run --rm api ingest discover
docker compose run --rm api ingest download
docker compose run --rm api ingest transcribe

say "Done. Ready items:"
docker compose exec -T postgres psql -U "$(grep -E '^POSTGRES_USER=' .env | cut -d= -f2)" \
  -d "$(grep -E '^POSTGRES_DB=' .env | cut -d= -f2)" \
  -c "SELECT i.id, s.slug, i.status, left(i.title, 40) FROM items i JOIN sources s ON s.id=i.source_id WHERE i.status='ready' ORDER BY i.id;"

cat <<EOF

Next steps
  Server URL for the phone:  http://<this-machine-lan-ip>:$PORT
  Auth token:                $TOKEN

  Build the Android app:     ./scripts/android.sh assembleDebug
  APK lands at:              android/app/build/outputs/apk/debug/app-debug.apk

EOF
