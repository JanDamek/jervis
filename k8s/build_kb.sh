#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

SERVICE_NAME="jervis-knowledgebase"
DOCKERFILE="backend/service-knowledgebase/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (Python) ==="

# Docker Build
echo "Step 1/3: Building Docker image..."
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}/backend/service-knowledgebase"

echo "✓ Docker image built"

# Docker Push
echo "Step 2/3: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

# Deploy to Kubernetes
echo "Step 3/3: Deploying to Kubernetes..."
kubectl apply -f "$SCRIPT_DIR/configmap.yaml" -n "${NAMESPACE}"
kubectl apply -f "$SCRIPT_DIR/app_knowledgebase.yaml" -n "${NAMESPACE}"

# Validate YAML changes propagated to K8s (both read and write deployments)
validate_deployment_spec "jervis-knowledgebase-read" "$NAMESPACE"
validate_deployment_spec "jervis-knowledgebase-write" "$NAMESPACE"

# Restart both read and write deployments to pull latest
kubectl rollout restart deployment/jervis-knowledgebase-read -n ${NAMESPACE}
kubectl rollout restart deployment/jervis-knowledgebase-write -n ${NAMESPACE}
echo "Waiting for read deployment rollout..."
kubectl rollout status deployment/jervis-knowledgebase-read -n ${NAMESPACE}
echo "Waiting for write deployment rollout..."
kubectl rollout status deployment/jervis-knowledgebase-write -n ${NAMESPACE}

echo "=== ✓ ${SERVICE_NAME} complete (read + write) ==="
