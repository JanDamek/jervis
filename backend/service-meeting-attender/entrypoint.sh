#!/bin/bash
set -e

# Per-pod virtual audio sink. Chromium plays meeting audio into "jervis-sink",
# we record from its `.monitor` source via ffmpeg. Avoids any host audio
# device dependency — the pod is fully self-contained.
export XDG_RUNTIME_DIR=/tmp/runtime-$$
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

pulseaudio --start --exit-idle-time=-1 --daemonize=no &
PULSE_PID=$!
sleep 2
pactl load-module module-null-sink sink_name=jervis-sink sink_properties=device.description=JervisSink || true
pactl set-default-sink jervis-sink || true

# Xvfb for Chromium rendering (Teams refuses true headless for unauth joins).
export DISPLAY=:99
Xvfb :99 -screen 0 1280x720x24 &
XVFB_PID=$!

# noVNC for ops debugging — only exposed inside the cluster.
x11vnc -display :99 -forever -nopw -shared -bg -rfbport 5900 || true
websockify --web=/usr/share/novnc/ 6080 localhost:5900 &

trap 'kill $PULSE_PID $XVFB_PID 2>/dev/null || true' EXIT

exec uvicorn app.main:app --host 0.0.0.0 --port 8095
