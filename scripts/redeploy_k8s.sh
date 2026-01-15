#!/bin/bash
set -e

NAMESPACE="jervis"
K8S_DIR="k8s"

echo "===> Re-deploying entire k8s cluster for namespace: $NAMESPACE"

# 1. Apply Namespace
echo "===> Applying namespace..."
kubectl apply -f "$K8S_DIR/namespace.yaml"

# 2. Apply Secrets and ConfigMaps
echo "===> Applying secrets and configmaps..."
kubectl apply -f "$K8S_DIR/secrets.yaml"
kubectl apply -f "$K8S_DIR/configmap.yaml"

# 3. Apply PVC
echo "===> Applying PVC..."
kubectl apply -f "$K8S_DIR/pvc.yaml"

# 4. Apply Applications (Deployments and Services)
echo "===> Applying applications..."
kubectl apply -f "$K8S_DIR/apps.yaml"

# 5. Apply Ingress
echo "===> Applying ingress..."
kubectl apply -f "$K8S_DIR/ingress.yaml"

echo "===> All resources applied. Waiting for rollouts..."

# 6. Wait for deployments to be ready (optional but helpful)
DEPLOYMENTS=$(kubectl get deployments -n "$NAMESPACE" -o name)
for deploy in $DEPLOYMENTS; do
    echo "Waiting for $deploy..."
    kubectl rollout status "$deploy" -n "$NAMESPACE" --timeout=60s || echo "Warning: $deploy rollout timed out"
done

echo "===> Re-deployment complete."
