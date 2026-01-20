#!/bin/bash
set -e

echo "======================================================================"
echo "Build a deploy script pro opravu A2A Agent Card"
echo "======================================================================"
echo ""
echo "Opravy:"
echo "- Agent card path v serverech změněn z '/a2a/.well-known/agent-card.json' na '/.well-known/agent-card.json'"
echo "- UrlAgentCardResolver v klientovi používá prázdný path"
echo "- Agent card je nyní dostupný na rootu (http://service/.well-known/agent-card.json)"
echo "- A2A endpoint zůstává na /a2a"
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
echo "Zkontroluj agent card endpoints:"
echo "  kubectl exec -n jervis deploy/jervis-aider -- curl -s http://localhost:3100/.well-known/agent-card.json"
echo "  kubectl exec -n jervis deploy/jervis-junie -- curl -s http://localhost:3300/.well-known/agent-card.json"
echo "  kubectl exec -n jervis deploy/jervis-coding-engine -- curl -s http://localhost:3200/.well-known/agent-card.json"
echo ""
echo "Zkontroluj logy:"
echo "  kubectl logs -n jervis -l app=jervis-server --tail=50 | grep -E 'A2A|agent-card'"
echo "======================================================================"
