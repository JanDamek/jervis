#!/bin/bash
# /opt/jervis/entrypoint-joern-job.sh
#
# Entrypoint for Joern K8s Job.
# Reads a Joern query script from PVC, runs Joern CLI, writes result to PVC.
#
# Required env vars:
#   WORKSPACE   – path to project directory on shared PVC
#
# Expected files on PVC:
#   $WORKSPACE/.jervis/joern-query.sc     – Joern query script (written by KB service)
#
# Output:
#   $WORKSPACE/.jervis/joern-result.json  – JSON with stdout, stderr, exitCode
#
set -euo pipefail

WORKSPACE="${WORKSPACE:?WORKSPACE env is required}"
QUERY_FILE="$WORKSPACE/.jervis/joern-query.sc"
RESULT_FILE="$WORKSPACE/.jervis/joern-result.json"

if [ ! -f "$QUERY_FILE" ]; then
    echo "ERROR: No query file found at $QUERY_FILE"
    python3 -c "
import json
json.dump({'stdout': '', 'stderr': 'No query file found at $QUERY_FILE', 'exitCode': 1}, open('$RESULT_FILE', 'w'))
"
    exit 1
fi

echo "=== JERVIS JOERN JOB START ==="
echo "Workspace: $WORKSPACE"
echo "Query file: $QUERY_FILE"
echo "==============================="

JOERN="${JOERN_HOME:-/opt/joern}/joern-cli/joern"

# Run Joern CLI
set +e
"$JOERN" --script "$QUERY_FILE" --param inputPath="$WORKSPACE" > /tmp/joern-stdout.txt 2>/tmp/joern-stderr.txt
EXIT_CODE=$?
set -e

# Write structured result to PVC
mkdir -p "$(dirname "$RESULT_FILE")"
python3 -c "
import json
stdout = open('/tmp/joern-stdout.txt').read()
stderr = open('/tmp/joern-stderr.txt').read()
json.dump({
    'stdout': stdout,
    'stderr': stderr,
    'exitCode': $EXIT_CODE
}, open('$RESULT_FILE', 'w'))
"

echo "=== JERVIS JOERN JOB DONE (exit=$EXIT_CODE) ==="
exit $EXIT_CODE
