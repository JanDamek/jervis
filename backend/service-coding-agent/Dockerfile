# =============================================================================
# Jervis Coding Agent Container
# Job-only image — spawned by Kotlin AgentJobDispatcher as a K8s Job per
# dispatched AgentJobRecord (CODING flavor). No persistent Deployment.
#
# Role: run Claude Code CLI against a prepared git worktree at $WORKSPACE,
#       commit + push on the per-agent branch, and exit.
#
# Scope: B (coding only). No meeting assistant, no KB ingest, no JAR.
# =============================================================================

FROM python:3.12-slim

ENV DEBIAN_FRONTEND=noninteractive \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# --- Base OS tools used by Claude Code CLI + agent Bash sandbox ---
# git + openssh-client: clone/fetch/push
# gnupg: signed commits (key imported at runtime from K8s secret)
# ripgrep: Claude Code's internal grep backend
# jq, less, tree, file, findutils, procps: common agent Bash utilities
# curl, wget, ca-certificates: NodeSource install + generic fetches
# make, unzip, zip, tar, gzip: build / archive ops inside the workspace
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        git \
        git-lfs \
        openssh-client \
        gnupg \
        ripgrep \
        jq \
        less \
        tree \
        file \
        findutils \
        procps \
        curl \
        wget \
        ca-certificates \
        make \
        unzip \
        zip \
        tar \
        gzip \
    && rm -rf /var/lib/apt/lists/*

# --- Node.js 20 (required by @anthropic-ai/claude-code CLI) ---
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get update && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

# --- Claude Code CLI (baked-in — NAS-cached version takes precedence at runtime) ---
RUN npm install -g @anthropic-ai/claude-code

# --- Claude Agent SDK (Python) — used by entrypoint to drive Claude headless ---
RUN pip install claude-agent-sdk

# --- Entrypoint ---
COPY backend/service-coding-agent/entrypoint-coding.sh /opt/jervis/entrypoint-coding.sh
RUN chmod +x /opt/jervis/entrypoint-coding.sh

# --- Non-root user per uid-map.md (Claude CLI refuses
#     --dangerously-skip-permissions as root). Shared gid 1000 jervis
#     + per-service uid 1040.
RUN groupadd -g 1000 jervis && \
    useradd -u 1040 -g jervis -m -s /bin/bash jervis-coding-agent && \
    mkdir -p /opt/jervis && \
    chown -R jervis-coding-agent:jervis /opt/jervis

# Activate the non-root user as the container default.
USER jervis-coding-agent

ENTRYPOINT ["/opt/jervis/entrypoint-coding.sh"]
