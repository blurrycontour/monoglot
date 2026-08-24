#!/usr/bin/env bash
# Build the Android app inside a container. Nothing is installed on the host.
#
#   scripts/android.sh                 # signed release APK, published to /download
#   scripts/android.sh assembleDebug   # or any other gradle task
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE=monoglot-android-build
KEYSTORE="$ROOT/android/release.keystore"
KEYPROPS="$ROOT/android/keystore.properties"
APK_DIR="$ROOT/data/apk"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "building $IMAGE (one time, a few minutes)..."
  docker build -t "$IMAGE" -f "$ROOT/android/Dockerfile.build" "$ROOT/android"
fi

docker volume create monoglot-gradle-cache >/dev/null
docker run --rm -v monoglot-gradle-cache:/gradle-cache "$IMAGE" \
  chown -R "$(id -u):$(id -g)" /gradle-cache

run_in_container() {
  docker run --rm \
    -v "$ROOT/android:/workspace" \
    -v monoglot-gradle-cache:/gradle-cache \
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
    -alias monoglot \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Monoglot Listening Trainer, OU=Personal, O=Personal, C=SE" \
    >/dev/null
  cat > "$KEYPROPS" <<EOF
storeFile=release.keystore
storePassword=$PASS
keyAlias=monoglot
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

# Publish only when this invocation actually produced a release APK. A stale
# APK from an earlier build must never be republished under a fresh version
# number: the app would then be told to update to a build that does not exist,
# and would keep asking forever after installing the real one.
APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
case "$TASK" in
  assembleRelease|bundleRelease|build|assemble) PUBLISH=1 ;;
  *) PUBLISH=0 ;;
esac

if [ "$PUBLISH" = "1" ] && [ -f "$APK" ]; then
  mkdir -p "$APK_DIR"
  cp "$APK" "$APK_DIR/monoglot.apk"

  # Read the version back out of the built APK rather than trusting the shell
  # variable. These must agree exactly or the update check never converges.
  # No `| head` here: under `set -o pipefail` the early close sends SIGPIPE to
  # aapt2 and aborts the script even though the read succeeded.
  BADGING=$(docker run --rm -v "$APK_DIR:/apk" "$IMAGE" \
    /opt/android-sdk/build-tools/35.0.0/aapt2 dump badging /apk/monoglot.apk 2>/dev/null || true)
  PACKAGE_LINE=${BADGING%%$'\n'*}
  APK_CODE=$(printf '%s' "$PACKAGE_LINE" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
  APK_NAME=$(printf '%s' "$PACKAGE_LINE" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")

  if [ -z "$APK_CODE" ]; then
    echo "!! could not read versionCode from the APK; not publishing a manifest" >&2
    exit 1
  fi

  echo "$APK_NAME (build $APK_CODE)" > "$APK_DIR/version.txt"
  cat > "$APK_DIR/version.json" <<JSON
{
  "version_code": $APK_CODE,
  "version_name": "$APK_NAME",
  "size_bytes": $(stat -c%s "$APK_DIR/monoglot.apk"),
  "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
JSON
  echo "==> Published build $APK_CODE to $APK_DIR/monoglot.apk"
  echo "    Install from a phone browser: http://<server-ip>:${API_PORT:-8080}/download"
fi
