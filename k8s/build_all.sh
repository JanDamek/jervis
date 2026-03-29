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
"${K8S_DIR}/build_server.sh"
echo ""

"${K8S_DIR}/build_orchestrator.sh"
echo ""

"${K8S_DIR}/build_kb.sh"
echo ""

"${K8S_DIR}/build_mcp.sh"
echo ""

"${K8S_DIR}/build_ollama_router.sh"
echo ""

"${K8S_DIR}/build_docext.sh"
echo ""

"${K8S_DIR}/build_correction.sh"
echo ""

"${K8S_DIR}/build_o365_gateway.sh"
echo ""

"${K8S_DIR}/build_o365_browser_pool.sh"
echo ""

"${K8S_DIR}/build_atlassian.sh"
echo ""

"${K8S_DIR}/build_github.sh"
echo ""

"${K8S_DIR}/build_gitlab.sh"
echo ""

"${K8S_DIR}/build_coding_engine.sh"
echo ""

"${K8S_DIR}/build_claude.sh"
echo ""

# Build Job-only images (no persistent Deployment)
"${K8S_DIR}/build_joern.sh"
echo ""

# TTS is deployed as K8s Deployment
"${K8S_DIR}/build_tts.sh"
echo ""

# Note: Whisper runs on GPU VM (ollama.lan.mazlusek.com), deployed via deploy_whisper_gpu.sh

echo ""
echo "=== ✓ All services built and deployed successfully ==="
echo ""
kubectl get deployments -n jervis
echo ""
kubectl get pods -n jervis
