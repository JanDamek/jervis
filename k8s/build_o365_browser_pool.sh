#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SERVICE_NAME="jervis-o365-browser-pool"
DOCKERFILE="backend/service-o365-browser-pool/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (Python) ==="

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Docker Build
echo "Step 1/3: Building Docker image..."
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}/backend/service-o365-browser-pool"
echo "✓ Docker image built"

# Docker Push
echo "Step 2/3: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

# Deploy to Kubernetes
echo "Step 3/3: Deploying to Kubernetes..."
kubectl apply -f "$SCRIPT_DIR/app_o365_browser_pool.yaml" -n "${NAMESPACE}"
kubectl rollout restart statefulset/jervis-o365-browser-pool -n ${NAMESPACE}
kubectl rollout status statefulset/jervis-o365-browser-pool -n ${NAMESPACE}

echo "=== ✓ ${SERVICE_NAME} deployed ==="
