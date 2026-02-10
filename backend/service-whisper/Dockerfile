# =============================================================================
# Whisper K8s Job Image
# Runs as a K8s Job (not a persistent Deployment).
# Pure Python: faster-whisper + ffmpeg. No JRE, no Kotlin, no kRPC.
# =============================================================================
FROM python:3.11-slim

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ffmpeg libgomp1 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir "numpy<2.0" && \
    pip install --no-cache-dir faster-whisper

WORKDIR /opt/jervis/whisper

# Pre-download all Whisper models into the image (avoids HuggingFace download at runtime).
# Each model in a separate RUN to release memory between downloads (medium/large-v3 are ~5-6GB).
# Uses download_model() which only downloads files without loading into RAM.
RUN python3 -c "from faster_whisper.utils import download_model; download_model('tiny')"
RUN python3 -c "from faster_whisper.utils import download_model; download_model('base')"
RUN python3 -c "from faster_whisper.utils import download_model; download_model('small')"
RUN python3 -c "from faster_whisper.utils import download_model; download_model('medium')"
RUN python3 -c "from faster_whisper.utils import download_model; download_model('large-v3')"

COPY backend/service-whisper/whisper_runner.py /opt/jervis/whisper/whisper_runner.py
COPY backend/service-whisper/entrypoint-whisper-job.sh /opt/jervis/entrypoint-whisper-job.sh
RUN chmod +x /opt/jervis/entrypoint-whisper-job.sh

ENTRYPOINT ["/opt/jervis/entrypoint-whisper-job.sh"]
