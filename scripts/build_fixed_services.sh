#!/bin/bash
set -e

echo "======================================================================"
echo "Build a deploy skript pro opravené služby (A2A health check fix)"
echo "======================================================================"
echo ""
echo "Tento skript byl vytvořen Claude Code po opravě health check problémů"
echo "v A2A službách (aider, coding-engine, junie) a server modulech."
echo ""
echo "Změny:"
echo "- Health server se nyní spouští PRVNÍ (před hlavním A2A serverem)"
echo "- Přidány importy LLModel do orchestrator tříd"
echo "- Změněno model.provider.name na 'OLLAMA'"
echo ""
echo "======================================================================"
echo ""

# Služby které potřebují rebuild
MODULES=(
  "server:backend/server"
  "aider:backend/service-aider"
  "coding-engine:backend/service-coding-engine"
  "junie:backend/service-junie"
)

REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"

echo "Krok 1: Build všech JAR souborů"
echo "----------------------------------------------------------------------"
./gradlew :backend:server:build :backend:service-aider:build :backend:service-coding-engine:build :backend:service-junie:build -x test --parallel
echo ""

echo "Krok 2: Build a push Docker images"
echo "----------------------------------------------------------------------"
for module in "${MODULES[@]}"; do
  IFS=":" read -r name path <<< "$module"

  echo ">>> Building and pushing jervis-$name..."

  docker buildx build \
    --platform linux/amd64 \
    -f "$path/Dockerfile" \
    -t "$REGISTRY/jervis-$name:latest" \
    --push .

  echo ">>> Done: jervis-$name"
  echo ""
done

echo "Krok 3: Restart deploymentů v k8s"
echo "----------------------------------------------------------------------"
for module in "${MODULES[@]}"; do
  IFS=":" read -r name path <<< "$module"

  echo ">>> Restarting deployment jervis-$name..."
  kubectl rollout restart deployment "jervis-$name" -n "$NAMESPACE"
  echo ""
done

echo "======================================================================"
echo "Hotovo! Sleduj status:"
echo "  kubectl get pods -n jervis -w"
echo ""
echo "Zkontroluj logy:"
echo "  kubectl logs -n jervis -l app=jervis-aider --tail=50"
echo "  kubectl logs -n jervis -l app=jervis-coding-engine --tail=50"
echo "  kubectl logs -n jervis -l app=jervis-junie --tail=50"
echo "  kubectl logs -n jervis -l app=jervis-server --tail=50"
echo "======================================================================"
