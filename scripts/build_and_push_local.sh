#!/bin/bash
set -e

# Tento skript sestaví a nasadí všechny obrazy do registru.
# Sestavení probíhá uvnitř Dockeru (včetně Gradle buildu).

MODULES=(
  "server:backend/server"
  "joern:backend/service-joern"
  "whisper:backend/service-whisper"
  "tika:backend/service-tika"
  "aider:backend/service-aider"
  "junie:backend/service-junie"
  "coding-engine:backend/service-coding-engine"
  "atlassian:backend/service-atlassian"
)

REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"

for module in "${MODULES[@]}"; do
  IFS=":" read -r name path <<< "$module"
  
  echo "===> Sestavuji a odesílám jervis-$name z $path/Dockerfile..."
  
  docker buildx build \
    --platform linux/amd64 \
    -f "$path/Dockerfile" \
    -t "$REGISTRY/jervis-$name:latest" \
    --push .

  echo "===> Restartuji deployment jervis-$name v k8s..."
  kubectl rollout restart deployment "jervis-$name" -n "$NAMESPACE"
done

echo "===> Všechny služby byly aktualizovány."
