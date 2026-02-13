#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

# Config
SERVICE_NAME="jervis-server"
MODULE=":backend:server"
DOCKERFILE="backend/server/Dockerfile"
REGISTRY="registry.damek-soft.eu/jandamek"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"
NAMESPACE="jervis"

echo "=== Building and deploying ${SERVICE_NAME} ==="

# 1. Build JAR
echo "Step 1/4: Building JAR..."
cd "$(dirname "$0")/.."
./gradlew ${MODULE}:clean ${MODULE}:build -x test
echo "✓ JAR built successfully"

# 2. Docker Build
echo "Step 2/4: Building Docker image..."
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${DOCKERFILE}" .
echo "✓ Docker image ${IMAGE} built successfully"

# 3. Docker Push
echo "Step 3/4: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

# 4. Deploy
echo "Step 4/4: Deploying to Kubernetes..."
kubectl apply -f "$SCRIPT_DIR/app_server.yaml" -n "${NAMESPACE}"

# Validate YAML changes propagated to K8s
validate_deployment_spec "$SERVICE_NAME" "$NAMESPACE"

kubectl rollout restart deployment/${SERVICE_NAME} -n "${NAMESPACE}"
kubectl get pods -n "${NAMESPACE}" -l "app=${SERVICE_NAME}"
echo "✓ ${SERVICE_NAME} deployment triggered"
echo "=== ✓ ${SERVICE_NAME} complete ==="
