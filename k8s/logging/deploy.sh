#!/usr/bin/env bash
# =============================================================================
# Deploy EFK logging stack (Elasticsearch + Fluent Bit + Kibana)
#
# Components:
#   - Elasticsearch 7.17 (single-node, NFS 20Gi, 2Gi RAM)
#   - Fluent Bit DaemonSet (collects logs from ALL pods in ALL namespaces)
#   - Kibana (web UI at kibana.damek-soft.eu)
#   - CronJob for 7-day log retention
# =============================================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Deploying EFK logging stack ==="

# 1. Namespace
echo "--- Creating namespace..."
kubectl apply -f "$SCRIPT_DIR/namespace.yaml"

# 2. Elasticsearch (must be up before Fluent Bit can send logs)
echo "--- Deploying Elasticsearch..."
kubectl apply -f "$SCRIPT_DIR/elasticsearch.yaml"

# 3. Wait for Elasticsearch to be ready
echo "--- Waiting for Elasticsearch..."
kubectl rollout status deployment/elasticsearch -n logging --timeout=180s || {
    echo "WARNING: Elasticsearch not ready yet, continuing..."
}

# 4. Kibana
echo "--- Deploying Kibana..."
kubectl apply -f "$SCRIPT_DIR/kibana.yaml"

# 5. Fluent Bit (DaemonSet — RBAC + ConfigMap + DaemonSet)
echo "--- Deploying Fluent Bit..."
kubectl apply -f "$SCRIPT_DIR/fluent-bit.yaml"

# 6. Fluent Bit aggregator (UDP/514 syslog from UniFi devices, TCP/24224 forward from RPi sidecar)
echo "--- Deploying Fluent Bit aggregator..."
kubectl apply -f "$SCRIPT_DIR/unifi-aggregator.yaml"

# 7. Log retention CronJob
echo "--- Deploying log retention CronJob..."
kubectl apply -f "$SCRIPT_DIR/retention-cronjob.yaml"

# 8. Ingress
echo "--- Deploying Kibana ingress..."
kubectl apply -f "$SCRIPT_DIR/ingress.yaml"

echo ""
echo "=== EFK logging stack deployed ==="
echo "  Elasticsearch: elasticsearch.logging.svc.cluster.local:9200"
echo "  Kibana:        kibana.lan.mazlusek.com"
echo "  Fluent Bit:    DaemonSet on all nodes (k8s-logs-*)"
echo "  Aggregator:    UDP/514 syslog + TCP/24224 forward (unifi-syslog-*, unifi-controller-*)"
echo "  Retention:     7 days (CronJob at 02:00 daily, all *-logs / unifi-* indices)"
echo ""
echo "Check status:"
echo "  kubectl get all -n logging"
echo "  kubectl get svc -n logging fluent-bit-syslog fluent-bit-forward  # MetalLB EXTERNAL-IP"
echo "  kubectl logs -n logging -l app=fluent-bit-aggregator --tail=20"
