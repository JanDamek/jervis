#!/bin/bash
# =============================================================================
# jervis-coding-agent entrypoint
#
# Invoked by AgentJobDispatcher as the container entrypoint of a K8s Job.
# The orchestrator has already prepared $WORKSPACE as a per-agent git
# worktree on $EXPECTED_BRANCH, and written .jervis/brief.md + .jervis/CLAUDE.md.
#
# Required env:
#   AGENT_JOB_ID     — AgentJobRecord.id (24 char hex, never sliced)
#   WORKSPACE        — per-agent git worktree path (on shared PVC)
#   EXPECTED_BRANCH  — expected checkout branch name
#   one of: CLAUDE_CODE_OAUTH_TOKEN | ANTHROPIC_API_KEY
#
# Optional env (from AgentJobDispatcher):
#   GIT_USER_NAME, GIT_USER_EMAIL                  — git commit identity
#   GPG_PRIVATE_KEY, GPG_KEY_ID, GPG_PASSPHRASE    — signed-commit config
#   GIT_CREDENTIALS_USERNAME, GIT_CREDENTIALS_PASSWORD  — https push creds
#   MCP_SERVER_URL, MCP_API_TOKEN                  — Claude MCP client config
#                                                    (MCP_API_TOKEN may be
#                                                     comma-separated; first
#                                                     token is used)
#
# Exit contract:
#   - writes $WORKSPACE/.jervis/result.json (JSON: agentJobId, success, summary,
#     branch, changedFiles, timestamp). Watcher (3b.4) parses this.
#   - non-zero exit on any setup failure; Claude's own exit code is propagated
#     when Claude was launched.
# =============================================================================
set -euo pipefail

: "${AGENT_JOB_ID:?AGENT_JOB_ID required}"
: "${WORKSPACE:?WORKSPACE required}"
: "${EXPECTED_BRANCH:?EXPECTED_BRANCH required}"

RESULT_FILE="$WORKSPACE/.jervis/result.json"
BRIEF_FILE="$WORKSPACE/.jervis/brief.md"
CLAUDE_MD="$WORKSPACE/.jervis/CLAUDE.md"
# .mcp.json is generated at runtime from MCP_* env in /tmp (never persisted
# to the PVC — tokens must not end up on shared storage).
MCP_CONFIG_PATH="/tmp/jervis-mcp.json"

mkdir -p "$(dirname "$RESULT_FILE")"

log() { echo "[coding-agent] $*"; }

# ---- Result writer (must work even if git state is weird) ----
write_result() {
    local success=$1
    local summary=$2
    local branch=""
    local commit_sha=""
    local changed_json="[]"
    if [ -d "$WORKSPACE/.git" ] || [ -f "$WORKSPACE/.git" ]; then
        branch=$(git -C "$WORKSPACE" branch --show-current 2>/dev/null || echo "")
        commit_sha=$(git -C "$WORKSPACE" rev-parse HEAD 2>/dev/null || echo "")
        changed_json=$(WORKSPACE="$WORKSPACE" python3 - <<'PYEOF' 2>/dev/null || echo "[]"
import json, os, subprocess
ws = os.environ['WORKSPACE']
def run(*args):
    return subprocess.check_output(['git', '-C', ws, *args], text=True, stderr=subprocess.DEVNULL)
try:
    d = run('diff', '--name-only', 'HEAD')
    s = run('diff', '--name-only', '--cached')
    u = run('ls-files', '--others', '--exclude-standard')
    files = sorted({f for f in (d + s + u).splitlines() if f and not f.startswith('.jervis/')})
    print(json.dumps(files))
except Exception:
    print('[]')
PYEOF
        )
    fi
    _JERVIS_SUMMARY="$summary" \
    _JERVIS_BRANCH="$branch" \
    _JERVIS_COMMIT_SHA="$commit_sha" \
    _JERVIS_SUCCESS="$success" \
    _JERVIS_JOB="$AGENT_JOB_ID" \
    _JERVIS_CHANGED="$changed_json" \
    _JERVIS_RESULT_FILE="$RESULT_FILE" \
    python3 - <<'PYEOF'
import datetime, json, os
with open(os.environ['_JERVIS_RESULT_FILE'], 'w') as f:
    json.dump({
        'agentJobId': os.environ['_JERVIS_JOB'],
        'success': os.environ['_JERVIS_SUCCESS'] == 'true',
        'summary': os.environ.get('_JERVIS_SUMMARY', ''),
        'branch': os.environ.get('_JERVIS_BRANCH', ''),
        'commitSha': os.environ.get('_JERVIS_COMMIT_SHA', ''),
        'changedFiles': json.loads(os.environ.get('_JERVIS_CHANGED', '[]') or '[]'),
        'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }, f, indent=2)
PYEOF
}

# ---- Auth check ----
if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    log "ERROR: neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY set"
    write_result false "Claude auth missing (no CLAUDE_CODE_OAUTH_TOKEN / ANTHROPIC_API_KEY)"
    exit 1
fi

# ---- Workspace + git identity setup ----
cd "$WORKSPACE"
mkdir -p .jervis

git config --global --add safe.directory "$WORKSPACE"
git config --global --add safe.directory '*'
rm -f .git/index.lock 2>/dev/null || true

if [ -n "${GIT_USER_NAME:-}" ]; then
    git config --global user.name "$GIT_USER_NAME"
fi
if [ -n "${GIT_USER_EMAIL:-}" ]; then
    git config --global user.email "$GIT_USER_EMAIL"
fi

# Keep .jervis/ out of commits by default (brief, result.json, logs).
GLOBAL_IGNORE="/tmp/.jervis-global-gitignore"
printf '.jervis/\n' > "$GLOBAL_IGNORE"
git config --global core.excludesFile "$GLOBAL_IGNORE"

# ---- GPG import (only if orchestrator provided a key) ----
if [ -n "${GPG_PRIVATE_KEY:-}" ]; then
    log "importing GPG key ${GPG_KEY_ID:-<unknown>}"
    echo "$GPG_PRIVATE_KEY" | gpg --batch --import >/dev/null 2>&1 || \
        log "WARN: GPG import reported a non-zero status (continuing)"
    if [ -n "${GPG_KEY_ID:-}" ]; then
        git config --global commit.gpgsign true
        git config --global user.signingkey "$GPG_KEY_ID"
        git config --global gpg.program gpg
        export GPG_TTY=""
        if [ -n "${GPG_PASSPHRASE:-}" ]; then
            KEYGRIP=$(gpg --list-keys --with-keygrip "$GPG_KEY_ID" 2>/dev/null \
                | awk '/Keygrip/{print $3; exit}')
            if [ -n "$KEYGRIP" ]; then
                gpg-connect-agent "PRESET_PASSPHRASE $KEYGRIP -1 $(printf '%s' "$GPG_PASSPHRASE" | xxd -p | tr -d '\n')" /bye \
                    >/dev/null 2>&1 || log "WARN: gpg-connect-agent preset failed"
            fi
        fi
    fi
fi

# ---- Git push credentials (from ConnectionDocument via dispatcher env) ----
if [ -n "${GIT_CREDENTIALS_PASSWORD:-}" ]; then
    CRED_USER="${GIT_CREDENTIALS_USERNAME:-x-access-token}"
    GIT_CREDS_FILE="$HOME/.git-credentials"
    # URL-encode the password in case it contains `:` / `@` / `/` / `#`.
    # The credential file format is `https://user:pass@host` per line.
    ENCODED_PASSWORD=$(GIT_CRED_USER="$CRED_USER" GIT_CRED_PASS="$GIT_CREDENTIALS_PASSWORD" python3 <<'PYEOF'
import os, urllib.parse
u = urllib.parse.quote(os.environ['GIT_CRED_USER'], safe='')
p = urllib.parse.quote(os.environ['GIT_CRED_PASS'], safe='')
print(f"{u}:{p}")
PYEOF
    )
    : > "$GIT_CREDS_FILE"
    chmod 600 "$GIT_CREDS_FILE"
    for host in github.com gitlab.com bitbucket.org; do
        printf 'https://%s@%s\n' "$ENCODED_PASSWORD" "$host" >> "$GIT_CREDS_FILE"
    done
    # If the dispatcher knows the exact remote host, write that too —
    # covers self-hosted Gitea / GitLab / Bitbucket Server.
    if [ -n "${GIT_REMOTE_URL:-}" ]; then
        REMOTE_HOST=$(printf '%s' "$GIT_REMOTE_URL" \
            | sed -E 's#^https?://([^/@:]+(:[0-9]+)?).*#\1#')
        if [ -n "$REMOTE_HOST" ]; then
            printf 'https://%s@%s\n' "$ENCODED_PASSWORD" "$REMOTE_HOST" >> "$GIT_CREDS_FILE"
        fi
    fi
    git config --global credential.helper "store --file=$GIT_CREDS_FILE"
    log "git-credentials wired (helper=store, hosts=$(wc -l <"$GIT_CREDS_FILE"))"
fi

# ---- Verify workspace is on the expected branch ----
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
if [ "$CURRENT_BRANCH" != "$EXPECTED_BRANCH" ]; then
    log "ERROR: workspace branch '$CURRENT_BRANCH' != expected '$EXPECTED_BRANCH'"
    write_result false "Workspace on wrong branch: '$CURRENT_BRANCH' (expected '$EXPECTED_BRANCH')"
    exit 1
fi
# Note: no pull/rebase here. Sub-task agents work in isolated worktrees;
# merge of sub-branches → task branch is the server's responsibility post-DONE.
# Any auto-rebase here would be destructive against another agent's local state.

# ---- Brief + system prompt ----
if [ ! -f "$BRIEF_FILE" ]; then
    log "ERROR: brief missing at $BRIEF_FILE"
    write_result false "Missing brief at .jervis/brief.md"
    exit 1
fi

log "job=$AGENT_JOB_ID branch=$EXPECTED_BRANCH brief=$(wc -c <"$BRIEF_FILE") bytes"

# ---- Render Claude MCP config from env (never touches PVC) ----
if [ -n "${MCP_SERVER_URL:-}" ] && [ -n "${MCP_API_TOKEN:-}" ]; then
    # MCP_API_TOKEN may be comma-separated (secret holds all accepted tokens
    # for the server); pick the first one for the outgoing Bearer header.
    FIRST_TOKEN="${MCP_API_TOKEN%%,*}"
    MCP_SERVER_URL="$MCP_SERVER_URL" FIRST_TOKEN="$FIRST_TOKEN" \
        MCP_CONFIG_PATH="$MCP_CONFIG_PATH" python3 <<'PYEOF'
import json, os
config = {
    "mcpServers": {
        "jervis-mcp": {
            "type": "http",
            "url": os.environ['MCP_SERVER_URL'],
            "headers": {"Authorization": f"Bearer {os.environ['FIRST_TOKEN']}"},
        }
    }
}
with open(os.environ['MCP_CONFIG_PATH'], 'w') as f:
    json.dump(config, f)
PYEOF
    chmod 600 "$MCP_CONFIG_PATH"
    log "MCP config rendered at $MCP_CONFIG_PATH (server=$MCP_SERVER_URL)"
else
    log "WARN: MCP_SERVER_URL/MCP_API_TOKEN missing — Claude runs without jervis-mcp tools"
fi

# ---- Run Claude Code CLI headless ----
# Use --append-system-prompt-file (file variant) instead of
# --append-system-prompt "$(cat …)" — passing 4 KB+ system prompt as
# inline argv hit ENAMETOOLONG on Claude CLI 2.x, which appears to
# interpret long values as file paths and call open() on them.
#
# --output-format stream-json emits JSONL events (one JSON / line) for
# every assistant message, tool_use, tool_result, system event. Claude
# CLI requires --verbose alongside stream-json. We tee output so the
# pod stdout flows to fluent-bit → Kibana (operator archive) AND the
# JSONL persists to .jervis/claude-stream.jsonl for the backend narrative
# parser (AgentNarrativeEvent stream — Fáze J).
CLAUDE_ARGS=(--dangerously-skip-permissions --print --output-format stream-json --verbose)
if [ -f "$CLAUDE_MD" ]; then
    CLAUDE_ARGS+=(--append-system-prompt-file "$CLAUDE_MD")
fi
if [ -f "$MCP_CONFIG_PATH" ]; then
    CLAUDE_ARGS+=(--mcp-config "$MCP_CONFIG_PATH")
fi

CLAUDE_STREAM_FILE="$WORKSPACE/.jervis/claude-stream.jsonl"
: > "$CLAUDE_STREAM_FILE"

set +e
claude "${CLAUDE_ARGS[@]}" "$(cat "$BRIEF_FILE")" | tee "$CLAUDE_STREAM_FILE"
CLAUDE_EC=${PIPESTATUS[0]}
set -e

if [ "$CLAUDE_EC" -eq 0 ]; then
    # If Claude already wrote result.json via MCP report_done, leave it alone.
    if [ ! -f "$RESULT_FILE" ]; then
        write_result true "Claude exited 0 (no MCP report_done call recorded)"
    fi
    log "done OK (exit 0)"
    exit 0
fi

if [ ! -f "$RESULT_FILE" ]; then
    write_result false "Claude exited $CLAUDE_EC"
fi
log "done FAIL (exit $CLAUDE_EC)"
exit "$CLAUDE_EC"
