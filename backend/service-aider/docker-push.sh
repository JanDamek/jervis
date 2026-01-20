#!/bin/bash
set -e

SERVICE_NAME="jervis-aider"
REGISTRY="registry.damek-soft.eu/jandamek"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "Pushing Docker image ${IMAGE}..."
docker push "${IMAGE}"

echo "✓ Docker image ${IMAGE} pushed successfully"
