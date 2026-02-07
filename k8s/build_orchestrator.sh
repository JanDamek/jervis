#!/bin/bash
set -e

SERVICE_NAME="jervis-orchestrator"
DOCKERFILE="backend/service-orchestrator/Dockerfile"
DEPLOY_NAME="orchestrator"
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
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}/backend/service-orchestrator"

echo "✓ Docker images built"

# Docker Push
echo "Step 2/3: Pushing Docker images..."
docker push "${IMAGE_VERSIONED}"
docker push "${IMAGE_LATEST}"
echo "✓ Docker images pushed"

# Deploy
echo "Step 3/3: Deploying to Kubernetes..."
# Apply RBAC (ServiceAccount, Role, RoleBinding for K8s Job management)
kubectl apply -f "${PROJECT_ROOT}/k8s/orchestrator-rbac.yaml" -n "${NAMESPACE}"
# Apply deployment + service
kubectl apply -f "${PROJECT_ROOT}/k8s/app_orchestrator.yaml" -n "${NAMESPACE}"
# Update image to force restart
kubectl set image deployment/jervis-orchestrator orchestrator=${IMAGE_VERSIONED} -n ${NAMESPACE}
kubectl rollout status deployment/jervis-orchestrator -n ${NAMESPACE}

echo "=== ✓ ${SERVICE_NAME} complete ==="
