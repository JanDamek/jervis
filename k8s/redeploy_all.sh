#!/bin/bash
set -e

NAMESPACE="jervis"
K8S_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Redeploying ALL Jervis services ==="
echo ""

SERVICES=("tika" "joern" "whisper" "atlassian" "server" "aider" "coding_engine" "junie" "github" "gitlab")

for SERVICE in "${SERVICES[@]}"; do
    "${K8S_DIR}/redeploy_service.sh" "$SERVICE"
    echo ""
done

echo "=== ✓ All services redeployed successfully ==="
kubectl get deployments -n "${NAMESPACE}"
echo ""
kubectl get pods -n "${NAMESPACE}"
