#!/usr/bin/env bash
set -euo pipefail

# Change to script's directory (project root)
dirname=$(dirname "$0")
cd "$dirname"

# Stop any running Gradle daemons (optional, non-fatal)
./gradlew --stop || true
# Clean project
./gradlew clean

# Download all runtime and test dependencies for all modules
./gradlew dependencies --configuration=runtimeClasspath
./gradlew dependencies --configuration=testRuntimeClasspath

# Build all modules, ensuring plugins/annotation processors/etc. are resolved
./gradlew build --no-daemon --stacktrace --refresh-dependencies -x test
# Ensure test dependencies are present (test classes compiled)
./gradlew testClasses --no-daemon --stacktrace --refresh-dependencies
