#!/usr/bin/env bash
set -e

# =============================================================================
# Deploy Piper TTS GPU service to ollama.lan.mazlusek.com (p40-2 VM).
#
# Runs as systemd service alongside Whisper + Ollama.
# Piper ONNX model is tiny (~50MB VRAM) — no GPU lock needed.
# Uses onnxruntime-gpu for CUDA acceleration (<0.5s vs ~4.5s CPU).
#
# Usage:
#   ./deploy_tts_gpu.sh                    # uses SSH key auth
#   SSH_PASS=secret ./deploy_tts_gpu.sh    # uses sshpass
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

GPU_HOST="${GPU_HOST:-ollama.lan.mazlusek.com}"
GPU_USER="${GPU_USER:-damekjan}"
INSTALL_DIR="/opt/jervis/tts"
SERVICE_NAME="jervis-tts-gpu"

# SSH wrapper
ssh_cmd() {
    if [ -n "$SSH_PASS" ]; then
        sshpass -p "$SSH_PASS" ssh -o StrictHostKeyChecking=no "$GPU_USER@$GPU_HOST" "$@"
    else
        ssh -o StrictHostKeyChecking=no "$GPU_USER@$GPU_HOST" "$@"
    fi
}

echo "=== Deploying $SERVICE_NAME to $GPU_HOST ==="

# Step 1: Setup directory and venv
echo "Step 1/5: Setting up directory and virtualenv..."
ssh_cmd "echo '$SSH_PASS' | sudo -S mkdir -p $INSTALL_DIR 2>/dev/null; echo '$SSH_PASS' | sudo -S chown -R $GPU_USER:$GPU_USER /opt/jervis 2>/dev/null; [ -d $INSTALL_DIR/venv ] || python3 -m venv $INSTALL_DIR/venv"
echo "  directory and venv OK"

# Step 2: Install Python dependencies with CUDA support
echo "Step 2/5: Installing Python dependencies (onnxruntime-gpu)..."
ssh_cmd "$INSTALL_DIR/venv/bin/pip install --no-cache-dir -q \
    fastapi uvicorn pydantic \
    piper-tts \
    onnxruntime-gpu 2>&1 | tail -5"
echo "  dependencies OK"

# Step 3: Copy server files
echo "Step 3/5: Copying TTS server..."
ssh_cmd "cat > $INSTALL_DIR/tts_server.py" < "$PROJECT_ROOT/backend/service-tts/tts_server.py"
echo "  files copied"

# Step 4: Create systemd service
echo "Step 4/5: Setting up systemd service..."
ssh_cmd "cat > /tmp/$SERVICE_NAME.service << 'UNIT_EOF'
[Unit]
Description=Jervis TTS GPU Service (Piper ONNX + CUDA)
After=network.target

[Service]
Type=simple
User=$GPU_USER
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/venv/bin/python3 -m uvicorn tts_server:app --host 0.0.0.0 --port 8787 --workers 1
Restart=on-failure
RestartSec=10

Environment=TTS_PORT=8787
Environment=TTS_MODEL=cs_CZ-jirka-medium
Environment=TTS_DATA_DIR=/opt/jervis/data/tts
Environment=TTS_MAX_TEXT_LENGTH=10000

[Install]
WantedBy=multi-user.target
UNIT_EOF

echo '$SSH_PASS' | sudo -S mv /tmp/$SERVICE_NAME.service /etc/systemd/system/$SERVICE_NAME.service 2>/dev/null
echo '$SSH_PASS' | sudo -S systemctl daemon-reload 2>/dev/null
echo '$SSH_PASS' | sudo -S systemctl enable $SERVICE_NAME 2>/dev/null"
echo "  systemd service configured"

# Step 5: Pre-download model + restart
echo "Step 5/5: Downloading model and starting service..."
ssh_cmd "mkdir -p /opt/jervis/data/tts/models && $INSTALL_DIR/venv/bin/python3 -c \"
from piper.download import ensure_voice_exists, get_voices
import os, glob
model_dir = '/opt/jervis/data/tts/models'
os.makedirs(model_dir, exist_ok=True)
voices = get_voices(model_dir, update_voices=True)
ensure_voice_exists('cs_CZ-jirka-medium', [model_dir], model_dir, voices)
# Remove non-Czech models
for f in glob.glob(os.path.join(model_dir, 'en_*')):
    os.remove(f)
print('Model ready')
\""

ssh_cmd "echo '$SSH_PASS' | sudo -S systemctl restart $SERVICE_NAME 2>/dev/null"
sleep 3

# Health check
echo ""
echo "Checking health..."
HEALTH=$(curl -s --max-time 15 "http://$GPU_HOST:8787/health" 2>/dev/null || echo '{"status":"starting"}')
echo "  $HEALTH"

STATUS=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','unknown'))" 2>/dev/null || echo "unknown")
if [ "$STATUS" = "ok" ]; then
    echo ""
    echo "=== $SERVICE_NAME deployed and healthy on $GPU_HOST:8787 ==="
else
    echo ""
    echo "Service is starting up (model loading may take a moment)."
    echo "Check status: ssh $GPU_USER@$GPU_HOST 'sudo systemctl status $SERVICE_NAME'"
    echo "Check logs:   ssh $GPU_USER@$GPU_HOST 'sudo journalctl -u $SERVICE_NAME -f'"
    echo "=== $SERVICE_NAME deployed to $GPU_HOST (health check pending) ==="
fi
