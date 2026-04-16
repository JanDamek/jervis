#!/bin/bash
# Live tail of Claude companion outbox for a given meeting assistant session.
#
# Usage:
#   ./scripts/companion_tail.sh <session-id>            # prints suggestions/answers as they arrive
#   ./scripts/companion_tail.sh mtg-abc123 60           # override max_age_seconds (default 45)
#
# Streams Server-Sent Events from the orchestrator companion router and
# pretty-prints each outbox event. Use as a backup if the normal Desktop/RPC
# event stream fails during a meeting.
set -euo pipefail

SESSION_ID="${1:-}"
if [ -z "$SESSION_ID" ]; then
    echo "usage: $0 <session-id> [max_age_seconds]" >&2
    exit 1
fi
MAX_AGE="${2:-45}"

POD=$(kubectl -n jervis get pods -l app=jervis-orchestrator -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
if [ -z "$POD" ]; then
    echo "ERROR: orchestrator pod not found" >&2
    exit 1
fi

echo "[companion-tail] session=$SESSION_ID pod=$POD ttl=${MAX_AGE}s"
exec kubectl -n jervis exec -i "$POD" -- python3 -u -c "
import urllib.request, json, sys
url = 'http://localhost:8090/companion/session/${SESSION_ID}/stream?max_age_seconds=${MAX_AGE}'
with urllib.request.urlopen(url, timeout=None) as r:
    for raw in r:
        line = raw.decode('utf-8', 'replace').rstrip()
        if not line.startswith('data:'):
            continue
        payload = line[5:].strip()
        if not payload:
            continue
        try:
            ev = json.loads(payload)
        except Exception:
            continue
        etype = ev.get('type', '?')
        content = ev.get('content', '').strip()
        ts = ev.get('ts', '')[:19].replace('T', ' ')
        if etype in ('answer', 'suggestion'):
            tag = 'HINT' if etype == 'suggestion' else 'ANSWER'
            print(f'[{ts}] {tag}: {content}', flush=True)
        else:
            print(f'[{ts}] ({etype}) {content[:120]}', flush=True)
"
