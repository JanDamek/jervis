#!/usr/bin/env bash
set -eu
cd /workspaces/jervis
chmod +x ./gradlew
./gradlew build --no-daemon --refresh-dependencies --stacktrace --warning-mode=all