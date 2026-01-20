#!/bin/bash
set -e

SERVICE_NAME="jervis-coding-engine"
REGISTRY="registry.damek-soft.eu/jandamek"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "Pushing Docker image ${IMAGE}..."
docker push "${IMAGE}"

echo "✓ Docker image ${IMAGE} pushed successfully"
