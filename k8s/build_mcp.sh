#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

SERVICE_NAME="jervis-mcp"
DOCKERFILE="backend/service-mcp/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (Python) ==="

# Docker Build
echo "Step 1/3: Building Docker image..."
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}/backend/service-mcp"

echo "✓ Docker image built"

# Docker Push
echo "Step 2/3: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

# Deploy to Kubernetes
echo "Step 3/3: Deploying to Kubernetes..."
kubectl apply -f "$SCRIPT_DIR/configmap.yaml" -n "${NAMESPACE}"
kubectl apply -f "$SCRIPT_DIR/app_mcp.yaml" -n "${NAMESPACE}"

# Validate YAML changes propagated to K8s
validate_deployment_spec "jervis-mcp" "$NAMESPACE"

kubectl rollout restart deployment/jervis-mcp -n ${NAMESPACE}
kubectl rollout status deployment/jervis-mcp -n ${NAMESPACE}

echo "=== ✓ ${SERVICE_NAME} deployed at jervis-mcp.damek-soft.eu ==="
