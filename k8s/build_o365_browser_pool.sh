#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SERVICE_NAME="jervis-o365-browser-pool"
DOCKERFILE="backend/service-o365-browser-pool/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building ${SERVICE_NAME} (Python) ==="

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Docker Build
echo "Step 1/2: Building Docker image..."
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}/backend/service-o365-browser-pool"
echo "✓ Docker image built"

# Docker Push
echo "Step 2/2: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

# Dynamic browser pods are managed by the server (BrowserPodManager).
# To restart all browser pods after image update, restart the pods via:
#   kubectl get deployments -n jervis -l managed-by=jervis-browser-pod --no-headers | awk '{print $1}' | xargs -I{} kubectl rollout restart deployment/{} -n jervis
echo ""
echo "Image pushed. Dynamic browser pods will use new image on next restart."
echo "To force-restart all browser pods:"
echo "  kubectl get deployments -n jervis -l managed-by=jervis-browser-pod --no-headers | awk '{print \$1}' | xargs -I{} kubectl rollout restart deployment/{} -n jervis"

echo "=== ✓ ${SERVICE_NAME} image built and pushed ==="
