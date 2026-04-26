#!/usr/bin/env bash
# Build a release/patch fixture pair under scripts/app-updates-server/fixtures/.
# Pulls the currently installed wings.v APK as `previous.apk`, builds a fresh
# debug APK at the target version as `new.apk`, generates the zstd patch and
# matching releases.json. Defaults: base = currently installed version, target
# = 99.99.99. Override via env: BASE_VERSION, TARGET_VERSION.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FIXTURES_DIR="$SCRIPT_DIR/fixtures"
TARGET_VERSION="${TARGET_VERSION:-99.99.99}"
PORT="${WINGSV_MOCK_PORT:-8080}"
BASE_URL="http://127.0.0.1:${PORT}"

require() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }; }
require adb
require zstd
require python3
require sha256sum
require sha512sum

mkdir -p "$FIXTURES_DIR"
cd "$REPO_ROOT"

INSTALLED_PATH="$(adb shell pm path wings.v 2>/dev/null | sed 's|^package:||' | tr -d '\r' | head -n1)"
if [[ -z "$INSTALLED_PATH" ]]; then
    echo "wings.v is not installed on device — install a debug APK first" >&2
    exit 1
fi

if [[ -z "${BASE_VERSION:-}" ]]; then
    BASE_VERSION="$(adb shell dumpsys package wings.v | sed -nE 's/.*versionName=([^[:space:]]+).*/\1/p' | head -n1 | tr -d '\r')"
fi
echo "base = $BASE_VERSION (currently installed) -> target = $TARGET_VERSION"

echo "==> pulling installed apk as previous.apk"
adb shell "su -c 'cat \"$INSTALLED_PATH\"'" > "$FIXTURES_DIR/previous.apk"
if [[ ! -s "$FIXTURES_DIR/previous.apk" ]]; then
    echo "failed to read installed APK from device" >&2
    exit 1
fi

echo "==> building target debug apk (-Pver=$TARGET_VERSION)"
./gradlew :app:assembleDebug "-Pver=$TARGET_VERSION" --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk "$FIXTURES_DIR/new.apk"

PATCH_FILE="wings-v_v${TARGET_VERSION}.patch"

echo "==> generating zstd patch"
rm -f "$FIXTURES_DIR/$PATCH_FILE"
zstd -q -19 --patch-from="$FIXTURES_DIR/previous.apk" "$FIXTURES_DIR/new.apk" -o "$FIXTURES_DIR/$PATCH_FILE"

echo "==> computing checksums"
( cd "$FIXTURES_DIR" && \
    sha256sum new.apk         | awk '{print $1}' > new.apk.sha256 && \
    sha512sum new.apk         | awk '{print $1}' > new.apk.sha512 && \
    sha256sum "$PATCH_FILE"   | awk '{print $1}' > "${PATCH_FILE}.sha256" && \
    sha512sum "$PATCH_FILE"   | awk '{print $1}' > "${PATCH_FILE}.sha512" )

APK_SIZE=$(stat -c%s "$FIXTURES_DIR/new.apk")
PATCH_SIZE=$(stat -c%s "$FIXTURES_DIR/$PATCH_FILE")

cat > "$FIXTURES_DIR/releases.json" <<JSON
[
  {
    "tag_name": "v${TARGET_VERSION}",
    "name": "v${TARGET_VERSION}",
    "draft": false,
    "prerelease": false,
    "published_at": "2026-04-26T15:00:00Z",
    "body": "Mock release",
    "html_url": "${BASE_URL}/releases/v${TARGET_VERSION}",
    "assets": [
      { "name": "app-release.apk",                  "size": ${APK_SIZE},   "browser_download_url": "${BASE_URL}/assets/new.apk" },
      { "name": "app-release.apk.sha256",           "size": 64,            "browser_download_url": "${BASE_URL}/assets/new.apk.sha256" },
      { "name": "app-release.apk.sha512",           "size": 128,           "browser_download_url": "${BASE_URL}/assets/new.apk.sha512" },
      { "name": "${PATCH_FILE}",                    "size": ${PATCH_SIZE}, "browser_download_url": "${BASE_URL}/assets/${PATCH_FILE}" },
      { "name": "${PATCH_FILE}.sha256",             "size": 64,            "browser_download_url": "${BASE_URL}/assets/${PATCH_FILE}.sha256" },
      { "name": "${PATCH_FILE}.sha512",             "size": 128,           "browser_download_url": "${BASE_URL}/assets/${PATCH_FILE}.sha512" }
    ]
  }
]
JSON

echo "==> done"
echo "fixtures: $FIXTURES_DIR"
echo "patch=${PATCH_SIZE}B apk=${APK_SIZE}B"
