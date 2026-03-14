FROM python:3.11-slim

WORKDIR /app

# Piper TTS needs these system deps
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Pre-download Czech male voice model (cs_CZ-jirka-medium)
COPY download_tts_model.py /tmp/download_tts_model.py
RUN python3 /tmp/download_tts_model.py && rm /tmp/download_tts_model.py

RUN useradd -m -u 1000 -s /bin/bash jervis && chown -R jervis:jervis /app /opt/jervis

USER 1000

EXPOSE 8787

CMD ["python", "-m", "uvicorn", "tts_server:app", "--host", "0.0.0.0", "--port", "8787", "--workers", "1"]
