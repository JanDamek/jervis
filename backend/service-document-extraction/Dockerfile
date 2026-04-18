FROM python:3.11-slim

WORKDIR /app

# System deps for pymupdf, lxml
RUN apt-get update && apt-get install -y --no-install-recommends \
    libmupdf-dev libfreetype6-dev libharfbuzz-dev libjpeg62-turbo-dev \
    && rm -rf /var/lib/apt/lists/*

COPY backend/service-document-extraction/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Shared contracts package (proto stubs + interceptors)
COPY libs/jervis_contracts /opt/libs/jervis_contracts
RUN pip install --no-cache-dir -e /opt/libs/jervis_contracts

COPY backend/service-document-extraction/app/ app/

EXPOSE 8080 5501

# Single worker — the gRPC server owns port 5501 and must not be multi-replicated in-proc.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1"]
