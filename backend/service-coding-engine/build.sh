#!/bin/bash
set -e

echo "Building service-coding-engine JAR..."
cd "$(dirname "$0")/../.."
./gradlew :backend:service-coding-engine:clean :backend:service-coding-engine:build -x test

echo "✓ service-coding-engine JAR built successfully"
