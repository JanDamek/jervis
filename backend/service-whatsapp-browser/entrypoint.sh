#!/bin/bash
set -e

# Start Xvfb (virtual framebuffer) on display :99
Xvfb :99 -screen 0 1920x1080x24 -ac +extension GLX +render -noreset &
export DISPLAY=:99

# Wait for Xvfb to start
sleep 1

# Start x11vnc (VNC server) on port 5900 — NO password.
# Authorization is already enforced by the single-use /vnc-login?token=X
# handoff plus the vnc_session cookie checked on every static asset
# request and on the WebSocket upgrade. A VNC password would only leak
# into URLs and iframe src attributes.
x11vnc -display :99 -forever -shared -rfbport 5900 -bg -o /dev/null -nopw

# Start websockify on 0.0.0.0:6080 with noVNC web client.
# --web serves the noVNC HTML/JS from the installed package, so accessing
# http://host:6080/vnc.html opens the VNC viewer in browser.
websockify --web=/usr/share/novnc 0.0.0.0:6080 localhost:5900 &

echo "Xvfb on :99, VNC on :5900, websockify on localhost:6080, FastAPI on :8091"

# Start the FastAPI app in headed mode
exec python -m uvicorn app.main:app --host 0.0.0.0 --port 8091
