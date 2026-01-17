#!/bin/bash
set -e

# Tento skript sestaví image pro server a nasadí ho do k8s.
# Předpokládá se, že JAR byl sestaven lokálně.

SERVICE_NAME="server"
IMAGE_NAME="jervis-server"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"

# Skript musí být spouštěn z kořene projektu.
if [ ! -f "settings.gradle.kts" ]; then
  cd ../..
fi

echo "===> Sestavuji JAR pro $SERVICE_NAME..."
./gradlew :backend:$SERVICE_NAME:clean :backend:$SERVICE_NAME:bootJar -x test

echo "===> Sestavuji image $IMAGE_NAME:latest..."
docker buildx build --platform linux/amd64 \
  -f "backend/$SERVICE_NAME/Dockerfile" \
  -t "$REGISTRY/$IMAGE_NAME:latest" \
  --push .

echo "===> Restartuji deployment $IMAGE_NAME v Kubernetes..."
kubectl rollout restart deployment "$IMAGE_NAME" -n "$NAMESPACE"

echo "===> Hotovo."
