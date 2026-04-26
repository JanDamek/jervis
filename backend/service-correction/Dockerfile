FROM python:3.12-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Shared cross-service version constraints (SSOT in repo root).
COPY constraints.txt /constraints.txt

# Pod-to-pod Protobuf contracts package. Copied first so it layer-caches
# independently of the service's own requirements.
COPY libs/jervis_contracts /libs/jervis_contracts
RUN pip install --no-cache-dir -c /constraints.txt -e /libs/jervis_contracts

# Copy requirements and install Python dependencies
COPY backend/service-correction/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -c /constraints.txt -r requirements.txt

# Pre-download tiktoken BPE data (avoids runtime download behind SSL proxy)
RUN python -c "import tiktoken; tiktoken.get_encoding('cl100k_base')"

# Copy application code
COPY backend/service-correction/app/ ./app/

# Per uid-map.md: shared gid 1000 jervis + per-service uid 1060.
RUN groupadd -g 1000 jervis && \
    useradd -u 1060 -g jervis -m -s /bin/bash correction && \
    chown -R correction:jervis /app
USER correction

EXPOSE 8000 5501

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8000/health || exit 1

# Run with uvicorn (gRPC server boots in FastAPI lifespan on port 5501)
CMD ["sh", "-c", "umask 002 && uvicorn app.main:app --host 0.0.0.0 --port 8000 --log-level info"]
