#!/bin/bash
set -e

SERVICE_NAME="jervis-junie"
REGISTRY="registry.damek-soft.eu/jandamek"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "Building Docker image for ${SERVICE_NAME}..."
cd "$(dirname "$0")/../.."

docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f backend/service-junie/Dockerfile .

echo "✓ Docker image ${IMAGE} built successfully"
