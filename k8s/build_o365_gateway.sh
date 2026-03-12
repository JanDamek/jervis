#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SERVICE_NAME="jervis-o365-gateway"
MODULE="backend:service-o365-gateway"
DOCKERFILE="backend/service-o365-gateway/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (Kotlin) ==="

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Gradle Build
echo "Step 1/4: Building with Gradle..."
cd "$PROJECT_ROOT"
./gradlew ${MODULE}:clean ${MODULE}:jar -x test
echo "✓ Gradle build complete"

# Docker Build
echo "Step 2/4: Building Docker image..."
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${DOCKERFILE}" "$PROJECT_ROOT"
echo "✓ Docker image built"

# Docker Push
echo "Step 3/4: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

# Deploy to Kubernetes
echo "Step 4/4: Deploying to Kubernetes..."
kubectl apply -f "$SCRIPT_DIR/app_o365_gateway.yaml" -n "${NAMESPACE}"
kubectl rollout restart deployment/jervis-o365-gateway -n ${NAMESPACE}
kubectl rollout status deployment/jervis-o365-gateway -n ${NAMESPACE}

echo "=== ✓ ${SERVICE_NAME} deployed ==="
