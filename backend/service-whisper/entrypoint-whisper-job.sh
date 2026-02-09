#!/bin/bash
# /opt/jervis/entrypoint-whisper-job.sh
#
# Entrypoint for Whisper K8s Job.
# Reads audio file path from env, transcribes using faster-whisper, writes result to PVC.
#
# Required env vars:
#   WORKSPACE   – base directory on shared PVC
#   AUDIO_FILE  – path to audio file on PVC (relative to WORKSPACE or absolute)
#
# Optional env vars:
#   WHISPER_TASK  – "transcribe" (default) or "translate"
#   WHISPER_MODEL – model name (default: "base")
#
# Output:
#   $WORKSPACE/.jervis/whisper-result.json – JSON with text, segments
#
set -euo pipefail

WORKSPACE="${WORKSPACE:?WORKSPACE env is required}"
AUDIO_FILE="${AUDIO_FILE:?AUDIO_FILE env is required}"
WHISPER_TASK="${WHISPER_TASK:-transcribe}"
WHISPER_MODEL="${WHISPER_MODEL:-base}"
RESULT_FILE="${RESULT_FILE:-$WORKSPACE/.jervis/whisper-result.json}"

# Resolve relative paths
if [[ ! "$AUDIO_FILE" = /* ]]; then
    AUDIO_FILE="$WORKSPACE/$AUDIO_FILE"
fi

if [ ! -f "$AUDIO_FILE" ]; then
    echo "ERROR: Audio file not found at $AUDIO_FILE"
    mkdir -p "$(dirname "$RESULT_FILE")"
    python3 -c "
import json
json.dump({'text': '', 'segments': [], 'error': 'Audio file not found: $AUDIO_FILE'}, open('$RESULT_FILE', 'w'))
"
    exit 1
fi

echo "=== JERVIS WHISPER JOB START ==="
echo "Workspace: $WORKSPACE"
echo "Audio file: $AUDIO_FILE"
echo "Task: $WHISPER_TASK"
echo "Model: $WHISPER_MODEL"
echo "================================="

mkdir -p "$(dirname "$RESULT_FILE")"

# Run whisper and capture both stdout and stderr
STDOUT_FILE=$(mktemp)
STDERR_FILE=$(mktemp)
set +e
python3 /opt/jervis/whisper/whisper_runner.py "$AUDIO_FILE" "$WHISPER_TASK" "$WHISPER_MODEL" > "$STDOUT_FILE" 2> "$STDERR_FILE"
EXIT_CODE=$?
set -e

if [ $EXIT_CODE -eq 0 ]; then
    cp "$STDOUT_FILE" "$RESULT_FILE"
    echo "=== JERVIS WHISPER JOB DONE (exit=0) ==="
else
    echo "Whisper runner failed (exit=$EXIT_CODE):"
    cat "$STDERR_FILE"
    # Write error as valid JSON result so server can read the reason
    python3 -c "
import json, sys
stderr = open('$STDERR_FILE').read().strip()
json.dump({'text': '', 'segments': [], 'error': f'Whisper failed (exit=$EXIT_CODE): {stderr}'}, open('$RESULT_FILE', 'w'))
"
    echo "=== JERVIS WHISPER JOB DONE (exit=0, error written to result) ==="
fi

rm -f "$STDOUT_FILE" "$STDERR_FILE"
exit 0
