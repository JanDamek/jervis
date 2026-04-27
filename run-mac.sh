#!/bin/bash
set -euo pipefail

# Build Jervis.app (jpackage + JervisAPNs helper + CEF runtime
# embedded under Contents/Resources) and launch it. Mirrors the
# run-ios.sh / run-android.sh pattern — one script per platform that
# builds and runs.

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

echo "==> Building JervisAPNs Swift helper"
( cd apps/macApp && xcodegen generate )
xcodebuild \
  -project apps/macApp/JervisAPNs.xcodeproj \
  -scheme JervisAPNs \
  -configuration Debug \
  -derivedDataPath apps/macApp/build \
  -allowProvisioningUpdates \
  -skipPackagePluginValidation \
  -skipMacroValidation \
  build

echo "==> Staging CEF runtime from ~/.jervis/kcef"
./gradlew :apps:desktop:prepareCefBundle

echo "==> Building Jervis.app (jpackage createDistributable)"
./gradlew :apps:desktop:createDistributable :apps:desktop:bundleApnsHelper :apps:desktop:bundleCefRuntime :apps:desktop:resignWithHelper

APP="$PROJECT_ROOT/apps/desktop/build/compose/binaries/main/app/Jervis.app"
if [ ! -d "$APP" ]; then
    echo "ERROR: Jervis.app not found at $APP" >&2
    exit 1
fi

echo "==> Killing previous Jervis instance"
pkill -x Jervis 2>/dev/null || true
pkill -f JervisAPNs 2>/dev/null || true
sleep 1

INSTALLED="/Applications/Jervis.app"
echo "==> Installing to $INSTALLED"
rm -rf "$INSTALLED"
cp -R "$APP" "$INSTALLED"

echo "==> Launching $INSTALLED"
open "$INSTALLED"
