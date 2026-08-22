#!/usr/bin/env bash
# Run a Gradle task for the Android app inside the build container.
# Usage: scripts/android.sh [gradle-args...]   (default: assembleDebug)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE=svenska-android-build

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "building $IMAGE (one time, a few minutes)..."
  docker build -t "$IMAGE" -f "$ROOT/android/Dockerfile.build" "$ROOT/android"
fi

# Named volume for the Gradle cache so repeat builds are fast. A fresh volume
# is root-owned, but the build runs as the host user so that generated files
# are not root-owned on the host; hand the volume over on first use.
docker volume create svenska-gradle-cache >/dev/null
docker run --rm -v svenska-gradle-cache:/gradle-cache "$IMAGE" \
  chown -R "$(id -u):$(id -g)" /gradle-cache

exec docker run --rm \
  -v "$ROOT/android:/workspace" \
  -v svenska-gradle-cache:/gradle-cache \
  -u "$(id -u):$(id -g)" \
  -e GRADLE_USER_HOME=/gradle-cache \
  -e HOME=/tmp \
  "$IMAGE" \
  gradle --no-daemon "${@:-assembleDebug}"
