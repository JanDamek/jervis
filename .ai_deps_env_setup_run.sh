#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./gradlew build --no-daemon --stacktrace --refresh-dependencies
./gradlew testClasses --no-daemon --stacktrace --refresh-dependencies
