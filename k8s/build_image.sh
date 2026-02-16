#!/bin/bash
set -e

# Build and push Docker image ONLY (no Gradle, no K8s deploy).
# Used for coding agents that run as K8s Jobs – no persistent Deployment.
#
# Usage: build_image.sh <image-name> <dockerfile-path> [gradle-module]
#   If gradle-module is provided, runs Gradle build first.

IMAGE_NAME=$1
DOCKERFILE=$2
GRADLE_MODULE=$3
REGISTRY="registry.damek-soft.eu/jandamek"

if [ -z "$IMAGE_NAME" ] || [ -z "$DOCKERFILE" ]; then
    echo "Usage: $0 <image-name> <dockerfile-path> [gradle-module]"
    exit 1
fi

IMAGE="${REGISTRY}/${IMAGE_NAME}:latest"

echo "=== Building image ${IMAGE_NAME} (Job-only, no K8s deploy) ==="

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# Optional Gradle build (for agents that still package a JAR)
if [ -n "$GRADLE_MODULE" ]; then
    echo "Step 1: Building JAR with Gradle..."
    ./gradlew ${GRADLE_MODULE}:clean ${GRADLE_MODULE}:build -x test
    echo "✓ JAR built"
fi

# Docker build
echo "Step 2: Building Docker image..."
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${DOCKERFILE}" "$PROJECT_ROOT"
echo "✓ Docker image built"

# Push with retry
push_with_retry() {
    local image=$1
    local max_attempts=3
    local attempt=1

    while [ $attempt -le $max_attempts ]; do
        echo "Pushing ${image} (attempt ${attempt}/${max_attempts})..."
        if docker push "${image}"; then
            echo "✓ Pushed ${image}"
            return 0
        else
            echo "⚠ Push failed (attempt ${attempt}/${max_attempts})"
            if [ $attempt -lt $max_attempts ]; then
                sleep 10
            fi
            attempt=$((attempt + 1))
        fi
    done
    echo "✗ Failed to push ${image} after ${max_attempts} attempts"
    return 1
}

echo "Step 3: Pushing Docker image..."
push_with_retry "${IMAGE}" || exit 1
echo "✓ Image pushed"

echo "=== ✓ ${IMAGE_NAME} image ready ==="
echo "  Orchestrator will spawn K8s Jobs from: ${IMAGE}"
