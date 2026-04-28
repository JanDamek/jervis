#!/usr/bin/env bash
set -e

# =============================================================================
# Deploy filebeat config to the VD GPU VM (ollama.lan.mazlusek.com).
#
# Source of truth: k8s/logging/vd-filebeat.yml in this repo.
# This script ships the file to /etc/filebeat/filebeat.yml on the VD and
# restarts the filebeat systemd unit. Old config is backed up to
# /etc/filebeat/filebeat.yml.bak.<timestamp> in case of rollback.
#
# Why we ship via SSH instead of a K8s ConfigMap: filebeat runs on the
# host, not in the cluster — there is no kubelet/kubectl access on the VD.
#
# Usage:
#   ./deploy_vd_filebeat.sh                   # uses ~/.ssh/id_starkys
#   GPU_USER=other ./deploy_vd_filebeat.sh    # override SSH user
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_SRC="${SCRIPT_DIR}/logging/vd-filebeat.yml"

GPU_HOST="${GPU_HOST:-ollama.lan.mazlusek.com}"
GPU_USER="${GPU_USER:-damekjan}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_starkys}"

if [ ! -f "$CONFIG_SRC" ]; then
    echo "FATAL: config source not found: $CONFIG_SRC"
    exit 1
fi

ssh_cmd() {
    ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$GPU_USER@$GPU_HOST" "$@"
}

echo "=== Deploying filebeat config to $GPU_HOST ==="

# Step 1/4: ship config to /tmp on VD (no sudo needed for scp into home).
echo "Step 1/4: scp config to /tmp/vd-filebeat.yml..."
scp -i "$SSH_KEY" -o StrictHostKeyChecking=no \
    "$CONFIG_SRC" "$GPU_USER@$GPU_HOST:/tmp/vd-filebeat.yml"

# Step 2/4: backup current config + move new one in place.
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
echo "Step 2/4: Backup current config + install new..."
ssh_cmd "sudo cp /etc/filebeat/filebeat.yml /etc/filebeat/filebeat.yml.bak.${TIMESTAMP} 2>/dev/null || true"
ssh_cmd "sudo mv /tmp/vd-filebeat.yml /etc/filebeat/filebeat.yml"
ssh_cmd "sudo chown root:root /etc/filebeat/filebeat.yml && sudo chmod 600 /etc/filebeat/filebeat.yml"

# Step 3/4: validate config before restart (filebeat -e -c ... --strict.perms=false).
echo "Step 3/4: Validating config..."
if ssh_cmd "sudo filebeat test config -c /etc/filebeat/filebeat.yml --strict.perms=false"; then
    echo "  config valid"
else
    echo "  config INVALID — rolling back"
    ssh_cmd "sudo cp /etc/filebeat/filebeat.yml.bak.${TIMESTAMP} /etc/filebeat/filebeat.yml"
    exit 1
fi

# Step 4/4: restart filebeat + verify it stays up.
echo "Step 4/4: Restarting filebeat..."
ssh_cmd "sudo systemctl restart filebeat"
sleep 3
if ssh_cmd "sudo systemctl is-active filebeat"; then
    echo "=== ✓ filebeat restarted, status: $(ssh_cmd "sudo systemctl is-active filebeat") ==="
    echo
    echo "Verify in Kibana (give it ~30s for first events):"
    echo "  curl 'http://elasticsearch.lan.mazlusek.com/k8s-logs-*/_search?q=host.name:gpu-server&size=3'"
else
    echo "FATAL: filebeat failed to start. Recent logs:"
    ssh_cmd "sudo journalctl -u filebeat -n 30 --no-pager"
    exit 1
fi
