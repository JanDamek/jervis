#!/bin/bash
set -e

K8S_DIR="/Users/damekjan/git/jervis/k8s"

echo "=== Building and deploying all Jervis services ==="
echo ""

# Build all services
"${K8S_DIR}/build_tika.sh"
echo ""

"${K8S_DIR}/build_joern.sh"
echo ""

"${K8S_DIR}/build_whisper.sh"
echo ""

"${K8S_DIR}/build_atlassian.sh"
echo ""

"${K8S_DIR}/build_aider.sh"
echo ""

"${K8S_DIR}/build_coding_engine.sh"
echo ""

"${K8S_DIR}/build_junie.sh"
echo ""

"${K8S_DIR}/build_server.sh"
echo ""

echo "=== ✓ All services built and deployed successfully ==="
echo ""
kubectl get deployments -n jervis
echo ""
kubectl get pods -n jervis
