FROM mcr.microsoft.com/playwright/python:v1.58.0-noble

WORKDIR /app

# Install Xvfb, x11vnc, noVNC for headed browser mode (QR code scanning)
# Allow unsigned repos (Playwright base image GPG issue)
RUN apt-get update --allow-insecure-repositories || true && \
    apt-get install -y --no-install-recommends --allow-unauthenticated \
    xvfb \
    x11vnc \
    novnc \
    websockify \
    && rm -rf /var/lib/apt/lists/*

COPY libs/jervis_contracts /libs/jervis_contracts
RUN pip install --no-cache-dir --ignore-installed -e /libs/jervis_contracts

COPY backend/service-whatsapp-browser/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir --ignore-installed -r requirements.txt

COPY backend/service-whatsapp-browser/app/ app/
COPY backend/service-whatsapp-browser/entrypoint.sh /entrypoint.sh

# Create browser profiles directory (persistent via PVC)
RUN mkdir -p /browser-profiles && \
    chown -R 1000:1000 /app /browser-profiles && \
    chmod +x /entrypoint.sh

USER 1000

EXPOSE 8091 6080 5501

CMD ["/entrypoint.sh"]
