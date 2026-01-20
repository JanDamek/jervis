#!/bin/bash
set -e

SERVICE_NAME="jervis-aider"
REGISTRY="registry.damek-soft.eu/jandamek"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "Building Docker image for ${SERVICE_NAME}..."
cd "$(dirname "$0")/../.."

docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f backend/service-aider/Dockerfile .

echo "✓ Docker image ${IMAGE} built successfully"
