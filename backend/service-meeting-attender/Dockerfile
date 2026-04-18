FROM mcr.microsoft.com/playwright/python:v1.58.0-noble

WORKDIR /app

# ffmpeg for audio capture from the virtual sink + Xvfb so Chromium can render
# the meeting page (Teams web client refuses to start in true headless mode for
# guest joins). PulseAudio gives us a per-pod virtual sink we can record from
# without needing host audio devices.
RUN apt-get update --allow-insecure-repositories || true && \
    apt-get install -y --no-install-recommends --allow-unauthenticated \
    ffmpeg \
    pulseaudio \
    pulseaudio-utils \
    xvfb \
    x11vnc \
    novnc \
    websockify \
    dbus-x11 \
    && rm -rf /var/lib/apt/lists/*

COPY libs/jervis_contracts /libs/jervis_contracts
RUN pip install --no-cache-dir --ignore-installed -e /libs/jervis_contracts

COPY backend/service-meeting-attender/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir --ignore-installed -r requirements.txt

COPY backend/service-meeting-attender/app/ app/
COPY backend/service-meeting-attender/entrypoint.sh /entrypoint.sh

RUN mkdir -p /var/lib/jervis/recordings && \
    chown -R 1000:1000 /app /var/lib/jervis/recordings && \
    chmod +x /entrypoint.sh

USER 1000

EXPOSE 8095 5501 6080

CMD ["/entrypoint.sh"]
