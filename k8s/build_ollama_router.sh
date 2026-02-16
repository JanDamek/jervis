#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

REGISTRY="registry.damek-soft.eu/jandamek"
SERVICE_NAME="jervis-ollama-router"
IMAGE="$REGISTRY/$SERVICE_NAME:latest"

echo "=== Building and deploying $SERVICE_NAME (Python) ==="

# Step 1: Build Docker image
echo "Step 1/3: Building Docker image..."
cd "$PROJECT_ROOT/backend/service-ollama-router"
docker buildx build \
  --platform linux/amd64 \
  -t "$IMAGE" \
  -f Dockerfile \
  "$PROJECT_ROOT/backend/service-ollama-router" || { echo "✗ Docker build failed"; exit 1; }
echo "✓ Docker image built"

# Step 2: Push Docker image
echo "Step 2/3: Pushing Docker image..."
docker push "$IMAGE" || { echo "✗ Docker push failed"; exit 1; }
echo "✓ Docker image pushed"

# Step 3: Deploy to Kubernetes
echo "Step 3/3: Deploying to Kubernetes..."
cd "$PROJECT_ROOT/k8s"
kubectl apply -f app_ollama_router.yaml

# Validate YAML changes propagated to K8s
validate_deployment_spec "$SERVICE_NAME" "jervis"

kubectl rollout restart deployment/$SERVICE_NAME -n jervis
kubectl rollout status deployment/$SERVICE_NAME -n jervis --timeout=300s

echo "=== ✓ $SERVICE_NAME complete ==="
