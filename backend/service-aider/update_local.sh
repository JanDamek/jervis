#!/bin/bash
set -e

# Tento skript sestaví image pro service-aider a nasadí ho do k8s.
# Dockerfile nyní obsahuje i gradle build modulu.

SERVICE_DIR="service-aider"
IMAGE_NAME="jervis-aider"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"

# Skript musí být spouštěn z kořene projektu.
if [ ! -f "settings.gradle.kts" ]; then
  cd ../..
fi

echo "===> Sestavuji image $IMAGE_NAME:latest (včetně Gradle buildu)..."
docker buildx build --platform linux/amd64 \
  -f "backend/$SERVICE_DIR/Dockerfile" \
  -t "$REGISTRY/$IMAGE_NAME:latest" \
  --push .

echo "===> Restartuji deployment $IMAGE_NAME v Kubernetes..."
kubectl rollout restart deployment "$IMAGE_NAME" -n "$NAMESPACE"

echo "===> Hotovo."
