#!/bin/bash
set -e

# Generate random VNC password if not set via environment
if [ -z "$WHATSAPP_VNC_PASSWORD" ]; then
    WHATSAPP_VNC_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 16)
    export WHATSAPP_VNC_PASSWORD
fi
echo -n "$WHATSAPP_VNC_PASSWORD" > /tmp/vnc_password

# Start Xvfb (virtual framebuffer) on display :99
Xvfb :99 -screen 0 1920x1080x24 -ac +extension GLX +render -noreset &
export DISPLAY=:99

# Wait for Xvfb to start
sleep 1

# Start x11vnc (VNC server) on port 5900, with password
x11vnc -display :99 -forever -shared -rfbport 5900 -bg -o /dev/null -passwd "$WHATSAPP_VNC_PASSWORD"

# Start websockify on 0.0.0.0:6080 with noVNC web client.
# --web serves the noVNC HTML/JS from the installed package, so accessing
# http://host:6080/vnc.html opens the VNC viewer in browser.
websockify --web=/usr/share/novnc 0.0.0.0:6080 localhost:5900 &

echo "Xvfb on :99, VNC on :5900, websockify on localhost:6080, FastAPI on :8091"

# Start the FastAPI app in headed mode
exec python -m uvicorn app.main:app --host 0.0.0.0 --port 8091
