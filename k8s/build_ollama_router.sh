#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

REGISTRY="registry.damek-soft.eu/jandamek"
SERVICE_NAME="jervis-ollama-router"
VERSION=$(git rev-parse --short HEAD)-$(date +%Y%m%d-%H%M%S)

echo "=== Building and deploying $SERVICE_NAME (Python) ==="
echo "Version: $VERSION"

# Step 1: Build Docker image
echo "Step 1/3: Building Docker image..."
cd "$PROJECT_ROOT/backend/service-ollama-router"
docker buildx build \
  --platform linux/amd64 \
  -t "$REGISTRY/$SERVICE_NAME:$VERSION" \
  -t "$REGISTRY/$SERVICE_NAME:latest" \
  -f Dockerfile \
  "$PROJECT_ROOT/backend/service-ollama-router" || { echo "✗ Docker build failed"; exit 1; }
echo "✓ Docker images built"

# Step 2: Push Docker images
echo "Step 2/3: Pushing Docker images..."
docker push "$REGISTRY/$SERVICE_NAME:$VERSION" || { echo "✗ Docker push failed"; exit 1; }
docker push "$REGISTRY/$SERVICE_NAME:latest" || { echo "✗ Docker push failed"; exit 1; }
echo "✓ Docker images pushed"

# Step 3: Deploy to Kubernetes
echo "Step 3/3: Deploying to Kubernetes..."
cd "$PROJECT_ROOT/k8s"
kubectl apply -f app_ollama_router.yaml
kubectl set image deployment/$SERVICE_NAME router="$REGISTRY/$SERVICE_NAME:$VERSION" -n jervis
kubectl rollout status deployment/$SERVICE_NAME -n jervis --timeout=300s

echo "=== ✓ $SERVICE_NAME complete ==="
