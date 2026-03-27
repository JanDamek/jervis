#!/usr/bin/env bash
set -e

# =============================================================================
# Deploy Whisper GPU REST service to ollama.lan.mazlusek.com (p40-2 VM).
#
# This service runs OUTSIDE K8s — directly on the GPU VM as a systemd service,
# sharing the P40 GPU with Ollama via CUDA.
#
# Prerequisites:
#   - SSH access to GPU_HOST (key-based or sshpass)
#   - Python 3.11+ on GPU_HOST
#   - NVIDIA driver installed on GPU_HOST
#
# Usage:
#   ./deploy_whisper_gpu.sh                    # uses SSH key auth
#   SSH_PASS=secret ./deploy_whisper_gpu.sh    # uses sshpass
#   HF_TOKEN=hf_xxx ./deploy_whisper_gpu.sh    # enables speaker diarization
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

GPU_HOST="${GPU_HOST:-ollama.lan.mazlusek.com}"
GPU_USER="${GPU_USER:-damekjan}"
INSTALL_DIR="/opt/jervis/whisper"
SERVICE_NAME="jervis-whisper"

# SSH wrapper — uses sshpass if SSH_PASS is set, otherwise plain ssh
ssh_cmd() {
    if [ -n "$SSH_PASS" ]; then
        sshpass -p "$SSH_PASS" ssh -o StrictHostKeyChecking=no "$GPU_USER@$GPU_HOST" "$@"
    else
        ssh -o StrictHostKeyChecking=no "$GPU_USER@$GPU_HOST" "$@"
    fi
}

scp_cmd() {
    if [ -n "$SSH_PASS" ]; then
        sshpass -p "$SSH_PASS" scp -o StrictHostKeyChecking=no "$@"
    else
        scp -o StrictHostKeyChecking=no "$@"
    fi
}

echo "=== Deploying $SERVICE_NAME to $GPU_HOST ==="

# Step 1: Ensure target directory and venv exist
echo "Step 1/6: Setting up directory and virtualenv..."
ssh_cmd "echo '$SSH_PASS' | sudo -S mkdir -p $INSTALL_DIR 2>/dev/null; echo '$SSH_PASS' | sudo -S chown -R $GPU_USER:$GPU_USER /opt/jervis 2>/dev/null; [ -d $INSTALL_DIR/venv ] || python3 -m venv $INSTALL_DIR/venv"
echo "  directory and venv OK"

# Step 2: Install/upgrade Python dependencies
echo "Step 2/6: Installing Python dependencies..."
ssh_cmd "$INSTALL_DIR/venv/bin/pip install --no-cache-dir -q \
    faster-whisper \
    pyannote.audio \
    fastapi uvicorn python-multipart sse-starlette \
    torch torchaudio --index-url https://download.pytorch.org/whl/cu124 2>&1 | tail -3"
echo "  dependencies OK (including pyannote-audio for speaker diarization)"

# Step 3: Copy server files (using ssh+cat — more reliable with sshpass than scp)
echo "Step 3/6: Copying server files..."
ssh_cmd "cat > $INSTALL_DIR/whisper_runner.py" < "$PROJECT_ROOT/backend/service-whisper/app/whisper_runner.py"
ssh_cmd "cat > $INSTALL_DIR/whisper_rest_server.py" < "$PROJECT_ROOT/backend/service-whisper/app/whisper_rest_server.py"
echo "  files copied"

# Step 4: Pre-download whisper models (if not cached)
echo "Step 4/6: Pre-downloading whisper models..."
ssh_cmd "$INSTALL_DIR/venv/bin/python3 -c \"
from faster_whisper.utils import download_model
for m in ['tiny', 'base', 'small', 'medium', 'large-v3']:
    print(f'  checking {m}...')
    download_model(m)
print('  all models ready')
\""

# Step 5: Create/update systemd service
echo "Step 5/6: Setting up systemd service..."
# HF_TOKEN: use env var, or auto-fetch from K8s secret
if [ -z "$HF_TOKEN" ]; then
    HF_TOKEN=$(kubectl get secret jervis-secrets -n jervis -o jsonpath='{.data.HF_TOKEN}' 2>/dev/null | base64 -d 2>/dev/null || true)
fi
HF_TOKEN_LINE=""
if [ -n "$HF_TOKEN" ]; then
    HF_TOKEN_LINE="Environment=HF_TOKEN=$HF_TOKEN"
fi

ssh_cmd "cat > /tmp/$SERVICE_NAME.service << 'UNIT_EOF'
[Unit]
Description=Jervis Whisper GPU REST Service
After=network.target

[Service]
Type=simple
User=$GPU_USER
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/venv/bin/python3 $INSTALL_DIR/whisper_rest_server.py
Restart=on-failure
RestartSec=10

Environment=WHISPER_DEVICE=cuda
Environment=WHISPER_COMPUTE_TYPE=int8_float32
Environment=WHISPER_DEFAULT_MODEL=medium
Environment=WHISPER_REST_PORT=8786
Environment=WHISPER_REST_HOST=0.0.0.0
Environment=WHISPER_REST_WORKERS=1
Environment=ROUTER_URL=http://jervis-router.lan.mazlusek.com
$HF_TOKEN_LINE

[Install]
WantedBy=multi-user.target
UNIT_EOF

echo '$SSH_PASS' | sudo -S mv /tmp/$SERVICE_NAME.service /etc/systemd/system/$SERVICE_NAME.service 2>/dev/null
echo '$SSH_PASS' | sudo -S systemctl daemon-reload 2>/dev/null
echo '$SSH_PASS' | sudo -S systemctl enable $SERVICE_NAME 2>/dev/null"
echo "  systemd service configured"

# Step 6: Restart the service
echo "Step 6/6: Restarting service..."
ssh_cmd "echo '$SSH_PASS' | sudo -S systemctl restart $SERVICE_NAME 2>/dev/null"
sleep 3

# Check health
echo ""
echo "Checking health..."
HEALTH=$(curl -s --max-time 10 "http://$GPU_HOST:8786/health" 2>/dev/null || echo '{"status":"starting"}')
echo "  $HEALTH"

STATUS=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','unknown'))" 2>/dev/null || echo "unknown")
if [ "$STATUS" = "ok" ]; then
    echo ""
    echo "=== $SERVICE_NAME deployed and healthy on $GPU_HOST:8786 ==="
else
    echo ""
    echo "Service is starting up (model loading may take a minute)."
    echo "Check status: ssh $GPU_USER@$GPU_HOST 'sudo systemctl status $SERVICE_NAME'"
    echo "Check logs:   ssh $GPU_USER@$GPU_HOST 'sudo journalctl -u $SERVICE_NAME -f'"
    echo "=== $SERVICE_NAME deployed to $GPU_HOST (health check pending) ==="
fi
