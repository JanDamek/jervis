#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

SERVICE_NAME="jervis-whatsapp-browser"
DOCKERFILE="backend/service-whatsapp-browser/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (Python) ==="

# Docker Build
echo "Step 1/3: Building Docker image..."
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}/backend/service-whatsapp-browser"

echo "✓ Docker image built"

# Docker Push
echo "Step 2/3: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

# Deploy to Kubernetes
echo "Step 3/3: Deploying to Kubernetes..."
kubectl apply -f "$SCRIPT_DIR/configmap.yaml" -n "${NAMESPACE}"
kubectl apply -f "$SCRIPT_DIR/app_whatsapp_browser.yaml" -n "${NAMESPACE}"

# Note: validate_deployment_spec only works for Deployments, not StatefulSets
kubectl rollout restart statefulset/jervis-whatsapp-browser -n ${NAMESPACE}
kubectl rollout status statefulset/jervis-whatsapp-browser -n ${NAMESPACE}

echo "=== ✓ ${SERVICE_NAME} deployed ==="
