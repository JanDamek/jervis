#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/validate_deployment.sh"

SERVICE_NAME="jervis-document-extraction"
DOCKERFILE="backend/service-document-extraction/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (Python) ==="

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "Step 1/3: Building Docker image..."
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}"
echo "✓ Docker image built"

echo "Step 2/3: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

echo "Step 3/3: Deploying to Kubernetes..."
kubectl apply -f "$SCRIPT_DIR/app_document_extraction.yaml" -n "${NAMESPACE}"
validate_deployment_spec "${SERVICE_NAME}" "$NAMESPACE"
kubectl rollout restart deployment/${SERVICE_NAME} -n ${NAMESPACE}
kubectl rollout status deployment/${SERVICE_NAME} -n ${NAMESPACE}

echo "=== ✓ ${SERVICE_NAME} complete ==="
