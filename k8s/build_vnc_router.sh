#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Building VNC Router ==="

docker build --platform linux/amd64 \
  -t registry.damek-soft.eu/jandamek/jervis-vnc-router:latest \
  -f "$ROOT_DIR/backend/service-vnc-router/Dockerfile" \
  "$ROOT_DIR/backend/service-vnc-router"

echo "=== Pushing VNC Router ==="
docker push registry.damek-soft.eu/jandamek/jervis-vnc-router:latest

echo "=== Deploying VNC Router ==="
kubectl apply -f "$SCRIPT_DIR/app_vnc_router.yaml"
kubectl rollout restart deployment/jervis-vnc-router -n jervis
kubectl rollout status deployment/jervis-vnc-router -n jervis --timeout=120s

echo "=== Updating VNC Ingress ==="
kubectl apply -f "$SCRIPT_DIR/ingress_novnc.yaml"

echo "=== VNC Router deployed ==="
