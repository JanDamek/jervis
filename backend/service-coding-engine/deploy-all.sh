#!/bin/bash
set -e

SCRIPT_DIR="$(dirname "$0")"

echo "=== Building and deploying service-coding-engine ==="
echo ""

echo "Step 1/4: Building JAR..."
"${SCRIPT_DIR}/build.sh"
echo ""

echo "Step 2/4: Building Docker image..."
"${SCRIPT_DIR}/docker-build.sh"
echo ""

echo "Step 3/4: Pushing Docker image..."
"${SCRIPT_DIR}/docker-push.sh"
echo ""

echo "Step 4/4: Deploying to Kubernetes..."
"${SCRIPT_DIR}/deploy.sh"
echo ""

echo "=== ✓ service-coding-engine deployment complete ==="
