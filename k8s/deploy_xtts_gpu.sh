#!/usr/bin/env bash
set -e

# =============================================================================
# Deploy jervis-xtts-gpu (Coqui XTTS v2) as a Docker container on the
# VD GPU VM (ollama.lan.mazlusek.com, p40-2).
#
# Flow (all runs from the Mac):
#   1. buildx docker image (linux/amd64) from backend/service-tts/Dockerfile.gpu
#   2. push to registry.damek-soft.eu
#   3. SSH to VD → stop+disable legacy systemd unit → docker pull → docker run --gpus all
#   4. health-check :5501 (gRPC) over SSH
#
# Usage:
#   ./deploy_xtts_gpu.sh                    # uses SSH key auth (~/.ssh/id_starkys)
#   SSH_PASS=secret ./deploy_xtts_gpu.sh    # or sshpass fallback
#   SKIP_BUILD=1 ./deploy_xtts_gpu.sh       # pull+run only (faster redeploy)
#
# To add a voice reference:
#   scp -i ~/.ssh/id_starkys voice.wav \
#     damekjan@ollama.lan.mazlusek.com:/opt/jervis/data/tts/speakers/speaker.wav
#   ./deploy_xtts_gpu.sh SKIP_BUILD=1   # container picks up new sample on restart
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

GPU_HOST="${GPU_HOST:-ollama.lan.mazlusek.com}"
GPU_USER="${GPU_USER:-damekjan}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_starkys}"
REGISTRY="${REGISTRY:-registry.damek-soft.eu/jandamek}"
IMAGE_NAME="jervis-xtts-gpu"
IMAGE="${REGISTRY}/${IMAGE_NAME}:latest"
CONTAINER_NAME="jervis-xtts-gpu"
DOCKERFILE="${PROJECT_ROOT}/backend/service-tts/Dockerfile.gpu"

# Router + server endpoints (see deploy_whisper_gpu.sh — identical network layout).
# XTTS runs outside the K8s cluster on the GPU VM; dial the worker node's NodePort
# directly (in-cluster DNS is not resolvable from here).
ROUTER_GRPC_HOST="${ROUTER_GRPC_HOST:-192.168.101.37}"
ROUTER_GRPC_PORT="${ROUTER_GRPC_PORT:-30501}"
SERVER_GRPC_HOST="${SERVER_GRPC_HOST:-192.168.101.37}"
SERVER_GRPC_PORT="${SERVER_GRPC_PORT:-30500}"

ssh_cmd() {
    ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$GPU_USER@$GPU_HOST" "$@"
}

echo "=== Deploying $CONTAINER_NAME (XTTS v2 via Docker) to $GPU_HOST ==="

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
# Step 2/5: stop + disable legacy systemd unit (one-shot migration away
#           from `python -m app` venv setup; idempotent on re-runs).
# ---------------------------------------------------------------------------
echo "Step 2/5: Stopping legacy systemd unit (if present)..."
# Separate SSH calls — chaining in one session occasionally kills the
# channel with exit-signal 255 on this VD. Trailing `|| true` at the
# script level swallows that so `set -e` doesn't abort.
ssh_cmd "sudo systemctl stop jervis-tts-gpu 2>/dev/null || true" || true
ssh_cmd "sudo systemctl disable jervis-tts-gpu 2>/dev/null || true" || true
ssh_cmd "pkill -f 'app[.]xtts_server' 2>/dev/null || true" || true
ssh_cmd "pkill -f '[x]tts_server' 2>/dev/null || true" || true
echo "  legacy unit stopped"

# ---------------------------------------------------------------------------
# Step 3/5: registry login (NOPASSWD sudo on VD, creds from the VD's
#           ~/.docker/config.json — provisioned out-of-band by infra).
# ---------------------------------------------------------------------------
echo "Step 3/5: Pulling $IMAGE on $GPU_HOST..."
ssh_cmd "docker pull '$IMAGE'"
echo "  image pulled"

# ---------------------------------------------------------------------------
# Step 4/5: stop+remove old container (if any) and run the new one.
#
# Flags:
#   --restart unless-stopped  → survives VD reboots
#   --gpus all                → exposes the P40 (shares with Ollama + Whisper)
#   --network host            → :5501 (gRPC) + :8787 (FastAPI debug) reachable
#                               at ollama.lan.mazlusek.com directly, no NAT
#   -v /opt/jervis/data/tts   → voice reference WAVs (persistent, not in image)
#   -v /opt/jervis/hf-cache   → XTTS v2 model weights (~2 GB, first-run cache)
# ---------------------------------------------------------------------------
echo "Step 4/5: Stopping old $CONTAINER_NAME + starting new..."
ssh_cmd "docker stop '$CONTAINER_NAME' 2>/dev/null || true; docker rm '$CONTAINER_NAME' 2>/dev/null || true"
ssh_cmd "mkdir -p /opt/jervis/data/tts/speakers /opt/jervis/hf-cache"
ssh_cmd "docker run -d \
    --name '$CONTAINER_NAME' \
    --restart unless-stopped \
    --gpus all \
    --network host \
    -v /opt/jervis/data/tts:/opt/jervis/data/tts \
    -v /opt/jervis/hf-cache:/opt/jervis/hf-cache \
    -e TTS_GRPC_PORT=5501 \
    -e CUDA_VISIBLE_DEVICES=0 \
    -e ROUTER_GRPC_HOST='$ROUTER_GRPC_HOST' \
    -e ROUTER_GRPC_PORT='$ROUTER_GRPC_PORT' \
    -e SERVER_GRPC_HOST='$SERVER_GRPC_HOST' \
    -e SERVER_GRPC_PORT='$SERVER_GRPC_PORT' \
    '$IMAGE'"
echo "  container started"

# ---------------------------------------------------------------------------
# Step 5/5: health check — poll :5501 until listening.
# ---------------------------------------------------------------------------
echo "Step 5/5: Waiting for gRPC :5501 (XTTS model ~30-120s on first boot)..."
for i in $(seq 1 30); do
    LISTENING=$(ssh_cmd "ss -tln 2>/dev/null | grep -c ':5501 ' || echo 0" | tr -d '[:space:]')
    if [ "$LISTENING" -ge 1 ]; then
        echo "  gRPC :5501 listening (attempt $i)"
        break
    fi
    sleep 5
done

if [ "$LISTENING" -ge 1 ]; then
    echo "=== ✓ $CONTAINER_NAME running on $GPU_HOST (:5501 gRPC, :8787 FastAPI) ==="
else
    echo "⚠ :5501 not listening after 150s — model may still be loading, or boot failed."
    echo "Logs: ssh -i $SSH_KEY $GPU_USER@$GPU_HOST 'docker logs --tail 80 $CONTAINER_NAME'"
    exit 1
fi
