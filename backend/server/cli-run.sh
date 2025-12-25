#!/bin/bash

# Koog CLI Test Application Runner
# Usage: ./cli-run.sh

# Set DATA_ROOT_DIR for logging (CLI doesn't really need it, but Spring Boot requires it)
export DATA_ROOT_DIR="${DATA_ROOT_DIR:-/tmp/jervis-cli}"

# Ensure directory exists
mkdir -p "$DATA_ROOT_DIR/logs"

echo "Starting Koog CLI Test Application..."
echo "Logs directory: $DATA_ROOT_DIR/logs"
echo ""

# Get the script's directory and go to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$PROJECT_ROOT" || exit 1

# Run with CLI profile
./gradlew :backend:server:bootRun --args='--spring.profiles.active=cli'
