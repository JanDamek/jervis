#!/bin/bash
set -e

echo "Building service-aider JAR..."
cd "$(dirname "$0")/../.."
./gradlew :backend:service-aider:clean :backend:service-aider:build -x test

echo "✓ service-aider JAR built successfully"
