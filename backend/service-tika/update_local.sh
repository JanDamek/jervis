#!/bin/bash
set -e

# Tento skript sestaví image pro service-tika a nasadí ho do k8s.
# Dockerfile nyní obsahuje i gradle build modulu.

SERVICE_DIR="service-tika"
IMAGE_NAME="jervis-tika"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"

echo "===> Sestavuji image $IMAGE_NAME:latest (včetně Gradle buildu)..."
# Skript musí být spouštěn z kořene projektu.
if [ ! -f "settings.gradle.kts" ]; then
  cd ../..
fi

docker buildx build --platform linux/amd64 \
  -f "backend/$SERVICE_DIR/Dockerfile" \
  -t "$REGISTRY/$IMAGE_NAME:latest" \
  --push .

echo "===> Restartuji deployment $IMAGE_NAME v Kubernetes..."
kubectl rollout restart deployment "$IMAGE_NAME" -n "$NAMESPACE"

echo "===> Hotovo."
