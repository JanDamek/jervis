#!/usr/bin/env bash
# Shared validation functions for K8s deployments
# Source this file in build scripts: source "$SCRIPT_DIR/validate_deployment.sh"

validate_deployment_spec() {
  local SERVICE_NAME=$1
  local NAMESPACE=${2:-jervis}

  echo "Validating deployment spec for $SERVICE_NAME..."

  # Check for hostNetwork (should not be present or should be false)
  local HOST_NETWORK=$(kubectl get deployment/$SERVICE_NAME -n $NAMESPACE -o jsonpath='{.spec.template.spec.hostNetwork}' 2>/dev/null || echo "")
  if [[ "$HOST_NETWORK" == "true" ]]; then
    echo "✗ WARNING: hostNetwork=true still present in deployment (not in YAML)"
    echo "  Removing: kubectl patch deployment/$SERVICE_NAME -n $NAMESPACE --type=json -p='[{\"op\": \"remove\", \"path\": \"/spec/template/spec/hostNetwork\"}]'"
    kubectl patch deployment/$SERVICE_NAME -n $NAMESPACE --type=json -p='[{"op": "remove", "path": "/spec/template/spec/hostNetwork"}]' || echo "  (patch failed or field not found)"
  fi

  # Check for hostPort in container ports (should not be present)
  local CONTAINER_COUNT=$(kubectl get deployment/$SERVICE_NAME -n $NAMESPACE -o jsonpath='{.spec.template.spec.containers}' 2>/dev/null | jq '. | length' 2>/dev/null || echo "0")
  for ((i=0; i<$CONTAINER_COUNT; i++)); do
    local PORT_COUNT=$(kubectl get deployment/$SERVICE_NAME -n $NAMESPACE -o jsonpath="{.spec.template.spec.containers[$i].ports}" 2>/dev/null | jq '. | length' 2>/dev/null || echo "0")
    for ((j=0; j<$PORT_COUNT; j++)); do
      local HOST_PORT=$(kubectl get deployment/$SERVICE_NAME -n $NAMESPACE -o jsonpath="{.spec.template.spec.containers[$i].ports[$j].hostPort}" 2>/dev/null || echo "")
      if [[ -n "$HOST_PORT" ]]; then
        echo "✗ WARNING: hostPort=$HOST_PORT found in container[$i].ports[$j]"
        echo "  Removing: kubectl patch deployment/$SERVICE_NAME -n $NAMESPACE --type=json -p='[{\"op\": \"remove\", \"path\": \"/spec/template/spec/containers/$i/ports/$j/hostPort\"}]'"
        kubectl patch deployment/$SERVICE_NAME -n $NAMESPACE --type=json -p='[{"op": "remove", "path": "/spec/template/spec/containers/'$i'/ports/'$j'/hostPort"}]' || echo "  (patch failed or field not found)"
      fi
    done
  done

  echo "✓ Deployment spec validated"
}

validate_common_resources() {
  local NAMESPACE=${1:-jervis}

  echo "Validating common resources in namespace $NAMESPACE..."

  # Check if secrets exist
  if kubectl get secret jervis-secrets -n $NAMESPACE >/dev/null 2>&1; then
    echo "✓ Secret jervis-secrets exists"
  else
    echo "✗ WARNING: Secret jervis-secrets not found"
  fi

  # Check if regcred exists (for private registry)
  if kubectl get secret regcred -n $NAMESPACE >/dev/null 2>&1; then
    echo "✓ Secret regcred exists"
  else
    echo "✗ WARNING: Secret regcred not found (needed for private registry)"
  fi

  echo "✓ Common resources validated"
}
