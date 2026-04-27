#!/bin/bash
set -euo pipefail

# STUB — Windows build prep.
#
# Run this on a Windows host (or via cross-build later). The macOS APNs
# helper is not bundled — Windows uses Java SystemTray notifications and
# server pushes are not delivered when the app is closed (deferred per
# project-win-linux-desktop-push-deferred.md).
#
# Output: apps/desktop/build/compose/binaries/main/msi/Jervis-1.0.0.msi

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

echo "==> Building Jervis.exe + MSI (jpackage)"
./gradlew.bat :apps:desktop:packageMsi || ./gradlew :apps:desktop:packageMsi

MSI="$PROJECT_ROOT/apps/desktop/build/compose/binaries/main/msi/Jervis-1.0.0.msi"
if [ -f "$MSI" ]; then
  echo "==> MSI ready: $MSI"
else
  echo "ERROR: MSI not found at $MSI" >&2
  exit 1
fi
