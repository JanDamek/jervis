#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

SERVICE_NAME="jervis-correction"
DOCKERFILE="backend/service-correction/Dockerfile"
DEPLOY_NAME="correction"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"

# Generate version tag
GIT_HASH=$(git rev-parse --short HEAD 2>/dev/null || echo "local")
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
VERSION_TAG="${GIT_HASH}-${TIMESTAMP}"

IMAGE_VERSIONED="${REGISTRY}/${SERVICE_NAME}:${VERSION_TAG}"
IMAGE_LATEST="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (Python) ==="
echo "Version: ${VERSION_TAG}"

# Docker Build
echo "Step 1/3: Building Docker image..."
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Context is the service directory
docker build --platform linux/amd64 \
  -t "${IMAGE_VERSIONED}" \
  -t "${IMAGE_LATEST}" \
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}/backend/service-correction"

echo "✓ Docker images built"

# Docker Push
echo "Step 2/3: Pushing Docker images..."
docker push "${IMAGE_VERSIONED}"
docker push "${IMAGE_LATEST}"
echo "✓ Docker images pushed"

# Deploy to Kubernetes
echo "Step 3/3: Deploying to Kubernetes..."
kubectl apply -f "$SCRIPT_DIR/app_correction.yaml" -n "${NAMESPACE}"

# Validate YAML changes propagated to K8s
validate_deployment_spec "jervis-correction" "$NAMESPACE"

# Update deployment
kubectl set image deployment/jervis-correction correction=${IMAGE_VERSIONED} -n ${NAMESPACE}
# Wait for rollout
kubectl rollout status deployment/jervis-correction -n ${NAMESPACE}

echo "=== ✓ ${SERVICE_NAME} complete ==="
