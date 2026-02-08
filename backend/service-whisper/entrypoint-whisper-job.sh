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
RESULT_FILE="$WORKSPACE/.jervis/whisper-result.json"

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
python3 /opt/jervis/whisper/whisper_runner.py "$AUDIO_FILE" "$WHISPER_TASK" "$WHISPER_MODEL" > "$RESULT_FILE"
EXIT_CODE=$?

echo "=== JERVIS WHISPER JOB DONE (exit=$EXIT_CODE) ==="
exit $EXIT_CODE
