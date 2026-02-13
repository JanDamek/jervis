#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$SCRIPT_DIR"

# Source shared validation functions
source "$SCRIPT_DIR/validate_deployment.sh"

echo "=== Building and deploying all Jervis services ==="
echo ""

# Validate common resources before deploying services
validate_common_resources "jervis"
echo ""

# Build persistent services (Deployments)
"${K8S_DIR}/build_tika.sh"
echo ""

"${K8S_DIR}/build_atlassian.sh"
echo ""

"${K8S_DIR}/build_aider.sh"
echo ""

"${K8S_DIR}/build_coding_engine.sh"
echo ""

"${K8S_DIR}/build_junie.sh"
echo ""

"${K8S_DIR}/build_github.sh"
echo ""

"${K8S_DIR}/build_gitlab.sh"
echo ""

"${K8S_DIR}/build_server.sh"
echo ""

"${K8S_DIR}/build_kb.sh"
echo ""

"${K8S_DIR}/build_orchestrator.sh"
echo ""

"${K8S_DIR}/build_claude.sh"
echo ""

# Note: qualification service removed - SimpleQualifierAgent calls KB directly

# Build Job-only images (no persistent Deployment)
"${K8S_DIR}/build_joern.sh"
echo ""

"${K8S_DIR}/build_whisper.sh"
echo ""

"${K8S_DIR}/build_whisper_rest.sh"
echo ""

echo ""
echo "=== ✓ All services built and deployed successfully ==="
echo ""
kubectl get deployments -n jervis
echo ""
kubectl get pods -n jervis
