# =============================================================================
# KILO Coding Agent Container
# Lightweight container using aider-chat with OpenRouter API (free models)
# =============================================================================

FROM python:3.12-slim

# Common agent tools
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    git curl wget ca-certificates gnupg \
    ripgrep jq diffutils patch less tree file \
    procps findutils openssh-client \
    && rm -rf /var/lib/apt/lists/*

# Node.js 20 (for potential MCP server communication)
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get update && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

# Install aider-chat (the core coding tool for KILO)
RUN pip install --no-cache-dir aider-chat httpx

# Per uid-map.md: shared gid 1000 jervis + per-service uid 1043 (kilo).
RUN groupadd -g 1000 jervis && \
    useradd -u 1043 -g jervis -m -s /bin/bash kilo

# Workspace directory
WORKDIR /workspace

# Copy shared entrypoint
COPY backend/shared-entrypoints/entrypoint-job.sh /opt/jervis/entrypoint-job.sh
RUN chmod +x /opt/jervis/entrypoint-job.sh

USER kilo

ENTRYPOINT ["/opt/jervis/entrypoint-job.sh"]
