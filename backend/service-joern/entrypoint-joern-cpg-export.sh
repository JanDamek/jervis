#!/bin/bash
# /opt/jervis/entrypoint-joern-cpg-export.sh
#
# Entrypoint for Joern CPG Export K8s Job.
# Runs the baked-in CPG export script against project code on PVC.
# Produces a pruned JSON export for ArangoDB ingest.
#
# Required env vars:
#   WORKSPACE   – path to project directory on shared PVC
#
# Output:
#   $WORKSPACE/.jervis/cpg-export.json         – Pruned CPG as JSON
#   $WORKSPACE/.jervis/cpg-export-status.json  – Job status metadata
#
set -euo pipefail

WORKSPACE="${WORKSPACE:?WORKSPACE env is required}"
EXPORT_SCRIPT="/opt/jervis/cpg-export-query.sc"
RESULT_FILE="$WORKSPACE/.jervis/cpg-export.json"
STATUS_FILE="$WORKSPACE/.jervis/cpg-export-status.json"

echo "=== JERVIS JOERN CPG EXPORT START ==="
echo "Workspace: $WORKSPACE"
echo "Export script: $EXPORT_SCRIPT"
echo "======================================="

if [ ! -f "$EXPORT_SCRIPT" ]; then
    echo "ERROR: Export script not found at $EXPORT_SCRIPT"
    python3 -c "
import json
json.dump({
    'exitCode': 1,
    'stderr': 'Export script not found at $EXPORT_SCRIPT',
    'exportFile': ''
}, open('$STATUS_FILE', 'w'))
"
    exit 1
fi

JOERN="${JOERN_HOME:-/opt/joern}/joern-cli/joern"

# Run Joern CPG export
set +e
"$JOERN" --script "$EXPORT_SCRIPT" --param inputPath="$WORKSPACE" \
    > /tmp/joern-stdout.txt 2>/tmp/joern-stderr.txt
EXIT_CODE=$?
set -e

# Write status file
mkdir -p "$(dirname "$STATUS_FILE")"
python3 -c "
import json, os
stdout = open('/tmp/joern-stdout.txt').read()
stderr = open('/tmp/joern-stderr.txt').read()[:2000]
export_exists = os.path.exists('$RESULT_FILE')
export_size = os.path.getsize('$RESULT_FILE') if export_exists else 0
json.dump({
    'exitCode': $EXIT_CODE,
    'stdout': stdout[:2000],
    'stderr': stderr,
    'exportFile': '$RESULT_FILE' if export_exists else '',
    'exportSizeBytes': export_size,
}, open('$STATUS_FILE', 'w'))
"

echo "=== JERVIS JOERN CPG EXPORT DONE (exit=$EXIT_CODE) ==="
exit $EXIT_CODE
