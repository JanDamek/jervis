#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

# Config
SERVICE_NAME="jervis-kilo"
DOCKERFILE="backend/service-kilo/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"
NAMESPACE="jervis"

echo "=== Building ${SERVICE_NAME} (KILO coding agent) ==="

# 1. Docker Build (no Gradle needed — pure Python/aider container)
echo "Step 1/2: Building Docker image..."
cd "$(dirname "$0")/.."
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${DOCKERFILE}" .
echo "✓ Docker image ${IMAGE} built successfully"

# 2. Docker Push
echo "Step 2/2: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

echo "=== ✓ ${SERVICE_NAME} complete (Job image only — no K8s Deployment) ==="
