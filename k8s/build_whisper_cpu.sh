#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/validate_deployment.sh"

SERVICE_NAME="jervis-whisper-cpu"
DOCKERFILE="backend/service-whisper/Dockerfile.cpu"
REGISTRY="registry.damek-soft.eu/jandamek"
NAMESPACE="jervis"
IMAGE="${REGISTRY}/${SERVICE_NAME}:latest"

echo "=== Building and deploying ${SERVICE_NAME} (CPU Whisper) ==="

# Docker Build
echo "Step 1/3: Building Docker image..."
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker build --platform linux/amd64 \
  -t "${IMAGE}" \
  -f "${PROJECT_ROOT}/${DOCKERFILE}" "${PROJECT_ROOT}"

echo "✓ Docker image built"

# Docker Push
echo "Step 2/3: Pushing Docker image..."
docker push "${IMAGE}"
echo "✓ Docker image pushed"

# Deploy to Kubernetes
echo "Step 3/3: Deploying to Kubernetes..."
cat <<EOF | kubectl apply -n "${NAMESPACE}" -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${SERVICE_NAME}
  labels:
    app: ${SERVICE_NAME}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ${SERVICE_NAME}
  template:
    metadata:
      labels:
        app: ${SERVICE_NAME}
    spec:
      containers:
        - name: whisper-cpu
          image: ${IMAGE}
          imagePullPolicy: Always
          ports:
            - containerPort: 8786
          env:
            - name: WHISPER_DEVICE
              value: "cpu"
            - name: WHISPER_COMPUTE_TYPE
              value: "int8"
            - name: WHISPER_DEFAULT_MODEL
              value: "medium"
          resources:
            requests:
              cpu: "1"
              memory: "2Gi"
            limits:
              cpu: "4"
              memory: "4Gi"
          readinessProbe:
            httpGet:
              path: /health
              port: 8786
            initialDelaySeconds: 60
            periodSeconds: 30
          livenessProbe:
            httpGet:
              path: /health
              port: 8786
            initialDelaySeconds: 90
            periodSeconds: 60
---
apiVersion: v1
kind: Service
metadata:
  name: ${SERVICE_NAME}
spec:
  selector:
    app: ${SERVICE_NAME}
  ports:
    - port: 8786
      targetPort: 8786
  type: ClusterIP
EOF

validate_deployment_spec "${SERVICE_NAME}" "${NAMESPACE}"

kubectl rollout restart deployment/${SERVICE_NAME} -n ${NAMESPACE}
kubectl rollout status deployment/${SERVICE_NAME} -n ${NAMESPACE} --timeout=180s

echo "=== ✓ ${SERVICE_NAME} complete ==="
