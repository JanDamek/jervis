#!/bin/bash
set -e

# Generate random VNC password if not set via environment
if [ -z "$O365_POOL_VNC_PASSWORD" ]; then
    O365_POOL_VNC_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 16)
    export O365_POOL_VNC_PASSWORD
fi
echo -n "$O365_POOL_VNC_PASSWORD" > /tmp/vnc_password

# Start Xvfb (virtual framebuffer) on display :99
Xvfb :99 -screen 0 1920x1080x24 -ac +extension GLX +render -noreset &
export DISPLAY=:99

# Wait for Xvfb to start
sleep 1

# ALSA → PulseAudio bridge so Chromium sees audio devices
cat > /tmp/asound.conf << 'ALSA'
pcm.!default {
    type pulse
}
ctl.!default {
    type pulse
}
ALSA
export ALSA_CONFIG_PATH=/tmp/asound.conf

# Start PulseAudio with virtual null sink for meeting audio capture
pulseaudio --start --exit-idle-time=-1 2>/dev/null || true
pactl load-module module-null-sink sink_name=jervis_audio sink_properties=device.description="JervisAudio" format=s16le rate=44100 channels=2 2>/dev/null || true
pactl set-default-sink jervis_audio 2>/dev/null || true
pactl set-default-source jervis_audio.monitor 2>/dev/null || true

# Start x11vnc (VNC server) on port 5900, with password
x11vnc -display :99 -forever -shared -rfbport 5900 -bg -o /dev/null -passwd "$O365_POOL_VNC_PASSWORD"

# Start websockify on localhost:6080 (internal only, FastAPI proxies to it)
websockify 127.0.0.1:6080 localhost:5900 &

echo "Xvfb on :99, VNC on :5900, websockify on localhost:6080, FastAPI on :8090"

# Start the FastAPI app in headed mode
exec python -m uvicorn app.main:app --host 0.0.0.0 --port 8090
