# =============================================================================
# Junie Agent Container
# Persistent Deployment – Kotlin orchestrator + JetBrains Junie CLI
# Junie supports MCP servers via .junie/mcp/mcp.json in workspace
# =============================================================================

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

# === Common agent tools (shell utilities agents invoke via terminal) ===
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    # -- basics --
    git curl wget ca-certificates gnupg lsb-release libarchive-tools \
    # -- code search --
    ripgrep \
    # -- shell utilities --
    jq diffutils patch less tree file unzip \
    procps findutils \
    # -- build tools --
    make \
    # -- network debug --
    dnsutils iproute2 openssh-client \
    # -- Python (for entrypoint scripts + MCP servers) --
    python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*

# === Node.js 20 (required by Junie CLI) ===
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get update && \
    apt-get install -y nodejs && \
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

# === Junie CLI (EAP) ===
RUN mkdir -p /opt/junie && \
    curl -fsSL https://github.com/jetbrains-junie/junie/releases/download/576.1/junie-eap-576.1-linux-amd64.zip -o /tmp/junie.zip && \
    bsdtar -x -f /tmp/junie.zip -C /opt/junie && \
    chmod +x /opt/junie/junie && \
    ln -s /opt/junie/junie /usr/local/bin/junie && \
    rm /tmp/junie.zip

# === MCP servers for Junie (KB + Joern) ===
# Junie reads MCP config from $WORKSPACE/.junie/mcp/mcp.json
# The Kotlin orchestrator writes this config before launching the agent.
# Servers are stdio-based, same Python scripts as Claude uses.
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"

COPY backend/service-kb-mcp/requirements.txt /opt/jervis/mcp/kb-requirements.txt
RUN pip install --no-cache-dir -r /opt/jervis/mcp/kb-requirements.txt
COPY backend/service-kb-mcp/server.py /opt/jervis/mcp/kb-server.py

COPY backend/service-joern-mcp/requirements.txt /opt/jervis/mcp/joern-requirements.txt
RUN pip install --no-cache-dir -r /opt/jervis/mcp/joern-requirements.txt
COPY backend/service-joern-mcp/server.py /opt/jervis/mcp/joern-server.py

# === Application JAR ===
WORKDIR /opt/jervis/junie
COPY backend/service-junie/build/libs/service-junie-*.jar jervis-junie.jar
COPY backend/shared-entrypoints/entrypoint-job.sh /opt/jervis/entrypoint-job.sh
RUN chmod +x /opt/jervis/entrypoint-job.sh

ENV PORT=3300 \
    JAVA_OPTS="-Xmx1g -Xms256m"
EXPOSE 3300

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/junie/jervis-junie.jar"]
