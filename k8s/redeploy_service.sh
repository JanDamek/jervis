#!/bin/bash
set -e

NAMESPACE="jervis"
K8S_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Redeploying Jervis service: $1 ==="

SERVICE_NAME=$1

if [ -z "$SERVICE_NAME" ]; then
    echo "Usage: $0 <service-name>"
    echo "Example: $0 tika"
    exit 1
fi

# Map service name to yaml file if needed
# (tika -> app_tika.yaml, server -> app_server.yaml, etc.)
YAML_FILE="${K8S_DIR}/app_${SERVICE_NAME}.yaml"

if [ ! -f "$YAML_FILE" ]; then
    echo "Error: YAML file not found: $YAML_FILE"
    exit 1
fi

# Determine deployment name (usually jervis-<service_name>)
# but for server it might be jervis-server
# Replace underscores with dashes for deployment name
DEPLOY_SUFFIX=${SERVICE_NAME//_/-}
DEPLOYMENT_NAME="jervis-${DEPLOY_SUFFIX}"

echo "Applying $YAML_FILE..."
kubectl apply -f "$YAML_FILE" -n "${NAMESPACE}"

echo "Restarting deployment ${DEPLOYMENT_NAME}..."
kubectl rollout restart deployment/${DEPLOYMENT_NAME} -n "${NAMESPACE}"

kubectl get pods -n "${NAMESPACE}" -l "app=${DEPLOYMENT_NAME}"
echo "✓ ${DEPLOYMENT_NAME} redeployed"
