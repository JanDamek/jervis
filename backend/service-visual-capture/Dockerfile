FROM python:3.12-slim

WORKDIR /app

# OpenCV runtime dependencies (headless — no GUI, just RTSP + image processing)
# python:3.12-slim (bookworm) replaced libgl1-mesa-glx with libgl1
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 libglib2.0-0 libsm6 libxrender1 libxext6 \
    && rm -rf /var/lib/apt/lists/*

# Shared cross-service version constraints (SSOT in repo root).
COPY constraints.txt /constraints.txt

COPY libs/jervis_contracts /libs/jervis_contracts
RUN pip install --no-cache-dir -c /constraints.txt -e /libs/jervis_contracts

COPY backend/service-visual-capture/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -c /constraints.txt -r requirements.txt

COPY backend/service-visual-capture/app/ app/

EXPOSE 8096 5501

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8096", "--workers", "1"]
