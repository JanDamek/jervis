# ============================================
# Stage: Resolve Joern version (minimal context)
# - Evaluates Gradle task to deterministically get Joern version
# - This layer only changes when Gradle configuration changes
# ============================================
FROM gradle:9.2.1-jdk21-jammy AS joern-version

WORKDIR /app

# Copy only the minimal Gradle files required to evaluate the joern version task
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle/
# Need to copy build.gradle.kts files for all modules included in settings (except ui-common)
# Also copy common-dto since it's an includeBuild
COPY shared/common-dto/build.gradle.kts shared/common-dto/build.gradle.kts
COPY shared/common-api/build.gradle.kts shared/common-api/build.gradle.kts
COPY shared/domain/build.gradle.kts shared/domain/build.gradle.kts
COPY backend/common-services/build.gradle.kts backend/common-services/build.gradle.kts
COPY backend/server/build.gradle.kts backend/server/build.gradle.kts
COPY backend/service-tika/build.gradle.kts backend/service-tika/build.gradle.kts
COPY backend/service-joern/build.gradle.kts backend/service-joern/build.gradle.kts
COPY backend/service-whisper/build.gradle.kts backend/service-whisper/build.gradle.kts
COPY backend/service-aider/build.gradle.kts backend/service-aider/build.gradle.kts
COPY backend/service-coding-engine/build.gradle.kts backend/service-coding-engine/build.gradle.kts
COPY backend/service-atlassian/build.gradle.kts backend/service-atlassian/build.gradle.kts

# Calculate Joern version once; this layer is stable unless Gradle files change
# Set DOCKER_BUILD to skip app modules and ui-common in settings.gradle.kts
RUN export DOCKER_BUILD=true && \
    JOERN_VERSION=$(gradle -q :backend:server:printJoernVersion --no-daemon) && \
    echo "$JOERN_VERSION" > /joern-version.txt

# ============================================
# Stage: Download and install Joern (rebuilds only if version changes)
# - Uses Alpine and streams ZIP directly into bsdtar to reduce disk usage
# ============================================
FROM alpine:3.23 AS joern-install

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
FROM gradle:9.2.1-jdk21-jammy AS builder

WORKDIR /app

# Copy Gradle wrapper and version catalog (needed for common-dto)
COPY gradle gradle/
COPY gradlew gradlew.bat gradle.properties ./

# Copy root Gradle files for main build
COPY build.gradle.kts settings.gradle.kts ./

# Copy common-dto (needed as includeBuild dependency)
COPY shared/common-dto shared/common-dto/

# Copy shared modules (skip ui-common - not needed for backend)
COPY shared/common-api shared/common-api/
COPY shared/domain shared/domain/

# Copy backend modules
COPY backend/common-services backend/common-services/
COPY backend/server backend/server/
COPY backend/service-tika backend/service-tika/
COPY backend/service-joern backend/service-joern/
COPY backend/service-whisper backend/service-whisper/
COPY backend/service-aider backend/service-aider/
COPY backend/service-coding-engine backend/service-coding-engine/
COPY backend/service-atlassian backend/service-atlassian/

# Build backend modules one by one to reduce memory pressure
# Set DOCKER_BUILD env var to skip iOS/Android targets and Compose dependencies
# Use lower memory settings and build services sequentially
RUN DOCKER_BUILD=true GRADLE_OPTS="-Xmx1g -XX:MaxMetaspaceSize=512m" \
    gradle -x test --no-daemon \
    :backend:common-services:jar \
    :backend:service-tika:bootJar \
    :backend:service-aider:bootJar \
    :backend:service-coding-engine:bootJar \
    :backend:service-atlassian:bootJar \
    && rm -rf /root/.gradle/caches/build-cache-*

RUN DOCKER_BUILD=true GRADLE_OPTS="-Xmx1g -XX:MaxMetaspaceSize=512m" \
    gradle -x test --no-daemon \
    :backend:service-joern:bootJar \
    :backend:service-whisper:bootJar \
    && rm -rf /root/.gradle/caches/build-cache-*

RUN DOCKER_BUILD=true GRADLE_OPTS="-Xmx1g -XX:MaxMetaspaceSize=512m" \
    gradle -x test --no-daemon \
    :backend:server:bootJar \
    && rm -rf /root/.gradle/caches/build-cache-*

# ============================================
# Stage: TiKa base (rarely changes)
# ============================================
FROM eclipse-temurin:21-jdk-jammy AS tika-base

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

# ---------- Final image: jervis-tika
FROM tika-base AS runtime-tika
WORKDIR /opt/jervis
COPY --from=builder /app/backend/service-tika/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 JAVA_OPTS="-Xmx2g -Xms512m" WORK_DATA=/opt/jervis/work
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "WD=${WORK_DATA}; if [ -z \"$WD\" ]; then WD=$(printenv WORK-DATA || true); fi; if [ -z \"$WD\" ]; then WD=/opt/jervis/work; fi; mkdir -p $WD && java ${JAVA_OPTS} -Djava.io.tmpdir=$WD -jar /opt/jervis/app.jar"]

# ---------- Final image: jervis-joern
FROM eclipse-temurin:21-jre-jammy AS runtime-joern
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*
ENV JOERN_HOME=/opt/joern
COPY --from=joern-install /opt/joern ${JOERN_HOME}
ENV PATH="${JOERN_HOME}/joern-cli:${PATH}"
WORKDIR /opt/jervis
COPY --from=builder /app/backend/service-joern/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 JAVA_OPTS="-Xmx2g -Xms512m" WORK_DATA=/opt/jervis/work
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${WORK_DATA} && java ${JAVA_OPTS} -Djava.io.tmpdir=${WORK_DATA} -jar /opt/jervis/app.jar"]

# ---------- Whisper base (simple runtime)
FROM eclipse-temurin:21-jre-jammy AS whisper-base
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 python3-venv python3-pip ffmpeg libgomp1 ca-certificates curl \
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
COPY --from=builder /app/backend/service-whisper/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 JAVA_OPTS="-Xmx2g -Xms512m" WORK_DATA=/opt/jervis/work
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${WORK_DATA} && java ${JAVA_OPTS} -Djava.io.tmpdir=${WORK_DATA} -jar /opt/jervis/app.jar"]

# ---------- Aider base (Python + Aider CLI)
FROM eclipse-temurin:21-jre-jammy AS aider-base
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv \
    git universal-ctags curl \
    && rm -rf /var/lib/apt/lists/*
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir aider-chat

# ---------- Final image: jervis-aider
FROM aider-base AS runtime-aider
WORKDIR /opt/jervis
COPY --from=builder /app/backend/service-aider/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 \
    JAVA_OPTS="-Xmx1g -Xms256m" \
    DATA_ROOT_DIR=/opt/jervis/data \
    PYTHONIOENCODING=utf-8
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${DATA_ROOT_DIR} && java ${JAVA_OPTS} -Ddata.root.dir=${DATA_ROOT_DIR} -jar /opt/jervis/app.jar"]

# ---------- OpenHands base (Python + OpenHands + Docker CLI)
FROM eclipse-temurin:21-jre-jammy AS openhands-base
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    software-properties-common \
    git curl ca-certificates gnupg \
    && add-apt-repository ppa:deadsnakes/ppa -y \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
    python3.12 python3.12-venv python3.12-dev \
    && rm -rf /var/lib/apt/lists/*

RUN install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
    chmod a+r /etc/apt/keyrings/docker.gpg && \
    echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
      $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
      tee /etc/apt/sources.list.d/docker.list > /dev/null && \
    apt-get update && \
    apt-get install -y docker-ce-cli && \
    rm -rf /var/lib/apt/lists/*

ENV VIRTUAL_ENV=/opt/venv
RUN python3.12 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir openhands-ai

# ---------- Final image: jervis-coding-engine
FROM openhands-base AS runtime-coding-engine
WORKDIR /opt/jervis
COPY --from=builder /app/backend/service-coding-engine/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 \
    JAVA_OPTS="-Xmx2g -Xms512m" \
    DATA_ROOT_DIR=/opt/jervis/data \
    DOCKER_HOST=tcp://localhost:2375
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${DATA_ROOT_DIR} && java ${JAVA_OPTS} -Ddata.root.dir=${DATA_ROOT_DIR} -jar /opt/jervis/app.jar"]

# ---------- Final image: jervis-atlassian
FROM eclipse-temurin:21-jre-jammy AS runtime-atlassian
WORKDIR /opt/jervis
COPY --from=builder /app/backend/service-atlassian/build/libs/*.jar app.jar
ENV SERVER_PORT=8080 JAVA_OPTS="-Xmx1g -Xms256m" WORK_DATA=/opt/jervis/work
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "mkdir -p ${WORK_DATA} && java ${JAVA_OPTS} -Djava.io.tmpdir=${WORK_DATA} -jar /opt/jervis/app.jar"]

# ---------- Final image: jervis-weaviate (vector database with hybrid search)
FROM semitechnologies/weaviate:latest AS runtime-weaviate

# Note: Base image is minimal and does not provide apt-get. We avoid installing curl
# and perform readiness checks from the host/CI instead of inside the container.

# Configure environment variables for Weaviate
ENV PERSISTENCE_DATA_PATH=/var/lib/weaviate \
    QUERY_DEFAULTS_LIMIT=500 \
    QUERY_MAXIMUM_RESULTS=10000 \
    AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true \
    AUTHENTICATION_APIKEY_ENABLED=false \
    DEFAULT_VECTORIZER_MODULE=none \
    ENABLE_MODULES="" \
    TRACK_VECTOR_DIMENSIONS=true \
    GOMEMLIMIT=14GiB \
    GOMAXPROCS=14 \
    CLUSTER_HOSTNAME=weaviate-node1 \
    LOG_LEVEL=info

VOLUME ["/var/lib/weaviate"]
EXPOSE 8080 50051

# Start Weaviate (schema will be initialized by server application on first connectionDocument)
ENTRYPOINT ["/bin/weaviate"]
CMD ["--host", "0.0.0.0", "--port", "8080", "--scheme", "http"]

# ---------- Final image: jervis-server (orchestrator)
FROM eclipse-temurin:21-jre-jammy AS runtime-server

# Ensure required CLI tools are available for Git-based operations and MongoDB migrations
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    git \
    openssh-client \
    ca-certificates \
    curl \
    gnupg \
    wget && \
    rm -rf /var/lib/apt/lists/*

# Install MongoDB Shell (mongosh) for database migrations
RUN wget -qO- https://www.mongodb.org/static/pgp/server-8.0.asc | gpg --dearmor -o /usr/share/keyrings/mongodb-server-8.0.gpg && \
    echo "deb [ arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-8.0.gpg ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/8.0 multiverse" | tee /etc/apt/sources.list.d/mongodb-org-8.0.list && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends mongodb-mongosh && \
    rm -rf /var/lib/apt/lists/*

# Verify git and mongosh are available
RUN git --version && mongosh --version

WORKDIR /opt/jervis

# Copy application JAR
COPY --from=builder /app/backend/server/build/libs/*.jar app.jar

# Copy MongoDB migration scripts
COPY scripts/mongodb /opt/jervis/scripts/mongodb

ENV SERVER_PORT=5500 JAVA_OPTS="-Xmx4g -Xms1g" DATA_ROOT_DIR=/opt/jervis/data WORK_DATA=/opt/jervis/work
EXPOSE 5500
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f --insecure https://localhost:${SERVER_PORT}/actuator/health || exit 1
VOLUME ["/opt/jervis/data"]
ENTRYPOINT ["sh", "-c", "WD=${WORK_DATA}; if [ -z \"$WD\" ]; then WD=$(printenv WORK-DATA || true); fi; if [ -z \"$WD\" ]; then WD=/opt/jervis/work; fi; mkdir -p ${DATA_ROOT_DIR} $WD && java ${JAVA_OPTS} -Ddata.root.dir=${DATA_ROOT_DIR} -Djava.io.tmpdir=$WD -jar /opt/jervis/app.jar"]
