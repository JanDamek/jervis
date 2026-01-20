#!/bin/bash
set -e

SERVICE_NAME="jervis-coding-engine"
NAMESPACE="jervis"

echo "Deploying ${SERVICE_NAME} to Kubernetes..."

# Delete pods to force pull new image
kubectl delete pod -n "${NAMESPACE}" -l "app=${SERVICE_NAME}" --ignore-not-found=true

# Wait for new pod to be created
echo "Waiting for new pod to start..."
sleep 5

# Check pod status
kubectl get pods -n "${NAMESPACE}" -l "app=${SERVICE_NAME}"

echo "✓ ${SERVICE_NAME} deployment triggered"
echo "Monitor with: kubectl logs -n ${NAMESPACE} -l app=${SERVICE_NAME} -f"
