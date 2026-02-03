#!/bin/bash
set -e

# Config
SERVICE_NAME=$1
MODULE=$2
DOCKERFILE=$3
DEPLOY_NAME=${4:-$SERVICE_NAME}
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"

if [ -z "$SERVICE_NAME" ] || [ -z "$MODULE" ] || [ -z "$DOCKERFILE" ]; then
    echo "Usage: $0 <service-name> <gradle-module> <dockerfile-path> [deploy-name]"
    exit 1
fi

# Generate version tag from git commit hash + timestamp
GIT_HASH=$(git rev-parse --short HEAD 2>/dev/null || echo "local")
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
VERSION_TAG="${GIT_HASH}-${TIMESTAMP}"

IMAGE_VERSIONED="${REGISTRY}/${SERVICE_NAME}:${VERSION_TAG}"
IMAGE_LATEST="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (deploy as ${DEPLOY_NAME}) ==="
echo "Version: ${VERSION_TAG}"

# 1. Gradle Build (local build with cache for speed)
echo "Step 1/4: Building JAR with Gradle..."
# Get project root - assume we are in <root>/k8s
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
./gradlew ${MODULE}:clean ${MODULE}:build -x test
echo "✓ JAR built successfully"

# 2. Docker Build (copies pre-built JAR)
echo "Step 2/4: Building Docker image..."
docker build --platform linux/amd64 \
  -t "${IMAGE_VERSIONED}" \
  -t "${IMAGE_LATEST}" \
  -f "${DOCKERFILE}" "$PROJECT_ROOT"
echo "✓ Docker images built: ${VERSION_TAG} and latest"

# 3. Docker Push (both tags) with retry
echo "Step 3/4: Pushing Docker images..."

# Function to push with retry
push_with_retry() {
    local image=$1
    local max_attempts=3
    local attempt=1

    while [ $attempt -le $max_attempts ]; do
        echo "Pushing ${image} (attempt ${attempt}/${max_attempts})..."
        if docker push "${image}"; then
            echo "✓ Successfully pushed ${image}"
            return 0
        else
            echo "⚠ Push failed (attempt ${attempt}/${max_attempts})"
            if [ $attempt -lt $max_attempts ]; then
                echo "Retrying in 10 seconds..."
                sleep 10
            fi
            attempt=$((attempt + 1))
        fi
    done

    echo "✗ Failed to push ${image} after ${max_attempts} attempts"
    return 1
}

push_with_retry "${IMAGE_VERSIONED}" || exit 1
push_with_retry "${IMAGE_LATEST}" || exit 1
echo "✓ Docker images pushed"

# 4. Deploy to Kubernetes
echo "Step 4/4: Deploying to Kubernetes..."
# Fix for service names like jervis-coding-engine -> app_coding_engine.yaml
CLEAN_NAME=${DEPLOY_NAME#jervis-}
CLEAN_NAME=${CLEAN_NAME//-/_}
YAML_FILE="/Users/damekjan/git/jervis/k8s/app_${CLEAN_NAME}.yaml"

# Apply deployment - kubectl will decide if update is needed
kubectl apply -f "$YAML_FILE" -n "${NAMESPACE}"
kubectl rollout restart deployment/${DEPLOY_NAME} -n "${NAMESPACE}"
kubectl get pods -n "${NAMESPACE}" -l "app=${DEPLOY_NAME}"
echo "✓ ${DEPLOY_NAME} deployment applied"
echo "=== ✓ ${SERVICE_NAME} complete (version: ${VERSION_TAG}) ==="