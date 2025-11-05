#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./gradlew --no-daemon --console=plain --quiet dependencies || true
./gradlew --no-daemon --console=plain --quiet testClasses
