# =============================================================================
# Claude Code Agent Container
# Persistent Deployment – Kotlin orchestrator + Claude Code CLI + MCP servers
#
# IMPORTANT CONVENTION (see KB "service-claude-cli-tools-rule"):
# Every CLI tool installed here is an AGENT CAPABILITY, not an app dependency.
# Even if no grep in the source code references mongosh / psql / arangosh /
# pandoc / helm / …, they MUST stay. The Claude Code agent uses the Bash tool
# to run arbitrary shell commands on behalf of the user, and removing any of
# these tools makes the agent blind to that capability. Adding is safe,
# removing is DESTRUCTIVE.
# =============================================================================

# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

# === Common agent tools (shell utilities the agent invokes via Bash) ===
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    # -- basics --
    git git-lfs curl wget ca-certificates gnupg lsb-release \
    # -- Claude Code internal dependency --
    ripgrep \
    # -- search / data processing --
    jq diffutils patch less tree file \
    # -- archives --
    unzip zip tar gzip bzip2 xz-utils p7zip-full \
    # -- process / file search --
    procps findutils fd-find fzf \
    # -- build tools --
    make cmake \
    # -- network debug / HTTP clients --
    dnsutils iproute2 openssh-client httpie \
    # -- document / image conversion --
    pandoc imagemagick \
    # -- shell linting / pretty diff --
    shellcheck \
    # -- Python (for MCP servers + entrypoint scripts + ad-hoc agent scripts) --
    python3 python3-pip python3-venv python3-dev \
    # -- lightweight DB (agent may need local scratch DB) --
    sqlite3 \
    # -- Redis CLI (Jervis uses Redis for caching + rate limiting) --
    redis-tools \
    && rm -rf /var/lib/apt/lists/*

# fd-find installs as `fdfind` on Debian/Ubuntu — symlink to `fd` for the
# expected command name so agents can use `fd` directly.
RUN ln -s /usr/bin/fdfind /usr/local/bin/fd || true

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

# === ArangoDB Shell (arangosh) — direct .deb download ===
# Historical note: the ArangoDB APT repo signing key (C04AD0FD86FEC04D) expired
# on 2026-04-07 and covers ALL versions (3.11, 3.12, ...), so adding the apt
# repo always fails `apt-get update` with EXPKEYSIG. Workaround: download the
# .deb file directly and install with `dpkg -i`. This bypasses the apt repo
# entirely and works as long as the .deb itself is reachable.
#
# Do NOT comment this out or remove arangosh — agents use it for ad-hoc graph
# queries when users ask. See KB: service-claude-cli-tools-rule.
#
# Pinned version: bump ARANGODB_CLIENT_VERSION when needed.
# List of available versions: https://download.arangodb.com/arangodb312/DEBIAN/amd64/
RUN ARANGODB_CLIENT_VERSION="3.12.4.3-1" && \
    curl -fsSLo /tmp/arangodb3-client.deb \
    "https://download.arangodb.com/arangodb312/DEBIAN/amd64/arangodb3-client_${ARANGODB_CLIENT_VERSION}_amd64.deb" && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        /tmp/arangodb3-client.deb && \
    rm -f /tmp/arangodb3-client.deb && \
    rm -rf /var/lib/apt/lists/*

# === PostgreSQL client (psql) ===
RUN apt-get update && \
    apt-get install -y --no-install-recommends postgresql-client && \
    rm -rf /var/lib/apt/lists/*

# === kubectl (K8s namespace management for coding agents) ===
RUN curl -fsSLo /usr/local/bin/kubectl \
    "https://dl.k8s.io/release/$(curl -Ls https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" && \
    chmod +x /usr/local/bin/kubectl

# === Helm (K8s package manager — Jervis uses K8s heavily, agent may need helm charts) ===
# Install from the official tarball on GitHub releases. Avoids the baltocdn
# apt repo which has been flaky (HTTP/2 PROTOCOL_ERROR during builds).
RUN HELM_VERSION="v3.16.3" && \
    curl -fsSLo /tmp/helm.tar.gz \
    "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz" && \
    tar -xzf /tmp/helm.tar.gz -C /tmp && \
    mv /tmp/linux-amd64/helm /usr/local/bin/helm && \
    chmod +x /usr/local/bin/helm && \
    rm -rf /tmp/helm.tar.gz /tmp/linux-amd64

# === yq (YAML processor — complement to jq for K8s manifests, configs) ===
RUN curl -fsSLo /usr/local/bin/yq \
    "https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64" && \
    chmod +x /usr/local/bin/yq

# === git-delta (pretty git diff viewer — standalone binary) ===
RUN DELTA_VERSION="0.18.2" && \
    curl -fsSLo /tmp/delta.deb \
    "https://github.com/dandavison/delta/releases/download/${DELTA_VERSION}/git-delta_${DELTA_VERSION}_amd64.deb" && \
    dpkg -i /tmp/delta.deb && \
    rm -f /tmp/delta.deb

# === Claude Code CLI (baked-in fallback — NAS-cached version takes precedence at runtime) ===
RUN npm install -g @anthropic-ai/claude-code

# === Auto-update script (sources NAS-cached CLI at Job startup) ===
COPY backend/service-claude/update-claude-cli.sh /opt/jervis/update-claude-cli.sh
RUN chmod +x /opt/jervis/update-claude-cli.sh

# === Python venv for MCP servers ===
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv $VIRTUAL_ENV
ENV PATH="$VIRTUAL_ENV/bin:$PATH"

# Joern MCP server (stdio subprocess – delegates to K8s Job)
# KB + Environment tools now served by HTTP service-mcp (agents connect via HTTP)
COPY backend/service-joern-mcp/requirements.txt /opt/jervis/mcp/joern-requirements.txt
RUN pip install --no-cache-dir -r /opt/jervis/mcp/joern-requirements.txt
RUN pip install --no-cache-dir claude-agent-sdk
COPY backend/service-joern-mcp/server.py /opt/jervis/mcp/joern-server.py

# === Application JAR ===
WORKDIR /opt/jervis/claude
COPY backend/service-claude/build/libs/service-claude-*.jar claude.jar
COPY backend/shared-entrypoints/entrypoint-job.sh /opt/jervis/entrypoint-job.sh
COPY backend/shared-entrypoints/entrypoint-companion.sh /opt/jervis/entrypoint-companion.sh
RUN chmod +x /opt/jervis/entrypoint-job.sh /opt/jervis/entrypoint-companion.sh
COPY backend/service-claude/claude_sdk_runner.py /opt/jervis/claude_sdk_runner.py
COPY backend/service-claude/companion_sdk_runner.py /opt/jervis/companion_sdk_runner.py

# === Non-root user per uid-map.md (Claude CLI refuses
#     --dangerously-skip-permissions as root). Shared gid 1000 jervis
#     + per-service uid 1041 (claude / claude image).
RUN groupadd -g 1000 jervis && \
    useradd -u 1041 -g jervis -m -s /bin/bash claude && \
    chown -R claude:jervis /opt/jervis /opt/venv
USER claude

ENV PORT=3400 \
    JAVA_OPTS="-Xmx1g -Xms256m"
EXPOSE 3400

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/claude/claude.jar"]
