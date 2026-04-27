#!/usr/bin/env bash
set -e

# =============================================================================
# Deploy `autoheal` sidecar on the VD GPU VM (ollama.lan.mazlusek.com).
#
# Watches all docker containers labelled `autoheal=true` and restarts any that
# Docker reports as `unhealthy`. The audio service containers
# (jervis-whisper-gpu, jervis-xtts-gpu) ship with HEALTHCHECK that pings NVML
# (`nvidia-smi -L`) — if the host hypervisor re-attaches the GPU passthrough,
# the in-container device handles go stale and CUDA allocs start failing with
# "no CUDA-capable device is detected" while the container itself stays
# running. `--restart unless-stopped` doesn't help because nothing crashes.
# This sidecar closes that gap.
#
# Reference: https://github.com/willfarrell/docker-autoheal
#
# Usage:
#   ./deploy_autoheal.sh        # uses SSH key auth (~/.ssh/id_starkys)
# =============================================================================

GPU_HOST="${GPU_HOST:-ollama.lan.mazlusek.com}"
GPU_USER="${GPU_USER:-damekjan}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_starkys}"
IMAGE="willfarrell/autoheal:latest"
CONTAINER_NAME="autoheal"

ssh_cmd() {
    ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$GPU_USER@$GPU_HOST" "$@"
}

echo "=== Deploying $CONTAINER_NAME on $GPU_HOST ==="

# ---------------------------------------------------------------------------
# Pull, then replace any existing container.
#
# AUTOHEAL_CONTAINER_LABEL=autoheal — only touches containers with that label
#   (so we don't restart Ollama, registry mirrors, or random stuff someone
#   started by hand).
# AUTOHEAL_INTERVAL=10 — check every 10s. Audio jobs are minutes long, so a
#   10-15s detection window is fine.
# AUTOHEAL_START_PERIOD=120 — give a freshly restarted container time to load
#   its model before we'd consider restarting it again.
# /var/run/docker.sock — required so autoheal can issue restart commands.
# ---------------------------------------------------------------------------
echo "Pulling $IMAGE..."
ssh_cmd "docker pull '$IMAGE'"

echo "Stopping + removing any existing $CONTAINER_NAME..."
ssh_cmd "docker stop '$CONTAINER_NAME' 2>/dev/null || true"
ssh_cmd "docker rm '$CONTAINER_NAME' 2>/dev/null || true"

echo "Starting $CONTAINER_NAME..."
ssh_cmd "docker run -d \
    --name '$CONTAINER_NAME' \
    --restart unless-stopped \
    -e AUTOHEAL_CONTAINER_LABEL=autoheal \
    -e AUTOHEAL_INTERVAL=10 \
    -e AUTOHEAL_START_PERIOD=120 \
    -v /var/run/docker.sock:/var/run/docker.sock \
    '$IMAGE'"

echo "Verifying..."
sleep 3
RUNNING=$(ssh_cmd "docker ps --filter name=^${CONTAINER_NAME}\$ --format '{{.Status}}' 2>/dev/null" | head -1)
if [[ "$RUNNING" == Up* ]]; then
    echo "=== ✓ $CONTAINER_NAME running ($RUNNING) ==="
    echo
    echo "Watched containers (label autoheal=true):"
    ssh_cmd "docker ps --filter label=autoheal=true --format '  {{.Names}} ({{.Status}})'"
else
    echo "⚠ $CONTAINER_NAME failed to start: $RUNNING"
    ssh_cmd "docker logs --tail 30 '$CONTAINER_NAME' 2>&1 || true"
    exit 1
fi
