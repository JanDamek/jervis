# --- Build Stage ---
FROM gradle:9.2.1-jdk21-jammy AS builder
WORKDIR /app
COPY . .
RUN DOCKER_BUILD=true gradle :backend:service-whisper:jar --no-daemon

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

WORKDIR /opt/jervis
COPY --from=builder /app/backend/service-whisper/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 JAVA_OPTS="-Xmx2g -Xms512m" WORK_DATA=/opt/jervis/work
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${WORK_DATA} && java ${JAVA_OPTS} -Djava.io.tmpdir=${WORK_DATA} -jar /opt/jervis/app.jar"]
