#!/usr/bin/env bash
# Build the Android app inside a container. Nothing is installed on the host.
#
#   scripts/android.sh                 # signed release APK, published to /download
#   scripts/android.sh assembleDebug   # or any other gradle task
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE=svenska-android-build
KEYSTORE="$ROOT/android/release.keystore"
KEYPROPS="$ROOT/android/keystore.properties"
APK_DIR="$ROOT/data/apk"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "building $IMAGE (one time, a few minutes)..."
  docker build -t "$IMAGE" -f "$ROOT/android/Dockerfile.build" "$ROOT/android"
fi

docker volume create svenska-gradle-cache >/dev/null
docker run --rm -v svenska-gradle-cache:/gradle-cache "$IMAGE" \
  chown -R "$(id -u):$(id -g)" /gradle-cache

run_in_container() {
  docker run --rm \
    -v "$ROOT/android:/workspace" \
    -v svenska-gradle-cache:/gradle-cache \
    -u "$(id -u):$(id -g)" \
    -e GRADLE_USER_HOME=/gradle-cache \
    -e HOME=/tmp \
    "$IMAGE" "$@"
}

# A stable signing key is what makes updates work. Android identifies an app by
# (applicationId, signing key); the container's debug keystore is regenerated
# every run, so debug builds could never update an existing install.
if [ ! -f "$KEYSTORE" ]; then
  echo "==> Generating a release signing key (one time)"
  PASS="$(openssl rand -hex 24)"
  run_in_container keytool -genkeypair -v \
    -keystore /workspace/release.keystore \
    -alias svenska \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Svenska Listening Trainer, OU=Personal, O=Personal, C=SE" \
    >/dev/null
  cat > "$KEYPROPS" <<EOF
storeFile=release.keystore
storePassword=$PASS
keyAlias=svenska
keyPassword=$PASS
EOF
  chmod 600 "$KEYPROPS" "$KEYSTORE"
  cat <<EOF

  *** BACK UP android/release.keystore AND android/keystore.properties ***
  Losing them means Android will refuse every future update, and you would
  have to uninstall the app (losing downloads and settings) to reinstall.
  Both are gitignored on purpose: they are secrets.

EOF
fi

# versionCode must strictly increase or Android rejects the update. Minutes
# since 2024-01-01 is monotonic for any build, committed or not, and stays well
# inside the 2100000000 ceiling for the next few thousand years. Commit count
# would look tidier but does not move when you rebuild uncommitted changes,
# which is precisely when you most want to install the result.
VERSION_CODE=$(( ($(date -u +%s) - 1704067200) / 60 ))
GIT_DESC="$(git -C "$ROOT" describe --always --dirty 2>/dev/null || echo nogit)"
VERSION_NAME="1.0-$(date -u +%Y%m%d.%H%M)-$GIT_DESC"

TASK="${1:-assembleRelease}"
shift || true

echo "==> gradle $TASK (versionCode=$VERSION_CODE versionName=$VERSION_NAME)"
run_in_container gradle --no-daemon "$TASK" \
  -PappVersionCode="$VERSION_CODE" \
  -PappVersionName="$VERSION_NAME" "$@"

# Publish a release build so the server's /download page can serve it.
APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  mkdir -p "$APK_DIR"
  cp "$APK" "$APK_DIR/svenska.apk"
  echo "$VERSION_NAME (build $VERSION_CODE)" > "$APK_DIR/version.txt"
  # Machine-readable manifest for the app's own update check.
  cat > "$APK_DIR/version.json" <<JSON
{
  "version_code": $VERSION_CODE,
  "version_name": "$VERSION_NAME",
  "size_bytes": $(stat -c%s "$APK"),
  "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
JSON
  echo "==> Published to $APK_DIR/svenska.apk"
  echo "    Install from a phone browser: http://<server-ip>:${API_PORT:-8080}/download"
fi
