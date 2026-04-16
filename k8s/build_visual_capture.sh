#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SERVICE_NAME="jervis-visual-capture"
DOCKERFILE="backend/service-visual-capture/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (Python) ==="

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Apply ConfigMap first (may have new VISUAL_CAPTURE_* env vars)
echo "Step 1/4: Applying ConfigMap..."
kubectl apply -f "$SCRIPT_DIR/configmap.yaml" -n "${NAMESPACE}"

# Docker Build — build context = PROJECT_ROOT so Dockerfile can COPY
# libs/jervis_contracts/ (pod-to-pod contracts).
echo "Step 2/4: Building Docker image..."
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}"
echo "✓ Docker image built"

# Docker Push
echo "Step 3/4: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

# Deploy to Kubernetes
echo "Step 4/4: Deploying to Kubernetes..."
kubectl apply -f "$SCRIPT_DIR/app_visual_capture.yaml" -n "${NAMESPACE}"

# Validate deployment spec
echo "Validating deployment spec for ${SERVICE_NAME}..."
kubectl get deployment/${SERVICE_NAME} -n ${NAMESPACE} -o jsonpath='{.spec.template.spec.containers[0].image}' && echo ""
echo "✓ Deployment spec validated"

kubectl rollout restart deployment/${SERVICE_NAME} -n ${NAMESPACE}
kubectl rollout status deployment/${SERVICE_NAME} -n ${NAMESPACE} --timeout=120s

echo "=== ✓ ${SERVICE_NAME} complete ==="
