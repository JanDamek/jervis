# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    git curl ca-certificates python3 python3-pip python3-venv && \
    rm -rf /var/lib/apt/lists/*

# Install Node.js 20 (required by Claude Code)
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get update && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

# Install Claude Code CLI globally via npm
RUN npm install -g @anthropic-ai/claude-code

# MCP KB server (stdio subprocess for Claude Code KB access)
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"
COPY backend/service-kb-mcp/requirements.txt /opt/jervis/mcp/requirements.txt
RUN pip install --no-cache-dir -r /opt/jervis/mcp/requirements.txt
COPY backend/service-kb-mcp/server.py /opt/jervis/mcp/kb-server.py

WORKDIR /opt/jervis/claude
COPY backend/service-claude/build/libs/service-claude-*.jar jervis-claude.jar
COPY backend/shared-entrypoints/entrypoint-job.sh /opt/jervis/entrypoint-job.sh
RUN chmod +x /opt/jervis/entrypoint-job.sh

ENV PORT=3400 \
    JAVA_OPTS="-Xmx1g -Xms256m"
EXPOSE 3400

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/claude/jervis-claude.jar"]
