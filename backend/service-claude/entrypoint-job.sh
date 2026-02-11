#!/bin/bash
set -e

# Jervis Claude CLI Agent Entrypoint
# Dual-mode: CODING (default) or MCP server

TASK_ID="${TASK_ID:-unknown}"
WORKSPACE_PATH="${WORKSPACE_PATH:-/app}"
MODE="${MODE:-coding}"

echo "[jervis-claude] Task: $TASK_ID"
echo "[jervis-claude] Workspace: $WORKSPACE_PATH"
echo "[jervis-claude] Mode: $MODE"

cd "$WORKSPACE_PATH"

# Verify auth (either CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY)
if [ -z "$CLAUDE_CODE_OAUTH_TOKEN" ] && [ -z "$ANTHROPIC_API_KEY" ]; then
    echo "[jervis-claude] ERROR: Neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY set"
    exit 1
fi

if [ -n "$CLAUDE_CODE_OAUTH_TOKEN" ]; then
    echo "[jervis-claude] Using OAuth token (Max/Pro subscription)"
else
    echo "[jervis-claude] Using API key (pay-per-token)"
fi

# Read instructions from .jervis/instructions.txt (prepared by workspace_manager)
INSTRUCTIONS_FILE=".jervis/instructions.txt"
if [ ! -f "$INSTRUCTIONS_FILE" ]; then
    echo "[jervis-claude] ERROR: Instructions file not found: $INSTRUCTIONS_FILE"
    exit 1
fi

INSTRUCTIONS=$(cat "$INSTRUCTIONS_FILE")
echo "[jervis-claude] Instructions loaded (${#INSTRUCTIONS} chars)"

# Execute based on mode
if [ "$MODE" = "mcp" ]; then
    echo "[jervis-claude] Starting MCP server mode..."
    # MCP server mode — stdio transport for KB access
    exec python /mcp-server/server.py
else
    echo "[jervis-claude] Starting coding mode..."
    # Coding mode — execute task with dangerously-skip-permissions
    exec claude --dangerously-skip-permissions "$INSTRUCTIONS"
fi
