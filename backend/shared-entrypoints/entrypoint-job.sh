#!/bin/bash
# /opt/jervis/entrypoint-job.sh
#
# Universal entrypoint for coding agent K8s Jobs.
# Agent is the EXECUTOR – performs the assigned step and exits.
# Git operations, commit, push -> controlled by ORCHESTRATOR.
#
# Required env vars:
#   WORKSPACE   – path to existing codebase on shared PVC
#   TASK_ID     – unique task identifier
#   AGENT_TYPE  – aider | openhands | claude | junie
#
# Optional env vars:
#   ALLOW_GIT       – "true" to allow git operations (default: "false")
#   CLAUDE_MODEL    – model override for Claude Code
#   GPG_PRIVATE_KEY – armored GPG private key for commit signing
#   GPG_KEY_ID      – GPG key ID (fingerprint) for signing
#   GPG_PASSPHRASE  – passphrase for the GPG key (if protected)
#   GIT_USER_NAME   – git user.name for commits (global fallback)
#   GIT_USER_EMAIL  – git user.email for commits (global fallback)
#
set -euo pipefail

WORKSPACE="${WORKSPACE:?WORKSPACE env is required}"
TASK_ID="${TASK_ID:?TASK_ID env is required}"
AGENT_TYPE="${AGENT_TYPE:?AGENT_TYPE env is required}"
RESULT_FILE="$WORKSPACE/.jervis/result.json"

# Trap: write error result on unexpected exit so orchestrator gets error details
trap '_trap_exit' ERR
_trap_exit() {
    local exit_code=$?
    local line_no=${BASH_LINENO[0]:-0}
    echo "ERROR at line $line_no (exit $exit_code)"
    # Try to write result.json even if workspace setup failed
    mkdir -p "$(dirname "$RESULT_FILE")" 2>/dev/null || true
    python3 -c "
import json, datetime
result = {
    'taskId': '${TASK_ID}',
    'success': False,
    'summary': 'Entrypoint failed at line $line_no with exit code $exit_code. Check job logs for details.',
    'agentType': '${AGENT_TYPE}',
    'changedFiles': [],
    'timestamp': datetime.datetime.now().isoformat()
}
with open('${RESULT_FILE}', 'w') as f:
    json.dump(result, f, indent=2)
" 2>/dev/null || true
}

cd "$WORKSPACE"

# --- Global gitignore: prevent coding agent artifacts from leaking into commits ---
GLOBAL_GITIGNORE="/tmp/.jervis-global-gitignore"
cat > "$GLOBAL_GITIGNORE" <<'GITIGNORE'
# Jervis orchestrator artifacts (auto-generated, never commit)
.jervis/
.claude/
CLAUDE.md
.aider.conf.yml
# Generated environment files (workspace_manager)
.env
.env.*
GITIGNORE
git config --global core.excludesFile "$GLOBAL_GITIGNORE"

# --- Git user identity (global fallback — local config takes precedence) ---
if [ -n "${GIT_USER_NAME:-}" ]; then
    git config --global user.name "${GIT_USER_NAME}"
fi
if [ -n "${GIT_USER_EMAIL:-}" ]; then
    git config --global user.email "${GIT_USER_EMAIL}"
fi

# Import GPG key for commit signing (if provided by orchestrator)
if [ -n "${GPG_PRIVATE_KEY:-}" ]; then
    echo "Importing GPG key: ${GPG_KEY_ID:-unknown}"
    echo "$GPG_PRIVATE_KEY" | gpg --batch --import 2>/dev/null || true
    git config --global commit.gpgsign true
    git config --global user.signingkey "${GPG_KEY_ID}"
    if [ -n "${GPG_PASSPHRASE:-}" ]; then
        GPG_KEYGRIP=$(gpg --list-keys --with-keygrip "${GPG_KEY_ID}" 2>/dev/null | grep -m1 Keygrip | awk '{print $3}')
        if [ -n "$GPG_KEYGRIP" ]; then
            gpg-connect-agent "PRESET_PASSPHRASE $GPG_KEYGRIP -1 $(echo -n "$GPG_PASSPHRASE" | xxd -p | tr -d '\n')" /bye 2>/dev/null || true
        fi
    fi
    export GPG_TTY=""
    git config --global gpg.program gpg
    echo "GPG signing configured"
fi

# Read instructions from orchestrator
INSTRUCTIONS=""
if [ -f ".jervis/instructions.md" ]; then
    INSTRUCTIONS=$(cat .jervis/instructions.md)
else
    echo "ERROR: No instructions found at .jervis/instructions.md"
    exit 1
fi

# Parse files list from task.json
FILES=""
if [ -f ".jervis/task.json" ]; then
    FILES=$(python3 -c "
import sys, json
try:
    data = json.load(open('.jervis/task.json'))
    print(' '.join(data.get('files', [])))
except:
    pass
" 2>/dev/null || true)
fi

# Write result helper
write_result() {
    local success=$1
    local summary=$2
    mkdir -p "$(dirname "$RESULT_FILE")"

    # Capture changed files via git diff (if git repo)
    local changed_files="[]"
    if [ -d ".git" ]; then
        changed_files=$(python3 -c "
import json, subprocess
try:
    out = subprocess.check_output(['git', 'diff', '--name-only', 'HEAD'], text=True, stderr=subprocess.DEVNULL)
    staged = subprocess.check_output(['git', 'diff', '--name-only', '--cached'], text=True, stderr=subprocess.DEVNULL)
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], text=True, stderr=subprocess.DEVNULL)
    files = set(filter(None, (out + staged + untracked).splitlines()))
    # Exclude .jervis/ internal files
    files = [f for f in files if not f.startswith('.jervis/')]
    print(json.dumps(sorted(files)))
except:
    print('[]')
" 2>/dev/null || echo "[]")
    fi

    # Convert bash true/false to Python True/False
    local py_success="False"
    [ "$success" = "true" ] && py_success="True"

    python3 -c "
import json, datetime
result = {
    'taskId': '$TASK_ID',
    'success': $py_success,
    'summary': '''$summary''',
    'agentType': '$AGENT_TYPE',
    'changedFiles': $changed_files,
    'timestamp': datetime.datetime.now().isoformat()
}
with open('$RESULT_FILE', 'w') as f:
    json.dump(result, f, indent=2)
"
}

# Git permission mode
ALLOW_GIT="${ALLOW_GIT:-false}"

# Build agent command based on type
case "$AGENT_TYPE" in
    aider)
        CMD="aider --yes"
        if [ "$ALLOW_GIT" = "true" ]; then
            # Normal mode – Aider may commit
            :
        else
            CMD="$CMD --no-auto-commits"
        fi
        CMD="$CMD --message \"$INSTRUCTIONS\""
        if [ -n "$FILES" ]; then
            CMD="$CMD $FILES"
        fi
        if [ -f ".jervis/kb-context.md" ]; then
            CMD="$CMD --read .jervis/kb-context.md"
        fi
        ;;
    openhands)
        CMD="python3 -m openhands.core.main --task \"$INSTRUCTIONS\" --max-iterations 10"
        ;;
    claude)
        # Verify auth (either CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY must be non-empty)
        if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -z "${ANTHROPIC_API_KEY:-}" ]; then
            echo "ERROR: Neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY set in K8s secret"
            write_result "false" "Missing Claude authentication — add ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN to jervis-secrets"
            exit 1
        fi
        # Claude Agent SDK (Python) — replaces direct CLI invocation
        CMD="python3 /opt/jervis/claude_sdk_runner.py"
        ;;
    junie)
        CMD="junie \"$INSTRUCTIONS\""
        ;;
    *)
        write_result "false" "Unknown agent type: $AGENT_TYPE"
        exit 1
        ;;
esac

# --- Auto-update Claude Code CLI from NAS cache (claude agent only) ---
if [ "$AGENT_TYPE" = "claude" ] && [ -f /opt/jervis/update-claude-cli.sh ]; then
    source /opt/jervis/update-claude-cli.sh
fi

echo "=== JERVIS AGENT START: $AGENT_TYPE / $TASK_ID ==="
echo "Workspace: $WORKSPACE"
echo "Allow git: $ALLOW_GIT"
echo "Instructions length: ${#INSTRUCTIONS} chars"
echo "==================================================="

# Capture output for error reporting — orchestrator needs detailed error messages
AGENT_OUTPUT_FILE="/tmp/jervis-agent-output-$$"
if eval "$CMD" 2>&1 | tee "$AGENT_OUTPUT_FILE"; then
    write_result "true" "Agent completed successfully."
    echo "=== JERVIS AGENT DONE: $AGENT_TYPE / $TASK_ID (SUCCESS) ==="
else
    EXIT_CODE=${PIPESTATUS[0]}
    # Capture last 50 lines of output for error diagnosis
    LAST_OUTPUT=$(tail -50 "$AGENT_OUTPUT_FILE" 2>/dev/null || echo "No output captured")
    write_result "false" "Agent exited with code $EXIT_CODE. Output: $LAST_OUTPUT"
    echo "=== JERVIS AGENT DONE: $AGENT_TYPE / $TASK_ID (FAILED: $EXIT_CODE) ==="
    rm -f "$AGENT_OUTPUT_FILE"
    exit $EXIT_CODE
fi
rm -f "$AGENT_OUTPUT_FILE"
