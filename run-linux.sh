#!/bin/bash
set -euo pipefail

# STUB — Linux build prep.
#
# Run on a Linux host. No APNs helper — push notifications are
# delivered via libnotify (notify-send) only when the app is running
# (no system-level wake-on-push, see project-win-linux-desktop-push-deferred.md).
#
# Output: apps/desktop/build/compose/binaries/main/deb/jervis_1.0.0-1_amd64.deb

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

echo "==> Building Jervis + DEB (jpackage)"
./gradlew :apps:desktop:packageDeb

DEB=$(find "$PROJECT_ROOT/apps/desktop/build/compose/binaries/main/deb" -name "*.deb" | head -1)
if [ -n "$DEB" ] && [ -f "$DEB" ]; then
  echo "==> DEB ready: $DEB"
else
  echo "ERROR: DEB not found in apps/desktop/build/compose/binaries/main/deb" >&2
  exit 1
fi
