#!/usr/bin/env bash
set -e

# =============================================================================
# Deploy XTTS v2 (Coqui) GPU TTS service to ollama.lan.mazlusek.com (p40-2 VM).
#
# Multilingual neural TTS (Czech + English) with voice cloning.
# Uses ~2-4 GB VRAM on P40 GPU.
#
# Hosts both FastAPI (:8787, dev debug) and pod-to-pod gRPC (:5501,
# production — Kotlin + other pods dial jervis.tts.TtsService here).
#
# Usage:
#   ./deploy_xtts_gpu.sh                    # uses SSH key auth
#   SSH_PASS=secret ./deploy_xtts_gpu.sh    # uses sshpass
#
# To add a custom voice:
#   scp voice.wav damekjan@ollama.lan.mazlusek.com:/opt/jervis/data/tts/speakers/speaker.wav
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

GPU_HOST="${GPU_HOST:-ollama.lan.mazlusek.com}"
GPU_USER="${GPU_USER:-damekjan}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_starkys}"
INSTALL_DIR="/opt/jervis/xtts"
SERVICE_NAME="jervis-tts-gpu"

# SSH wrapper
ssh_cmd() {
    ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$GPU_USER@$GPU_HOST" "$@"
}

scp_cmd() {
    scp -i "$SSH_KEY" -o StrictHostKeyChecking=no -r "$@"
}

echo "=== Deploying $SERVICE_NAME (XTTS v2 + gRPC) to $GPU_HOST ==="

# Step 1: Stop old TTS procs (both Piper legacy and previous XTTS runs).
# Must NOT match the ssh/bash process itself — `pkill -f` scans the full
# cmdline. Two defenses: (a) the [x]-style character class matches 'x' as
# a regex but the literal bracketed form in our own cmdline doesn't match
# the regex back; (b) run each pkill in a fresh SSH so the pattern of one
# call never appears as literal in the cmdline of another.
echo "Step 1/7: Stopping old TTS..."
ssh_cmd "pkill -f '[t]ts_server:app' 2>/dev/null; true"
ssh_cmd "pkill -f '[x]tts_server' 2>/dev/null; true"
ssh_cmd "pkill -f 'app[.]xtts_server' 2>/dev/null; true"
echo "  old TTS stopped"

# Step 2: Setup directory and venv
echo "Step 2/7: Setting up directory and virtualenv..."
ssh_cmd "mkdir -p $INSTALL_DIR/app && mkdir -p /opt/jervis/data/tts/speakers && [ -d $INSTALL_DIR/venv ] || python3 -m venv $INSTALL_DIR/venv"
echo "  directory and venv OK"

# Step 3: Install Python dependencies with CUDA support
echo "Step 3/7: Installing Python dependencies (this may take a few minutes)..."
ssh_cmd "$INSTALL_DIR/venv/bin/pip install --no-cache-dir -q \
    torch torchaudio --index-url https://download.pytorch.org/whl/cu124 2>&1 | tail -3"
echo "  torch installed"

ssh_cmd "$INSTALL_DIR/venv/bin/pip install --no-cache-dir -q \
    coqui-tts numpy \
    'grpcio>=1.66.0,<1.80' 'grpcio-reflection>=1.66.0,<1.80' 'protobuf>=5.28.0,<7' 2>&1 | tail -5"
echo "  coqui-tts + gRPC deps installed"

# Step 4: Copy XTTS app/ (xtts_server.py + grpc_server.py + __init__.py)
#         and the shared jervis_contracts library.
echo "Step 4/7: Copying XTTS app + jervis_contracts..."
scp_cmd "$PROJECT_ROOT/backend/service-tts/app/." "$GPU_USER@$GPU_HOST:$INSTALL_DIR/app/"
ssh_cmd "rm -rf $INSTALL_DIR/jervis_contracts && mkdir -p $INSTALL_DIR/jervis_contracts"
scp_cmd "$PROJECT_ROOT/libs/jervis_contracts/." "$GPU_USER@$GPU_HOST:$INSTALL_DIR/jervis_contracts/"
ssh_cmd "$INSTALL_DIR/venv/bin/pip install --no-cache-dir -q -e $INSTALL_DIR/jervis_contracts 2>&1 | tail -3"
echo "  app + contracts installed"

# Step 5: Pre-download XTTS v2 model
echo "Step 5/7: Pre-downloading XTTS v2 model (first run only, ~2 GB)..."
ssh_cmd "$INSTALL_DIR/venv/bin/python3 -c \"
from TTS.api import TTS
print('Downloading XTTS v2 model...')
tts = TTS('tts_models/multilingual/multi-dataset/xtts_v2')
print('Model downloaded successfully')
\""
echo "  model ready"

# Step 6: Generate a default Czech speaker reference if none exists
echo "Step 6/7: Checking speaker reference..."
HAS_SPEAKER=$(ssh_cmd "ls /opt/jervis/data/tts/speakers/*.wav 2>/dev/null | head -1 || echo ''")
if [ -z "$HAS_SPEAKER" ]; then
    echo "  No speaker WAV found. Generating default Czech reference..."
    ssh_cmd "$INSTALL_DIR/venv/bin/python3 -c \"
from TTS.api import TTS
import numpy as np, wave, io

# Use XTTS built-in to generate a Czech reference sample
# This creates a baseline voice; replace with a real recording for best quality
tts = TTS('tts_models/multilingual/multi-dataset/xtts_v2')
# Generate a sample using built-in speaker
wav = tts.tts(
    text='Dobrý den, já jsem Jervis, váš osobní asistent. Rád vám pomohu s čímkoliv potřebujete.',
    language='cs',
)
wav_array = np.array(wav, dtype=np.float32)
wav_int16 = np.clip(wav_array * 32767, -32768, 32767).astype(np.int16)
with wave.open('/opt/jervis/data/tts/speakers/speaker.wav', 'wb') as f:
    f.setframerate(22050)
    f.setsampwidth(2)
    f.setnchannels(1)
    f.writeframes(wav_int16.tobytes())
print('Default speaker reference generated')
\""
    echo "  default speaker generated"
else
    echo "  speaker reference found: $HAS_SPEAKER"
fi

# Step 7: systemd service (auto-restart on failure, survives reboot)
echo "Step 7/7: Setting up systemd service..."
ssh_cmd "cat << 'SVCEOF' | sudo tee /etc/systemd/system/$SERVICE_NAME.service > /dev/null
[Unit]
Description=Jervis XTTS v2 gRPC Service (:5501, GPU)
After=network.target

[Service]
Type=simple
User=$GPU_USER
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/venv/bin/python -m app.xtts_server
Restart=on-failure
RestartSec=10
Environment=TTS_GRPC_PORT=5501
Environment=CUDA_VISIBLE_DEVICES=0
Environment=PYTHONPATH=$INSTALL_DIR

[Install]
WantedBy=multi-user.target
SVCEOF
sudo systemctl daemon-reload
sudo systemctl enable $SERVICE_NAME
sudo systemctl restart $SERVICE_NAME"

echo "Waiting for model to load (~30-120s)..."
sleep 15

# Health check — gRPC-only; poll the :5501 port until it's listening.
for i in 1 2 3 4 5 6 7 8 9 10; do
    GRPC_LISTENING=$(ssh_cmd "ss -tln 2>/dev/null | grep -c ':5501 ' || echo 0")
    if [ "$GRPC_LISTENING" -ge 1 ]; then
        break
    fi
    echo "  waiting for gRPC :5501... ($i/10)"
    sleep 5
done

echo ""
echo "gRPC :5501 listening: $GRPC_LISTENING (should be >=1)"

if [ "$GRPC_LISTENING" -ge 1 ]; then
    echo ""
    echo "=== $SERVICE_NAME deployed + healthy on $GPU_HOST (gRPC :5501) ==="
else
    echo ""
    echo "Service is starting up (model loading takes ~30-60s on first request)."
    echo "Check logs: ssh -i $SSH_KEY $GPU_USER@$GPU_HOST 'sudo journalctl -u $SERVICE_NAME -n 60 --no-pager'"
    echo "=== $SERVICE_NAME deployed to $GPU_HOST (health check pending) ==="
fi

echo ""
echo "To add your own voice:"
echo "  scp -i $SSH_KEY your_voice.wav $GPU_USER@$GPU_HOST:/opt/jervis/data/tts/speakers/speaker.wav"
echo "  ssh -i $SSH_KEY $GPU_USER@$GPU_HOST 'sudo systemctl restart $SERVICE_NAME'"
echo "  # (no hot-swap endpoint — speaker embeddings reload at service start)"
