#!/bin/bash
set -e

NAMESPACE="jervis"
K8S_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Redeploying ALL Jervis services ==="
echo ""

SERVICES=(
    "server"
    "orchestrator"
    "knowledgebase"
    "mcp"
    "ollama_router"
    "document_extraction"
    "correction"
    "o365_gateway"
    "atlassian"
    "github"
    "gitlab"
    "visual_capture"
    "vnc_router"
    "whatsapp_browser"
)

for SERVICE in "${SERVICES[@]}"; do
    "${K8S_DIR}/redeploy_service.sh" "$SERVICE"
    echo ""
done

echo "=== ✓ All services redeployed successfully ==="
kubectl get deployments -n "${NAMESPACE}"
echo ""
kubectl get pods -n "${NAMESPACE}"
