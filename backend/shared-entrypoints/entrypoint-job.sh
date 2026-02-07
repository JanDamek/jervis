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
#   ALLOW_GIT   – "true" to allow git operations (default: "false")
#   CLAUDE_MODEL – model override for Claude Code
#
set -euo pipefail

WORKSPACE="${WORKSPACE:?WORKSPACE env is required}"
TASK_ID="${TASK_ID:?TASK_ID env is required}"
AGENT_TYPE="${AGENT_TYPE:?AGENT_TYPE env is required}"
RESULT_FILE="$WORKSPACE/.jervis/result.json"

cd "$WORKSPACE"

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

    python3 -c "
import json, datetime
result = {
    'taskId': '$TASK_ID',
    'success': $success,
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
        CMD="claude --dangerously-skip-permissions --output-format json"
        if [ -n "${CLAUDE_MODEL:-}" ]; then
            CMD="$CMD --model $CLAUDE_MODEL"
        fi
        CMD="$CMD \"$INSTRUCTIONS\""
        # CLAUDE.md controls rules – git permission is in the generated CLAUDE.md
        ;;
    junie)
        CMD="junie \"$INSTRUCTIONS\""
        ;;
    *)
        write_result "false" "Unknown agent type: $AGENT_TYPE"
        exit 1
        ;;
esac

echo "=== JERVIS AGENT START: $AGENT_TYPE / $TASK_ID ==="
echo "Workspace: $WORKSPACE"
echo "Allow git: $ALLOW_GIT"
echo "Instructions length: ${#INSTRUCTIONS} chars"
echo "==================================================="

if eval "$CMD" 2>&1; then
    write_result "true" "Agent completed successfully."
    echo "=== JERVIS AGENT DONE: $AGENT_TYPE / $TASK_ID (SUCCESS) ==="
else
    EXIT_CODE=$?
    write_result "false" "Agent exited with code $EXIT_CODE"
    echo "=== JERVIS AGENT DONE: $AGENT_TYPE / $TASK_ID (FAILED: $EXIT_CODE) ==="
    exit $EXIT_CODE
fi
