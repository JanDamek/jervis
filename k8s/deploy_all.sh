#!/bin/bash
set -e

NAMESPACE="jervis"
K8S_DIR="$(dirname "$0")"

echo "=== Deploying Jervis to Kubernetes ==="

# 1. Create namespace
echo "Step 1/5: Creating namespace..."
kubectl apply -f "${K8S_DIR}/namespace.yaml"

# 2. Apply PVC
echo "Step 2/5: Creating PersistentVolumeClaim..."
kubectl apply -f "${K8S_DIR}/pvc.yaml"

# 3. Apply secrets (if exists)
echo "Step 3/5: Applying secrets..."
if [ -f "${K8S_DIR}/secrets.yaml" ]; then
    kubectl apply -f "${K8S_DIR}/secrets.yaml"
else
    echo "⚠ Warning: secrets.yaml not found, skipping..."
fi

# 4. Apply ConfigMaps
echo "Step 4/5: Applying ConfigMaps..."
kubectl apply -f "${K8S_DIR}/configmap.yaml"

# 5. Deploy all applications
echo "Step 5/5: Deploying applications..."
kubectl apply -f "${K8S_DIR}/app_tika.yaml"
kubectl apply -f "${K8S_DIR}/app_joern.yaml"
kubectl apply -f "${K8S_DIR}/app_whisper.yaml"
kubectl apply -f "${K8S_DIR}/app_atlassian.yaml"
kubectl apply -f "${K8S_DIR}/app_server.yaml"
kubectl apply -f "${K8S_DIR}/app_aider.yaml"
kubectl apply -f "${K8S_DIR}/app_coding_engine.yaml"
kubectl apply -f "${K8S_DIR}/app_junie.yaml"

# 6. Apply Ingress
echo "Step 6/6: Applying Ingress..."
kubectl apply -f "${K8S_DIR}/ingress.yaml"

echo ""
echo "=== Deployment complete! ==="
echo ""
kubectl get pods -n ${NAMESPACE}
echo ""
kubectl get svc -n ${NAMESPACE}
echo ""
kubectl get ingress -n ${NAMESPACE}
