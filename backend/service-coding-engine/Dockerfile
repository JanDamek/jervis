# =============================================================================
# OpenHands (Coding Engine) Agent Container
# Persistent Deployment – Kotlin orchestrator + OpenHands runtime
# OpenHands internally spawns its own sandbox; this container is the controller.
# =============================================================================

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

# === Common agent tools (shell utilities for OpenHands sandbox + controller) ===
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    # -- basics --
    git curl wget ca-certificates gnupg software-properties-common \
    # -- code search --
    ripgrep \
    # -- shell utilities --
    jq diffutils patch less tree file unzip \
    procps findutils \
    # -- OpenHands REQUIRES tmux for BashSession --
    tmux \
    # -- build tools (for compiling Python C extensions) --
    make build-essential \
    # -- network debug --
    dnsutils iproute2 openssh-client \
    && rm -rf /var/lib/apt/lists/*

# === Python 3.12 (OpenHands requires 3.12+) ===
RUN add-apt-repository ppa:deadsnakes/ppa -y && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
    python3.12 python3.12-venv python3.12-dev && \
    rm -rf /var/lib/apt/lists/*

# === Docker CLI (OpenHands uses Docker for sandbox containers) ===
RUN install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
    chmod a+r /etc/apt/keyrings/docker.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
    https://download.docker.com/linux/ubuntu \
    $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list && \
    apt-get update && \
    apt-get install -y docker-ce-cli && \
    rm -rf /var/lib/apt/lists/*

# === MongoDB Shell (mongosh) ===
RUN curl -fsSL https://www.mongodb.org/static/pgp/server-7.0.asc \
    | gpg --dearmor -o /usr/share/keyrings/mongodb-server-7.0.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/mongodb-server-7.0.gpg] \
    https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/7.0 multiverse" \
    > /etc/apt/sources.list.d/mongodb-org-7.0.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends mongodb-mongosh && \
    rm -rf /var/lib/apt/lists/*

# === ArangoDB Shell (arangosh) ===
RUN curl -fsSL https://download.arangodb.com/arangodb311/DEBIAN/Release.key \
    | gpg --dearmor -o /usr/share/keyrings/arangodb.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/arangodb.gpg] \
    https://download.arangodb.com/arangodb311/DEBIAN/ /" \
    > /etc/apt/sources.list.d/arangodb.list && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends arangodb3-client && \
    rm -rf /var/lib/apt/lists/*

# === PostgreSQL client (psql) ===
RUN apt-get update && \
    apt-get install -y --no-install-recommends postgresql-client && \
    rm -rf /var/lib/apt/lists/*

# === OpenHands (Python venv) ===
ENV VIRTUAL_ENV=/opt/venv
RUN python3.12 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir openhands-ai

# === Application JAR ===
WORKDIR /opt/jervis/coding-engine
COPY backend/service-coding-engine/build/libs/service-coding-engine-*.jar jervis-coding-engine.jar
COPY backend/shared-entrypoints/entrypoint-job.sh /opt/jervis/entrypoint-job.sh
RUN chmod +x /opt/jervis/entrypoint-job.sh

ENV PORT=3200 \
    JAVA_OPTS="-Xmx2g -Xms512m" \
    DOCKER_HOST=tcp://localhost:2375
EXPOSE 3200

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/coding-engine/jervis-coding-engine.jar"]
