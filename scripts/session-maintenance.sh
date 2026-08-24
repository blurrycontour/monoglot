#!/usr/bin/env bash
# Session maintenance: rebuild, restart, reclaim Docker disk.
#
# Wired to the Stop hook, so it runs after every assistant turn. The rebuild
# takes minutes, so this detaches immediately and a lockfile makes overlapping
# runs a no-op. Output goes to data/maintenance.log, never to the transcript.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/data/.maintenance.lock"
LOG="$ROOT/data/maintenance.log"

mkdir -p "$ROOT/data"

# Already running: leave it alone rather than stacking docker builds.
if [ -e "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
  exit 0
fi

(
  echo "$$" > "$LOCK"
  trap 'rm -f "$LOCK"' EXIT

  {
    echo "=== $(date -Is) maintenance start ==="
    (cd "$ROOT" && ./scripts/android.sh) || echo "android.sh failed with $?"
    (cd "$ROOT" && docker compose up -d --build) || echo "compose failed with $?"
    # Build cache is the single biggest reclaimable consumer on this box and
    # regenerates on demand.
    docker builder prune -af || true
    df -h / | tail -1
    echo "=== $(date -Is) maintenance done ==="
  } >> "$LOG" 2>&1
) </dev/null >/dev/null 2>&1 &

disown || true
exit 0
