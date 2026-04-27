#!/usr/bin/env bash
set -e

# =============================================================================
# Deploy jervis-whisper-gpu (faster-whisper + pyannote.audio) as a Docker
# container on the VD GPU VM (ollama.lan.mazlusek.com, p40-2).
#
# Flow (all runs from the Mac):
#   1. buildx docker image (linux/amd64) from backend/service-whisper/Dockerfile.gpu
#   2. push to registry.damek-soft.eu
#   3. SSH to VD → stop+disable legacy systemd unit → docker pull → docker run --gpus all
#   4. health-check :8786/health over SSH
#
# Usage:
#   ./deploy_whisper_gpu.sh                    # uses SSH key auth (~/.ssh/id_starkys)
#   HF_TOKEN=hf_xxx ./deploy_whisper_gpu.sh    # passes token to the container
#                                              # (auto-fetched from K8s jervis-secrets when omitted)
#   SKIP_BUILD=1 ./deploy_whisper_gpu.sh       # pull+run only (faster redeploy)
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

GPU_HOST="${GPU_HOST:-ollama.lan.mazlusek.com}"
GPU_USER="${GPU_USER:-damekjan}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_starkys}"
REGISTRY="${REGISTRY:-registry.damek-soft.eu/jandamek}"
IMAGE_NAME="jervis-whisper-gpu"
IMAGE="${REGISTRY}/${IMAGE_NAME}:latest"
CONTAINER_NAME="jervis-whisper-gpu"
DOCKERFILE="${PROJECT_ROOT}/backend/service-whisper/Dockerfile.gpu"

# Router gRPC — same reasoning as XTTS: Whisper runs outside the K8s
# cluster, dial the worker-node NodePort.
ROUTER_GRPC_HOST="${ROUTER_GRPC_HOST:-jervis-router.lan.mazlusek.com}"
ROUTER_GRPC_PORT="${ROUTER_GRPC_PORT:-5501}"

ssh_cmd() {
    ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$GPU_USER@$GPU_HOST" "$@"
}

echo "=== Deploying $CONTAINER_NAME (faster-whisper + pyannote) to $GPU_HOST ==="

# ---------------------------------------------------------------------------
# Step 1/5: build + push (skip with SKIP_BUILD=1)
# ---------------------------------------------------------------------------
if [ -z "$SKIP_BUILD" ]; then
    echo "Step 1/5: Building $IMAGE (linux/amd64)..."
    docker buildx build --platform linux/amd64 \
        -f "$DOCKERFILE" \
        -t "$IMAGE" \
        --push \
        "$PROJECT_ROOT"
    echo "  image built + pushed"
else
    echo "Step 1/5: SKIP_BUILD set — skipping build/push"
fi

# ---------------------------------------------------------------------------
# Step 2/5: stop + disable legacy systemd unit.
#           Separate SSH calls per command — chaining sudo + pkill in one
#           session occasionally closes with exit-signal on this VD.
# ---------------------------------------------------------------------------
echo "Step 2/5: Stopping legacy systemd unit (if present)..."
# Trailing `|| true` swallows SSH exit-signal 255 from remote pkill/sudo
# side-effects that occasionally kill the channel on this VD.
ssh_cmd "sudo systemctl stop jervis-whisper 2>/dev/null || true" || true
ssh_cmd "sudo systemctl disable jervis-whisper 2>/dev/null || true" || true
ssh_cmd "pkill -f 'whisper_rest_server' 2>/dev/null || true" || true
echo "  legacy unit stopped"

# ---------------------------------------------------------------------------
# Step 3/5: resolve HF_TOKEN (env var > K8s jervis-secrets > empty).
# ---------------------------------------------------------------------------
if [ -z "$HF_TOKEN" ]; then
    HF_TOKEN=$(kubectl get secret jervis-secrets -n jervis -o jsonpath='{.data.HF_TOKEN}' 2>/dev/null \
        | base64 -d 2>/dev/null || true)
fi
if [ -n "$HF_TOKEN" ]; then
    echo "Step 3/5: HF_TOKEN resolved (diarization enabled)"
else
    echo "Step 3/5: HF_TOKEN not available — diarization will fall back to single-speaker"
fi

# ---------------------------------------------------------------------------
# Step 4/5: pull + run.
#
# Flags mirror the XTTS deploy — same VD, same GPU, same bind-mount layout.
# --network host: :5502 (gRPC) + :8786 (REST) reachable at
#                 ollama.lan.mazlusek.com directly.
# HF cache is shared with XTTS at /opt/jervis/hf-cache to avoid a second
# ~2-5 GB model-weights footprint on disk.
# ---------------------------------------------------------------------------
echo "Step 4/5: Pulling + running $IMAGE..."
ssh_cmd "docker pull '$IMAGE'"
ssh_cmd "docker stop '$CONTAINER_NAME' 2>/dev/null || true"
ssh_cmd "docker rm '$CONTAINER_NAME' 2>/dev/null || true"
ssh_cmd "mkdir -p /opt/jervis/hf-cache"

HF_ENV=""
if [ -n "$HF_TOKEN" ]; then
    HF_ENV="-e HF_TOKEN='$HF_TOKEN'"
fi

ssh_cmd "docker run -d \
    --name '$CONTAINER_NAME' \
    --restart unless-stopped \
    --label autoheal=true \
    --gpus all \
    --network host \
    -v /opt/jervis/hf-cache:/opt/jervis/hf-cache \
    -e WHISPER_DEVICE=cuda \
    -e WHISPER_COMPUTE_TYPE=int8_float32 \
    -e WHISPER_DEFAULT_MODEL=medium \
    -e WHISPER_REST_PORT=8786 \
    -e WHISPER_GRPC_PORT=5502 \
    -e WHISPER_REST_HOST=0.0.0.0 \
    -e WHISPER_REST_WORKERS=1 \
    -e ROUTER_GRPC_HOST='$ROUTER_GRPC_HOST' \
    -e ROUTER_GRPC_PORT='$ROUTER_GRPC_PORT' \
    -e CUDA_VISIBLE_DEVICES=0 \
    $HF_ENV \
    '$IMAGE'"
echo "  container started"

# ---------------------------------------------------------------------------
# Step 5/5: health-check :8786/health.
# ---------------------------------------------------------------------------
echo "Step 5/5: Waiting for REST :8786/health (models preload ~30-60s)..."
for i in $(seq 1 30); do
    HEALTH=$(curl -s --max-time 5 "http://$GPU_HOST:8786/health" 2>/dev/null || echo '{"status":"starting"}')
    STATUS=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','unknown'))" 2>/dev/null || echo "unknown")
    if [ "$STATUS" = "ok" ]; then
        echo "  REST healthy (attempt $i): $HEALTH"
        break
    fi
    sleep 5
done

if [ "$STATUS" = "ok" ]; then
    echo "=== ✓ $CONTAINER_NAME running on $GPU_HOST (:5502 gRPC, :8786 REST) ==="
else
    echo "⚠ /health not OK after 150s — still loading or boot failed."
    echo "Logs: ssh -i $SSH_KEY $GPU_USER@$GPU_HOST 'docker logs --tail 80 $CONTAINER_NAME'"
    exit 1
fi
