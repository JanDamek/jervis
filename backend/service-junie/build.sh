#!/bin/bash
set -e

echo "Building service-junie JAR..."
cd "$(dirname "$0")/../.."
./gradlew :backend:service-junie:clean :backend:service-junie:build -x test

echo "✓ service-junie JAR built successfully"
