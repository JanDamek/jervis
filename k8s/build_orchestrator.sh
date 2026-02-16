#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

SERVICE_NAME="jervis-orchestrator"
DOCKERFILE="backend/service-orchestrator/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (Python) ==="

# Docker Build
echo "Step 1/3: Building Docker image..."
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}/backend/service-orchestrator"

echo "✓ Docker image built"

# Docker Push
echo "Step 2/3: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

# Deploy
echo "Step 3/3: Deploying to Kubernetes..."
kubectl apply -f "$SCRIPT_DIR/orchestrator-rbac.yaml" -n "${NAMESPACE}"
kubectl apply -f "$SCRIPT_DIR/app_orchestrator.yaml" -n "${NAMESPACE}"

# Validate YAML changes propagated to K8s
validate_deployment_spec "jervis-orchestrator" "$NAMESPACE"

kubectl rollout restart deployment/jervis-orchestrator -n ${NAMESPACE}
kubectl rollout status deployment/jervis-orchestrator -n ${NAMESPACE}

echo "=== ✓ ${SERVICE_NAME} complete ==="
