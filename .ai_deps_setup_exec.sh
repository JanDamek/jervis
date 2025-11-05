#!/usr/bin/env bash
set -euo pipefail

# Move to project root
dirname="$(dirname "$0")"
cd "$dirname"

export CI=true
export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx2g"

./gradlew --no-daemon --stacktrace --refresh-dependencies build -x test
./gradlew --no-daemon --stacktrace testClasses
