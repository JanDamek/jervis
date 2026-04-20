#!/bin/bash
set -euo pipefail

# Build + launch the macOS native APNs helper (apps/macApp). The helper
# registers with APNs, owns the Unix socket at
# /tmp/jervis-macapp-apns.sock, and relays push payloads to the Compose
# Desktop JVM — which runs as the standalone `apps/desktop` Jervis.app
# (started via `./gradlew :apps:desktop:runPublic`).
#
# Usage:
#   ./run-mac.sh              # build + launch
#   ./run-mac.sh clean        # wipe generated xcodeproj, then build + launch
#
# Prerequisites:
#   - Xcode 15+
#   - xcodegen  (`brew install xcodegen`)
#   - For APNs support (optional, needed once for real push routing):
#     DEVELOPMENT_TEAM=<AppleTeamID> and enable "Push Notifications"
#     capability in Xcode → Signing & Capabilities.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MACAPP_DIR="$ROOT/apps/macApp"

# --- Preconditions --------------------------------------------------------
if ! command -v xcodegen >/dev/null 2>&1; then
    echo "ERROR: xcodegen not found — install with: brew install xcodegen" >&2
    exit 1
fi

if ! command -v xcodebuild >/dev/null 2>&1; then
    echo "ERROR: xcodebuild not found — install Xcode from the App Store" >&2
    exit 1
fi

# --- Optional clean -------------------------------------------------------
if [ "${1:-}" = "clean" ]; then
    echo "[run-mac] Cleaning Xcode derived data + generated project…"
    rm -rf "$ROOT/build/macapp"
    rm -rf "$MACAPP_DIR/macApp.xcodeproj"
fi

# --- 1. Generate Xcode project via xcodegen -------------------------------
echo "[run-mac] Generating apps/macApp/macApp.xcodeproj from project.yml…"
( cd "$MACAPP_DIR" && xcodegen generate )

# --- 2. Build + sign ------------------------------------------------------
DERIVED="$ROOT/build/macapp"
mkdir -p "$DERIVED"
echo "[run-mac] Building macApp (Debug)…"

XCODEBUILD_ARGS=(
    -project "$MACAPP_DIR/macApp.xcodeproj"
    -scheme macApp
    -configuration Debug
    -derivedDataPath "$DERIVED"
    clean build
)

# If DEVELOPMENT_TEAM is set, pass it through so xcodebuild can sign
# without requiring an Xcode GUI open first.
if [ -n "${DEVELOPMENT_TEAM:-}" ]; then
    XCODEBUILD_ARGS+=( "DEVELOPMENT_TEAM=$DEVELOPMENT_TEAM" )
fi

xcodebuild "${XCODEBUILD_ARGS[@]}"

APP_PATH="$DERIVED/Build/Products/Debug/Jervis.app"
if [ ! -d "$APP_PATH" ]; then
    echo "ERROR: Build succeeded but .app not found at $APP_PATH" >&2
    exit 1
fi

# --- 3. Launch ------------------------------------------------------------
# Kill any previous macApp instance so a new socket can bind cleanly.
pkill -f "$DERIVED/Build/Products/Debug/Jervis.app" 2>/dev/null || true
rm -f /tmp/jervis-macapp-apns.sock

echo "[run-mac] Launching ${APP_PATH}"
open "${APP_PATH}"
echo "[run-mac] macApp (APNs helper) started. Start the Compose Desktop"
echo "          client separately via:  ./gradlew :apps:desktop:runPublic"
echo "          Tail logs:"
echo "            log stream --predicate 'process == \"Jervis\"' --style compact"
