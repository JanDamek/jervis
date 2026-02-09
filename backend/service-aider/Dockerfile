# =============================================================================
# Aider Agent Container
# Persistent Deployment – Kotlin orchestrator + Aider CLI
# =============================================================================

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

# === Common agent tools (shell utilities agents invoke via Bash) ===
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    # -- basics --
    git curl wget ca-certificates gnupg lsb-release \
    # -- code search (Aider uses tree-sitter but ripgrep is useful fallback) --
    ripgrep \
    # -- Aider repo map fallback --
    universal-ctags \
    # -- shell utilities --
    jq diffutils patch less tree file unzip \
    procps findutils \
    # -- build tools --
    make \
    # -- network debug --
    dnsutils iproute2 openssh-client \
    # -- Aider /web command dependency --
    pandoc \
    # -- Python --
    python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*

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

# === Aider CLI (Python venv) ===
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir aider-chat

# === Application JAR ===
WORKDIR /opt/jervis/aider
COPY backend/service-aider/build/libs/service-aider-*.jar jervis-aider.jar
COPY backend/shared-entrypoints/entrypoint-job.sh /opt/jervis/entrypoint-job.sh
RUN chmod +x /opt/jervis/entrypoint-job.sh

ENV PORT=3100 \
    JAVA_OPTS="-Xmx1g -Xms256m" \
    PYTHONIOENCODING=utf-8
EXPOSE 3100

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/aider/jervis-aider.jar"]
