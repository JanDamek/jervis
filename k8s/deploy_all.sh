#!/bin/bash
set -e

NAMESPACE="jervis"
K8S_DIR="/Users/damekjan/git/jervis/k8s"

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
"${K8S_DIR}/redeploy_all.sh"

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
