# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 python3-venv python3-pip ffmpeg libgomp1 ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv "$VIRTUAL_ENV"
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN python -m pip install --upgrade pip setuptools wheel && \
    pip install --no-cache-dir faster-whisper

WORKDIR /opt/jervis/whisper
COPY backend/service-whisper/build/libs/*.jar jervis-whisper.jar
ENV JAVA_OPTS="-Xmx2g -Xms512m"
EXPOSE 8083
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/whisper/jervis-whisper.jar"]
