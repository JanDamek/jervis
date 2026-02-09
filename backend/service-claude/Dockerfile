# =============================================================================
# Claude Code Agent Container
# Persistent Deployment – Kotlin orchestrator + Claude Code CLI + MCP servers
# =============================================================================

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

# === Common agent tools (shell utilities agents invoke via Bash) ===
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    # -- basics --
    git curl wget ca-certificates gnupg lsb-release \
    # -- Claude Code internal dependency --
    ripgrep \
    # -- shell utilities --
    jq diffutils patch less tree file unzip \
    procps findutils \
    # -- build tools --
    make \
    # -- network debug --
    dnsutils iproute2 openssh-client \
    # -- Python (for MCP servers + entrypoint scripts) --
    python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*

# === Node.js 20 (required by Claude Code CLI) ===
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get update && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

# === GitHub CLI (Claude Code uses gh natively for PR operations) ===
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
    | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg && \
    chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] \
    https://cli.github.com/packages stable main" \
    > /etc/apt/sources.list.d/github-cli.list && \
    apt-get update && apt-get install -y gh && \
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

# === Claude Code CLI (globally via npm) ===
RUN npm install -g @anthropic-ai/claude-code

# === Python venv for MCP servers ===
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"

# KB MCP server (stdio subprocess)
COPY backend/service-kb-mcp/requirements.txt /opt/jervis/mcp/kb-requirements.txt
RUN pip install --no-cache-dir -r /opt/jervis/mcp/kb-requirements.txt
COPY backend/service-kb-mcp/server.py /opt/jervis/mcp/kb-server.py

# Joern MCP server (stdio subprocess – delegates to K8s Job)
COPY backend/service-joern-mcp/requirements.txt /opt/jervis/mcp/joern-requirements.txt
RUN pip install --no-cache-dir -r /opt/jervis/mcp/joern-requirements.txt
COPY backend/service-joern-mcp/server.py /opt/jervis/mcp/joern-server.py

# === Application JAR ===
WORKDIR /opt/jervis/claude
COPY backend/service-claude/build/libs/service-claude-*.jar jervis-claude.jar
COPY backend/shared-entrypoints/entrypoint-job.sh /opt/jervis/entrypoint-job.sh
RUN chmod +x /opt/jervis/entrypoint-job.sh

ENV PORT=3400 \
    JAVA_OPTS="-Xmx1g -Xms256m"
EXPOSE 3400

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/claude/jervis-claude.jar"]
