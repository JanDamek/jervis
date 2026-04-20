#!/bin/bash
set -e

# Start Xvfb (virtual framebuffer) on display :99
Xvfb :99 -screen 0 1920x1080x24 -ac +extension GLX +render -noreset &
export DISPLAY=:99
sleep 1

# ── ALSA → PulseAudio bridge ──────────────────────────────────────
# Written to /etc/asound.conf so ALL processes (including Chromium
# spawned by Playwright) see it without needing ALSA_CONFIG_PATH env.
cat > /etc/asound.conf << 'ALSA'
pcm.!default {
    type pulse
}
ctl.!default {
    type pulse
}
ALSA
# Also set env var as backup
export ALSA_CONFIG_PATH=/etc/asound.conf

# ── PulseAudio with virtual null sink ─────────────────────────────
# null-sink captures all audio output without actual hardware.
# Chromium → PulseAudio → jervis_audio sink → .monitor source → ffmpeg
pulseaudio --start --exit-idle-time=-1 2>/dev/null || true
sleep 0.5
pactl load-module module-null-sink sink_name=jervis_audio \
    sink_properties=device.description="JervisAudio" \
    format=s16le rate=44100 channels=2 2>/dev/null || true
pactl set-default-sink jervis_audio 2>/dev/null || true
pactl set-default-source jervis_audio.monitor 2>/dev/null || true

# ── Audio routing daemon ──────────────────────────────────────────
# Chromium may create new sink-inputs at any time (tab opened, meeting
# joined). This loop ensures ALL audio streams are routed to our
# capture sink, not to some other default.
(
    while true; do
        sleep 5
        for idx in $(pactl list sink-inputs short 2>/dev/null | awk '{print $1}'); do
            # Check if already on jervis_audio (sink index from pactl list sinks short)
            current_sink=$(pactl list sink-inputs short 2>/dev/null | grep "^${idx}" | awk '{print $2}')
            jervis_sink_idx=$(pactl list sinks short 2>/dev/null | grep jervis_audio | awk '{print $1}')
            if [ -n "$jervis_sink_idx" ] && [ "$current_sink" != "$jervis_sink_idx" ]; then
                pactl move-sink-input "$idx" jervis_audio 2>/dev/null && \
                    echo "Audio routing: moved sink-input $idx to jervis_audio"
            fi
        done
    done
) &
echo "Audio routing daemon started"

# Start x11vnc (VNC server) on port 5900 — NO password.
# Authorization is already enforced by the single-use /vnc-login?token=X
# handoff plus the vnc_session cookie checked on every static asset
# request and on the WebSocket upgrade. A VNC password would only leak
# into URLs (?password=...) and browser history.
x11vnc -display :99 -forever -shared -rfbport 5900 -bg -o /dev/null -nopw

# Start websockify on localhost:6080 (internal only, FastAPI proxies to it)
websockify 127.0.0.1:6080 localhost:5900 &

echo "Xvfb on :99, VNC on :5900, websockify on localhost:6080, FastAPI on :8090"

# Start the FastAPI app in headed mode
exec python -m uvicorn app.main:app --host 0.0.0.0 --port 8090
