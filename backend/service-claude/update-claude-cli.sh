#!/bin/bash
# /opt/jervis/update-claude-cli.sh
#
# Auto-update Claude Code CLI from NAS/PVC cache.
# Designed for K8s Jobs where the image stays stable but the CLI
# needs to be always-latest (minor versions change multiple times per day).
#
# How it works:
#   1. Check if NAS cache volume is mounted
#   2. Use flock to prevent concurrent update races
#   3. npm install/update Claude Code to NAS-mounted npm prefix
#   4. Export PATH so entrypoint uses the NAS version
#   5. Fall back to baked-in version if NAS is unavailable
#
# Required:
#   CLAUDE_CLI_CACHE  – NAS/PVC path for cached CLI (default: /opt/jervis/data/claude-cli)
#
# Exports:
#   PATH              – prepended with NAS bin dir (if update succeeded)
#   NPM_CONFIG_PREFIX – set to NAS cache dir

set -euo pipefail

CLAUDE_CLI_CACHE="${CLAUDE_CLI_CACHE:-/opt/jervis/data/claude-cli}"
LOCK_FILE="${CLAUDE_CLI_CACHE}/.update.lock"
VERSION_FILE="${CLAUDE_CLI_CACHE}/.installed-version"
UPDATE_INTERVAL="${CLAUDE_CLI_UPDATE_INTERVAL:-3600}"  # seconds between checks (default 1h)

log() { echo "[claude-cli-update] $*"; }

# --- Guard: is NAS/PVC available? ---
if [ ! -d "$(dirname "$CLAUDE_CLI_CACHE")" ]; then
    log "WARN: NAS cache parent dir not found, using baked-in CLI"
    return 0 2>/dev/null || exit 0
fi

mkdir -p "$CLAUDE_CLI_CACHE"

# --- Should we check for updates? ---
needs_update() {
    # Always update if no version file exists
    [ ! -f "$VERSION_FILE" ] && return 0

    # Check time since last update
    if [ -f "$VERSION_FILE" ]; then
        last_update=$(stat -c %Y "$VERSION_FILE" 2>/dev/null || echo 0)
        now=$(date +%s)
        age=$(( now - last_update ))
        if [ "$age" -lt "$UPDATE_INTERVAL" ]; then
            log "CLI updated ${age}s ago (interval=${UPDATE_INTERVAL}s), skipping"
            return 1
        fi
    fi
    return 0
}

# --- Update with file lock (prevents concurrent job races) ---
do_update() {
    log "Checking for Claude Code CLI updates..."
    export NPM_CONFIG_PREFIX="$CLAUDE_CLI_CACHE"

    # Install or update — npm handles "already latest" gracefully (~2-5s)
    if npm install -g @anthropic-ai/claude-code@latest --prefer-online 2>&1 | tail -3; then
        # Record installed version
        local version
        version=$("${CLAUDE_CLI_CACHE}/bin/claude" --version 2>/dev/null || echo "unknown")
        echo "$version" > "$VERSION_FILE"
        log "CLI ready: $version"
    else
        log "WARN: npm update failed, will use existing/baked-in version"
    fi
}

# --- Main ---
if needs_update; then
    # flock: only one job updates at a time, others wait (max 120s)
    (
        if flock -w 120 200; then
            # Re-check inside lock (another job may have updated while we waited)
            if needs_update; then
                do_update
            else
                log "Another job already updated, skipping"
            fi
        else
            log "WARN: Could not acquire update lock (timeout), using existing version"
        fi
    ) 200>"$LOCK_FILE"
fi

# --- Export PATH so the NAS version takes precedence ---
if [ -x "${CLAUDE_CLI_CACHE}/bin/claude" ]; then
    export PATH="${CLAUDE_CLI_CACHE}/bin:$PATH"
    export NPM_CONFIG_PREFIX="$CLAUDE_CLI_CACHE"
    log "Using NAS-cached CLI: $(claude --version 2>/dev/null || echo 'unknown')"
else
    log "Using baked-in CLI: $(which claude 2>/dev/null || echo 'not found')"
fi
