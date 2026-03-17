#!/usr/bin/env bash
set -e

# =============================================================================
# Deploy XTTS v2 (Coqui) GPU TTS service to ollama.lan.mazlusek.com (p40-2 VM).
#
# Replaces Piper TTS with multilingual neural TTS (Czech + English).
# Supports voice cloning from a reference WAV file.
# Uses ~2-4 GB VRAM on P40 GPU.
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

echo "=== Deploying $SERVICE_NAME (XTTS v2) to $GPU_HOST ==="

# Step 1: Stop old Piper TTS if running
echo "Step 1/6: Stopping old Piper TTS..."
ssh_cmd "pkill -f 'tts_server:app' 2>/dev/null || true"
echo "  old TTS stopped"

# Step 2: Setup directory and venv
echo "Step 2/6: Setting up directory and virtualenv..."
ssh_cmd "mkdir -p $INSTALL_DIR && mkdir -p /opt/jervis/data/tts/speakers && [ -d $INSTALL_DIR/venv ] || python3 -m venv $INSTALL_DIR/venv"
echo "  directory and venv OK"

# Step 3: Install Python dependencies with CUDA support
echo "Step 3/6: Installing Python dependencies (this may take a few minutes)..."
ssh_cmd "$INSTALL_DIR/venv/bin/pip install --no-cache-dir -q \
    torch torchaudio --index-url https://download.pytorch.org/whl/cu124 2>&1 | tail -3"
echo "  torch installed"

ssh_cmd "$INSTALL_DIR/venv/bin/pip install --no-cache-dir -q \
    coqui-tts \
    fastapi uvicorn pydantic numpy 2>&1 | tail -5"
echo "  coqui-tts + deps installed"

# Step 4: Copy server file
echo "Step 4/6: Copying XTTS server..."
ssh_cmd "cat > $INSTALL_DIR/xtts_server.py" < "$PROJECT_ROOT/backend/service-tts/xtts_server.py"
echo "  server copied"

# Step 5: Pre-download XTTS v2 model
echo "Step 5/6: Pre-downloading XTTS v2 model (first run only, ~2 GB)..."
ssh_cmd "$INSTALL_DIR/venv/bin/python3 -c \"
from TTS.api import TTS
print('Downloading XTTS v2 model...')
tts = TTS('tts_models/multilingual/multi-dataset/xtts_v2')
print('Model downloaded successfully')
\""
echo "  model ready"

# Step 6: Generate a default Czech speaker reference if none exists
echo "Step 6/6: Checking speaker reference..."
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

# Start the service (nohup for now, systemd setup separate)
echo ""
echo "Starting XTTS v2 service..."
ssh_cmd "cd $INSTALL_DIR && nohup $INSTALL_DIR/venv/bin/python3 -m uvicorn xtts_server:app --host 0.0.0.0 --port 8787 --workers 1 > /opt/jervis/data/tts/xtts.log 2>&1 &"

echo "Waiting for model to load (this takes ~30s on first request)..."
sleep 5

# Health check (retry a few times as model loads lazily)
for i in 1 2 3 4 5 6; do
    HEALTH=$(curl -s --max-time 10 "http://$GPU_HOST:8787/health" 2>/dev/null || echo '{"status":"starting"}')
    STATUS=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','unknown'))" 2>/dev/null || echo "unknown")
    if [ "$STATUS" = "ok" ]; then
        break
    fi
    echo "  waiting... ($i/6)"
    sleep 5
done

echo ""
echo "Health: $HEALTH"

if [ "$STATUS" = "ok" ]; then
    echo ""
    echo "=== $SERVICE_NAME (XTTS v2) deployed and healthy on $GPU_HOST:8787 ==="
else
    echo ""
    echo "Service is starting up (model loading takes ~30-60s on first request)."
    echo "Check logs: ssh -i $SSH_KEY $GPU_USER@$GPU_HOST 'tail -f /opt/jervis/data/tts/xtts.log'"
    echo "=== $SERVICE_NAME (XTTS v2) deployed to $GPU_HOST (health check pending) ==="
fi

echo ""
echo "To add your own voice:"
echo "  scp -i $SSH_KEY your_voice.wav $GPU_USER@$GPU_HOST:/opt/jervis/data/tts/speakers/speaker.wav"
echo "  curl -X POST http://$GPU_HOST:8787/set_speaker?wav_path=/opt/jervis/data/tts/speakers/speaker.wav"
