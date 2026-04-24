#!/usr/bin/env bash
set -e

# =============================================================================
# Deploy Ollama as a Docker container on the VD GPU VM
# (ollama.lan.mazlusek.com, p40-2).
#
# Uses the official `ollama/ollama:latest` image. Replaces the previous
# native `ollama.service` systemd unit so XTTS/Whisper/Ollama all share
# the same docker-run deploy pattern.
#
# Model weights stay at /usr/share/ollama/.ollama on the host (60+ GB —
# we don't move them) and are bind-mounted into /root/.ollama inside the
# container (the default model cache path).
#
# Env matches the previous systemd override.conf:
#   OLLAMA_HOST=0.0.0.0:11434        → LAN-reachable
#   OLLAMA_KEEP_ALIVE=-1             → never unload a model (router manages this)
#   OLLAMA_MAX_LOADED_MODELS=2       → P40 VRAM budget
#   OLLAMA_NUM_PARALLEL=10           → concurrent requests per model
#   OLLAMA_MAX_QUEUE=64              → queue depth before 503
#
# Usage:
#   ./deploy_ollama_gpu.sh                # uses SSH key auth (~/.ssh/id_starkys)
#   SKIP_PULL=1 ./deploy_ollama_gpu.sh    # skip image pull (already fetched)
# =============================================================================

GPU_HOST="${GPU_HOST:-ollama.lan.mazlusek.com}"
GPU_USER="${GPU_USER:-damekjan}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_starkys}"
IMAGE="${IMAGE:-ollama/ollama:latest}"
CONTAINER_NAME="ollama"
MODEL_DIR="/usr/share/ollama/.ollama"

ssh_cmd() {
    ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$GPU_USER@$GPU_HOST" "$@"
}

echo "=== Deploying $CONTAINER_NAME (official image) to $GPU_HOST ==="

# ---------------------------------------------------------------------------
# Step 1/4: stop + disable legacy systemd unit.
#           Separate SSH calls per command (same VD quirk as XTTS/Whisper).
# ---------------------------------------------------------------------------
echo "Step 1/4: Stopping legacy ollama systemd unit (if running)..."
# Trailing `|| true` at the script level swallows occasional SSH
# exit-signal 255 from sudo side-effects on this VD.
ssh_cmd "sudo systemctl stop ollama 2>/dev/null || true" || true
ssh_cmd "sudo systemctl disable ollama 2>/dev/null || true" || true
echo "  legacy unit stopped"

# ---------------------------------------------------------------------------
# Step 2/4: pull image (skip with SKIP_PULL=1 for offline redeploy).
# ---------------------------------------------------------------------------
if [ -z "$SKIP_PULL" ]; then
    echo "Step 2/4: Pulling $IMAGE..."
    ssh_cmd "docker pull '$IMAGE'"
    echo "  image pulled"
else
    echo "Step 2/4: SKIP_PULL set — skipping pull"
fi

# ---------------------------------------------------------------------------
# Step 3/4: stop old container (if any), run new one.
#
# Bind-mount /usr/share/ollama/.ollama → /root/.ollama so existing model
# weights (60 GB) are reused; fresh container, no re-download needed.
# Container runs as root internally (the ollama image's default); since
# network is host-networking, :11434 on ollama.lan.mazlusek.com serves
# the API directly.
# ---------------------------------------------------------------------------
echo "Step 3/4: Starting container..."
ssh_cmd "docker stop '$CONTAINER_NAME' 2>/dev/null || true"
ssh_cmd "docker rm '$CONTAINER_NAME' 2>/dev/null || true"
# chown the model dir so root-in-container can read/write — it was owned
# by host uid 1002 (ollama user) under the native systemd layout.
ssh_cmd "sudo chown -R root:root '$MODEL_DIR' 2>/dev/null || true"
ssh_cmd "docker run -d \
    --name '$CONTAINER_NAME' \
    --restart unless-stopped \
    --gpus all \
    --network host \
    -v '$MODEL_DIR':/root/.ollama \
    -e OLLAMA_HOST=0.0.0.0:11434 \
    -e OLLAMA_KEEP_ALIVE=-1 \
    -e OLLAMA_MAX_LOADED_MODELS=2 \
    -e OLLAMA_NUM_PARALLEL=10 \
    -e OLLAMA_MAX_QUEUE=64 \
    '$IMAGE'"
echo "  container started"

# ---------------------------------------------------------------------------
# Step 4/4: health-check :11434/api/tags (lists models; works without a
#           model loaded).
# ---------------------------------------------------------------------------
echo "Step 4/4: Waiting for :11434 (should be instant, models don't preload)..."
for i in $(seq 1 20); do
    if curl -s --max-time 3 "http://$GPU_HOST:11434/api/tags" | grep -q '"models"'; then
        echo "  :11434 healthy (attempt $i)"
        break
    fi
    sleep 2
done

if curl -s --max-time 5 "http://$GPU_HOST:11434/api/tags" | grep -q '"models"'; then
    MODELS=$(curl -s --max-time 5 "http://$GPU_HOST:11434/api/tags" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('models',[])))" 2>/dev/null || echo '?')
    echo "=== ✓ $CONTAINER_NAME running on $GPU_HOST:11434 ($MODELS models visible) ==="
else
    echo "⚠ :11434 not responding after 40s — check logs."
    echo "Logs: ssh -i $SSH_KEY $GPU_USER@$GPU_HOST 'docker logs --tail 80 $CONTAINER_NAME'"
    exit 1
fi
