#!/bin/bash
set -euo pipefail

# Build + run the macOS native Jervis app (apps/macApp) that wraps the
# Compose Desktop JVM and carries the APNs entitlement. Mirrors
# run-ios.sh but targets a local Mac — there is no simulator.
#
# Usage:
#   ./run-mac.sh              # build + launch
#   ./run-mac.sh clean        # clear staged Compose runtime, then build + launch
#
# Prerequisites:
#   - Xcode 15+
#   - xcodegen (brew install xcodegen) — turns apps/macApp/project.yml
#     into apps/macApp/macApp.xcodeproj so the binary pbxproj is never
#     committed
#   - APNs App ID com.jervis.macApp enabled in Apple Developer portal
#     (reuses the same APNs .p8 key as apps/iosApp)
#   - DEVELOPMENT_TEAM env var OR Team set on first Xcode open

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MACAPP_DIR="$ROOT/apps/macApp"
STAGED_RUNTIME="$MACAPP_DIR/macApp/Resources/JervisDesktop"

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
    echo "[run-mac] Cleaning staged Compose runtime and Xcode derived data…"
    rm -rf "$STAGED_RUNTIME"
    rm -rf "$ROOT/build/macapp"
    rm -rf "$MACAPP_DIR/macApp.xcodeproj"
fi

# --- 1. Compose Desktop runtime stage -------------------------------------
if [ ! -d "$STAGED_RUNTIME" ]; then
    echo "[run-mac] Building Compose Desktop distribution via Gradle…"
    "$ROOT/gradlew" :apps:desktop:createDistributable
    APP_BUNDLE="$ROOT/apps/desktop/build/compose/binaries/main/app/Jervis.app"
    if [ ! -d "$APP_BUNDLE" ]; then
        echo "ERROR: createDistributable did not produce $APP_BUNDLE" >&2
        exit 1
    fi
    mkdir -p "$MACAPP_DIR/macApp/Resources"
    cp -R "$APP_BUNDLE/Contents" "$STAGED_RUNTIME"
    echo "[run-mac] Compose runtime staged at $STAGED_RUNTIME"
else
    echo "[run-mac] Compose runtime already staged — pass 'clean' to rebuild."
fi

# --- 2. Generate Xcode project via xcodegen -------------------------------
echo "[run-mac] Generating apps/macApp/macApp.xcodeproj from project.yml…"
( cd "$MACAPP_DIR" && xcodegen generate )

# --- 3. Build + sign ------------------------------------------------------
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

# --- 4. Launch ------------------------------------------------------------
echo "[run-mac] Launching $APP_PATH…"
open "$APP_PATH"
echo "[run-mac] Started. Tail logs with:"
echo "  log stream --predicate 'subsystem == \"com.jervis.macApp\"' --style compact"
echo "  tail -f ~/Library/Logs/jervis-desktop.log  # (if Compose JVM writes there)"
