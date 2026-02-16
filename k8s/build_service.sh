#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

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

IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (deploy as ${DEPLOY_NAME}) ==="

# 1. Gradle Build
echo "Step 1/4: Building JAR with Gradle..."
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
./gradlew ${MODULE}:clean ${MODULE}:build -x test
echo "✓ JAR built successfully"

# 2. Docker Build
echo "Step 2/4: Building Docker image..."
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${DOCKERFILE}" "$PROJECT_ROOT"
echo "✓ Docker image built"

# 3. Docker Push with retry
echo "Step 3/4: Pushing Docker image..."

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

push_with_retry "${IMAGE}" || exit 1
echo "✓ Docker image pushed"

# 4. Deploy to Kubernetes
echo "Step 4/4: Deploying to Kubernetes..."
CLEAN_NAME=${DEPLOY_NAME#jervis-}
CLEAN_NAME=${CLEAN_NAME//-/_}
YAML_FILE="$SCRIPT_DIR/app_${CLEAN_NAME}.yaml"

kubectl apply -f "$YAML_FILE" -n "${NAMESPACE}"

# Validate YAML changes propagated to K8s
validate_deployment_spec "$DEPLOY_NAME" "$NAMESPACE"

kubectl rollout restart deployment/${DEPLOY_NAME} -n "${NAMESPACE}"
kubectl rollout status deployment/${DEPLOY_NAME} -n "${NAMESPACE}"
echo "✓ ${DEPLOY_NAME} deployment applied"
echo "=== ✓ ${SERVICE_NAME} complete ==="
