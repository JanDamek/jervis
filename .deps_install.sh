#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

./gradlew --no-daemon --console=plain build test --stacktrace --refresh-dependencies
