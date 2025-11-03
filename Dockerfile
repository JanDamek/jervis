# ============================================
# Stage: Resolve Joern version (minimal context)
# - Evaluates Gradle task to deterministically get Joern version
# - This layer only changes when Gradle configuration changes
# ============================================
FROM gradle:8.5-jdk21 AS joern-version

WORKDIR /app

# Copy only the minimal Gradle files required to evaluate the joern version task
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle gradle/
COPY server/build.gradle.kts server/build.gradle.kts

# Calculate Joern version once; this layer is stable unless Gradle files change
RUN JOERN_VERSION=$(gradle -q :server:printJoernVersion --no-daemon) \
    && echo "$JOERN_VERSION" > /joern-version.txt

# ============================================
# Stage: Download and install Joern (rebuilds only if version changes)
# - Uses Alpine and streams ZIP directly into bsdtar to reduce disk usage
# ============================================
FROM alpine:3.20 AS joern-install

COPY --from=joern-version /joern-version.txt /joern-version.txt

RUN apk add --no-cache \
        ca-certificates \
        wget \
        libarchive-tools \
    && update-ca-certificates \
    && mkdir -p /opt/joern \
    && JOERN_VERSION=$(cat /joern-version.txt) \
    && wget -q -O - "https://github.com/joernio/joern/releases/download/v${JOERN_VERSION}/joern-cli.zip" \
        | bsdtar -x -f - -C /opt/joern \
    && chmod +x /opt/joern/joern-cli/joern

# ============================================
# Stage: Build the application (always part of image creation)
# ============================================
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy root Gradle files first to leverage cache for dependency resolution
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle gradle/

# Copy sources
COPY common common/
COPY common-internal common-internal/
COPY server server/
COPY service-tika service-tika/
COPY service-joern service-joern/
COPY service-whisper service-whisper/

# Build required modules (skip tests for speed)
RUN gradle -x test --no-daemon :server:bootJar :service-tika:bootJar :service-joern:bootJar :service-whisper:bootJar

# ============================================
# Stage: TiKa base (rarely changes)
# ============================================
FROM eclipse-temurin:21-jre AS tika-base

# Install required tools and TiKa dependencies
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    git \
    openssh-client \
    gnupg \
    gnupg-agent \
    pinentry-curses \
    curl \
    wget \
    unzip \
    python3 \
    python3-pip \
    tesseract-ocr \
    tesseract-ocr-eng \
    tesseract-ocr-ces \
    tesseract-ocr-spa \
    tesseract-ocr-slk \
    tesseract-ocr-fin \
    tesseract-ocr-nor \
    tesseract-ocr-dan \
    tesseract-ocr-pol \
    tesseract-ocr-deu \
    tesseract-ocr-hun \
    imagemagick \
    poppler-utils \
    ghostscript \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Configure environment for Tesseract
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

# Default OCR environment (can be overridden at runtime)
ENV TIKA_OCR_ENABLED=true
ENV TIKA_OCR_LANG=eng+ces+spa+slk+fin+nor+dan+pol+deu+hun
ENV TIKA_OCR_TIMEOUT_MS=120000

# ============================================
# Stage: Final images for each service
# ============================================

# ---------- Final image: jervis-tima
FROM tika-base AS runtime-tika
WORKDIR /opt/jervis
COPY --from=builder /app/service-tika/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 JAVA_OPTS="-Xmx1g -Xms256m" WORK_DATA=/opt/jervis/work
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "WD=${WORK_DATA}; if [ -z \"$WD\" ]; then WD=$(printenv WORK-DATA || true); fi; if [ -z \"$WD\" ]; then WD=/opt/jervis/work; fi; mkdir -p $WD && java ${JAVA_OPTS} -Djava.io.tmpdir=$WD -jar /opt/jervis/app.jar"]

# ---------- Final image: jervis-joern
FROM eclipse-temurin:21-jre AS runtime-joern
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
ENV JOERN_HOME=/opt/joern
COPY --from=joern-install /opt/joern ${JOERN_HOME}
ENV PATH="${JOERN_HOME}/joern-cli:${PATH}"
WORKDIR /opt/jervis
COPY --from=builder /app/service-joern/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 JAVA_OPTS="-Xmx1g -Xms256m" WORK_DATA=/opt/jervis/work
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${WORK_DATA} && java ${JAVA_OPTS} -Djava.io.tmpdir=${WORK_DATA} -jar /opt/jervis/app.jar"]

# ---------- Whisper base (simple runtime)
FROM eclipse-temurin:21-jre AS whisper-base
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 python3-venv ffmpeg libgomp1 ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv "$VIRTUAL_ENV"
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN python -m pip install --upgrade pip setuptools wheel && \
    pip install --no-cache-dir faster-whisper
# Optional sanity check
RUN python -c "import faster_whisper, sys; print('faster-whisper', faster_whisper.__version__)"

# ---------- Final image: jervis-whisper
FROM whisper-base AS runtime-whisper
WORKDIR /opt/jervis
COPY --from=builder /app/service-whisper/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 JAVA_OPTS="-Xmx1g -Xms256m" WORK_DATA=/opt/jervis/work
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${WORK_DATA} && java ${JAVA_OPTS} -Djava.io.tmpdir=${WORK_DATA} -jar /opt/jervis/app.jar"]

# ---------- Final image: jervis-server (orchestrator)
FROM eclipse-temurin:21-jre AS runtime-server
WORKDIR /opt/jervis
COPY --from=builder /app/server/build/libs/*.jar app.jar
ENV SERVER_PORT=5500 JAVA_OPTS="-Xmx2g -Xms512m" DATA_ROOT_DIR=/opt/jervis/data WORK_DATA=/opt/jervis/work
EXPOSE 5500
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
VOLUME ["/opt/jervis/data"]
ENTRYPOINT ["sh", "-c", "WD=${WORK_DATA}; if [ -z \"$WD\" ]; then WD=$(printenv WORK-DATA || true); fi; if [ -z \"$WD\" ]; then WD=/opt/jervis/work; fi; mkdir -p ${DATA_ROOT_DIR} $WD && java ${JAVA_OPTS} -Ddata.root.dir=${DATA_ROOT_DIR} -Djava.io.tmpdir=$WD -jar /opt/jervis/app.jar"]
