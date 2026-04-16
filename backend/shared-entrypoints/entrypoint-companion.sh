#!/bin/bash
# Jervis Companion Agent entrypoint (Claude CLI in companion mode).
#
# Required env: WORKSPACE, SESSION_ID, COMPANION_MODE (adhoc|session)
# Auth: CLAUDE_CODE_OAUTH_TOKEN (Max/Pro) or ANTHROPIC_API_KEY
#
# Unlike the coding agent, companion is READ-ONLY on the workspace — no git
# setup, no GPG, no commit/push delegation.
set -euo pipefail

WORKSPACE="${WORKSPACE:?WORKSPACE env is required}"
SESSION_ID="${SESSION_ID:-adhoc}"
COMPANION_MODE="${COMPANION_MODE:-adhoc}"

echo "[companion] session=$SESSION_ID mode=$COMPANION_MODE workspace=$WORKSPACE"

if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    echo "[companion] ERROR: no Claude credentials set (CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY)"
    exit 1
fi

cd "$WORKSPACE"

# Optional Claude Code CLI auto-update from NAS cache
if [ -f /opt/jervis/update-claude-cli.sh ]; then
    # shellcheck disable=SC1091
    source /opt/jervis/update-claude-cli.sh || echo "[companion] WARN: CLI update failed"
fi

# Companion refuses to run as root (Claude SDK CLI guard) — drop to jervis user
if [ "$(id -u)" = "0" ]; then
    chmod -R a+w "$WORKSPACE" 2>/dev/null || true
    cp /root/.gitconfig /home/jervis/.gitconfig 2>/dev/null || true
    chmod 644 /home/jervis/.gitconfig 2>/dev/null || true
    export HOME=/home/jervis
    export USER=jervis
    export LOGNAME=jervis
    CMD="umask 000 && /opt/venv/bin/python3 /opt/jervis/companion_sdk_runner.py"
    exec su --preserve-environment jervis -c "$CMD"
fi

exec /opt/venv/bin/python3 /opt/jervis/companion_sdk_runner.py
