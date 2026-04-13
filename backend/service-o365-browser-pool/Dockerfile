FROM mcr.microsoft.com/playwright/python:v1.58.0-noble

WORKDIR /app

# Install Xvfb, x11vnc, noVNC for headed browser mode
# Allow unsigned repos (Playwright base image GPG issue)
RUN apt-get update --allow-insecure-repositories || true && \
    apt-get install -y --no-install-recommends --allow-unauthenticated \
    xvfb \
    x11vnc \
    novnc \
    websockify \
    pulseaudio \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir --ignore-installed -r requirements.txt

COPY app/ app/
COPY entrypoint.sh /entrypoint.sh

# Create browser profiles directory
RUN mkdir -p /browser-profiles && \
    chown -R 1000:1000 /app /browser-profiles && \
    chmod +x /entrypoint.sh && \
    touch /etc/asound.conf && chown 1000:1000 /etc/asound.conf

USER 1000

EXPOSE 8090 6080

CMD ["/entrypoint.sh"]
