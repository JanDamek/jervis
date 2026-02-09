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
#   WHISPER_OPTIONS – JSON string with all transcription parameters
#   WHISPER_TASK    – "transcribe" (default) or "translate" (legacy, ignored if OPTIONS set)
#   WHISPER_MODEL   – model name (default: "base") (legacy, ignored if OPTIONS set)
#
# Output:
#   RESULT_FILE   – JSON with text, segments
#   PROGRESS_FILE – periodically updated progress JSON
#
set -euo pipefail

WORKSPACE="${WORKSPACE:?WORKSPACE env is required}"
AUDIO_FILE="${AUDIO_FILE:?AUDIO_FILE env is required}"
RESULT_FILE="${RESULT_FILE:-$WORKSPACE/.jervis/whisper-result.json}"
PROGRESS_FILE="${PROGRESS_FILE:-${RESULT_FILE%.json}_progress.json}"

# Build options JSON: prefer WHISPER_OPTIONS env, fall back to individual env vars
if [ -z "${WHISPER_OPTIONS:-}" ]; then
    WHISPER_TASK="${WHISPER_TASK:-transcribe}"
    WHISPER_MODEL="${WHISPER_MODEL:-base}"
    WHISPER_OPTIONS=$(python3 -c "
import json
opts = {'task': '$WHISPER_TASK', 'model': '$WHISPER_MODEL', 'progress_file': '$PROGRESS_FILE'}
print(json.dumps(opts))
")
else
    # Inject progress_file into existing options
    WHISPER_OPTIONS=$(python3 -c "
import json
opts = json.loads('$WHISPER_OPTIONS')
opts['progress_file'] = '$PROGRESS_FILE'
print(json.dumps(opts))
")
fi

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
echo "Result file: $RESULT_FILE"
echo "Progress file: $PROGRESS_FILE"
echo "Options: $WHISPER_OPTIONS"
echo "================================="

mkdir -p "$(dirname "$RESULT_FILE")"

# Run whisper and capture both stdout and stderr
STDOUT_FILE=$(mktemp)
STDERR_FILE=$(mktemp)
set +e
python3 /opt/jervis/whisper/whisper_runner.py "$AUDIO_FILE" "$WHISPER_OPTIONS" > "$STDOUT_FILE" 2> "$STDERR_FILE"
EXIT_CODE=$?
set -e

# Stream stderr to container logs for visibility
cat "$STDERR_FILE"

if [ $EXIT_CODE -eq 0 ]; then
    cp "$STDOUT_FILE" "$RESULT_FILE"
    echo "=== JERVIS WHISPER JOB DONE (exit=0) ==="
else
    echo "Whisper runner failed (exit=$EXIT_CODE):"
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
