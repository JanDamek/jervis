#!/bin/bash
set -e

# Config
SERVICE_NAME=$1
MODULE=$2
DOCKERFILE=$3
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"

if [ -z "$SERVICE_NAME" ] || [ -z "$MODULE" ] || [ -z "$DOCKERFILE" ]; then
    echo "Usage: $0 <service-name> <gradle-module> <dockerfile-path>"
    exit 1
fi

# Generate version tag from git commit hash + timestamp
GIT_HASH=$(git rev-parse --short HEAD 2>/dev/null || echo "local")
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
VERSION_TAG="${GIT_HASH}-${TIMESTAMP}"

IMAGE_VERSIONED="${REGISTRY}/${SERVICE_NAME}:${VERSION_TAG}"
IMAGE_LATEST="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} ==="
echo "Version: ${VERSION_TAG}"

# 1. Gradle Build (local build with cache for speed)
echo "Step 1/4: Building JAR with Gradle..."
cd "$(dirname "$0")/.."
./gradlew ${MODULE}:clean ${MODULE}:build -x test
echo "✓ JAR built successfully"

# 2. Docker Build (copies pre-built JAR)
echo "Step 2/4: Building Docker image..."
docker build --platform linux/amd64 \
  -t "${IMAGE_VERSIONED}" \
  -t "${IMAGE_LATEST}" \
  -f "${DOCKERFILE}" .
echo "✓ Docker images built: ${VERSION_TAG} and latest"

# 3. Docker Push (both tags)
echo "Step 3/4: Pushing Docker images..."
docker push "${IMAGE_VERSIONED}"
docker push "${IMAGE_LATEST}"
echo "✓ Docker images pushed"

# 4. Deploy to Kubernetes
echo "Step 4/4: Deploying to Kubernetes..."
YAML_FILE="k8s/app_${SERVICE_NAME#jervis-}.yaml"

# Apply deployment - kubectl will decide if update is needed
kubectl apply -f "$YAML_FILE" -n "${NAMESPACE}"
kubectl get pods -n "${NAMESPACE}" -l "app=${SERVICE_NAME}"
echo "✓ ${SERVICE_NAME} deployment applied"
echo "=== ✓ ${SERVICE_NAME} complete (version: ${VERSION_TAG}) ==="
