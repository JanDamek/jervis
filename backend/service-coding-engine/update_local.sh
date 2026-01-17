#!/bin/bash
set -e

# Tento skript sestaví image pro service-coding-engine a nasadí ho do k8s.
# Předpokládá se, že JAR byl sestaven lokálně.

SERVICE_DIR="service-coding-engine"
IMAGE_NAME="jervis-coding-engine"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"

# Skript musí být spouštěn z kořene projektu.
if [ ! -f "settings.gradle.kts" ]; then
  cd ../..
fi

echo "===> Sestavuji JAR pro $SERVICE_DIR..."
./gradlew :backend:$SERVICE_DIR:clean :backend:$SERVICE_DIR:jar -x test

echo "===> Sestavuji image $IMAGE_NAME:latest..."
docker buildx build --platform linux/amd64 \
  -f "backend/$SERVICE_DIR/Dockerfile" \
  -t "$REGISTRY/$IMAGE_NAME:latest" \
  --push .

echo "===> Restartuji deployment $IMAGE_NAME v Kubernetes..."
kubectl rollout restart deployment "$IMAGE_NAME" -n "$NAMESPACE"

echo "===> Hotovo."
