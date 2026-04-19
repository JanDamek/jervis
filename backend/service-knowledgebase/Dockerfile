FROM python:3.12-slim

WORKDIR /app

# Shared cross-service version constraints (SSOT in repo root).
COPY constraints.txt /constraints.txt

# Pod-to-pod Protobuf contracts package. Copied first so it layer-caches
# independently of the service's own requirements.
COPY libs/jervis_contracts /libs/jervis_contracts
RUN pip install --no-cache-dir -c /constraints.txt -e /libs/jervis_contracts

COPY backend/service-knowledgebase/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -c /constraints.txt -r requirements.txt

# Pre-download tiktoken BPE data (avoids runtime download behind SSL proxy)
RUN python -c "import tiktoken; tiktoken.get_encoding('cl100k_base')"

COPY backend/service-knowledgebase/ .

# Expose port 8080 (matching the Kotlin service port if possible, or standard 8080)
RUN useradd -m -u 1000 -s /bin/bash jervis && chown -R jervis:jervis /app
USER 1000

EXPOSE 8080

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port 8080 --workers ${UVICORN_WORKERS:-1}"]
