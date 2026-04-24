# jervis-whisper — faster-whisper STT + speaker diarization on the VD GPU VM

Speech-to-text using `faster-whisper` (CTranslate2-backed) with optional
`pyannote.audio` speaker diarization. Hosts pod-to-pod gRPC
(`jervis.whisper.WhisperService`) on :5501 plus a FastAPI REST/SSE surface
on :8788 for the legacy streaming path.

## Where it runs

**Not in K8s.** Whisper lives directly on the VD GPU VM
(`ollama.lan.mazlusek.com`, p40-2) as a systemd user service, sharing the
P40 GPU with Ollama and XTTS. See
`memory/feedback-audio-services-on-vd.md` for the rationale (CUDA VRAM on
CPU-only K8s nodes is a non-starter; co-locating with Ollama wins on the
shared idle-semaphore path).

Kotlin consumers (`WhisperGrpcClient.kt`) dial
`ollama.lan.mazlusek.com:5501` directly over h2c.

## Deploy

Run from the repo root on your Mac (SSH-pushes code + deps to the VM):

```bash
./k8s/deploy_whisper_gpu.sh                    # uses SSH key auth (~/.ssh/id_starkys)
SSH_PASS=secret ./k8s/deploy_whisper_gpu.sh    # or sshpass
HF_TOKEN=hf_xxx ./k8s/deploy_whisper_gpu.sh    # enables diarization (auto-fetched from jervis-secrets when omitted)
```

The script:

1. Ensures `/opt/jervis/whisper/venv` exists on the VM.
2. Installs deps (`faster-whisper`, `pyannote.audio`, `fastapi`, `grpcio`,
   `torch` / `torchaudio` CUDA 12.4 wheels).
3. Copies `whisper_runner.py` / `whisper_rest_server.py` / `grpc_server.py`
   plus `libs/jervis_contracts/` (editable install) to the VM.
4. Pre-downloads `tiny`, `base`, `small`, `medium`, and `large-v3` models.
5. Drops the HuggingFace token into systemd env (required for `pyannote`
   speaker-diarization models behind gated HF repos).
6. (Re-)creates the `jervis-whisper` systemd user unit and restarts it.
7. Health-checks `:8788/health`.

## What's on disk

```
/opt/jervis/whisper/
├── venv/                     # Python 3.11 + CUDA 12.4 torch + faster-whisper
├── whisper_runner.py         # faster-whisper wrapper + diarization glue
├── whisper_rest_server.py    # FastAPI /transcribe + SSE streaming
├── grpc_server.py            # jervis.whisper.WhisperService impl
└── libs/jervis_contracts/    # local editable install
~/.cache/huggingface/hub/     # downloaded models (first-run cache)
```

## Diarization note

Diarization uses `pyannote/speaker-diarization-3.1` via HuggingFace, which
is a **gated repo** — the HF_TOKEN env on the systemd unit must grant
access. Without it `whisper_runner.py` silently skips the diarization step
and returns segments with a single default speaker label. Check in Kibana
for `pyannote.*UnauthorizedAccess` if diarization regresses.

Per `memory/architecture-whisper-diarization.md`: diarization only runs
for the full-meeting transcription path; the real-time stream path passes
`diarize=false` for latency.

## Local dev

Not supported on macOS — CUDA torch + `faster-whisper` CTranslate2 are
Linux + CUDA only. For protocol / contract changes edit on Mac and push
with `deploy_whisper_gpu.sh`; read logs via Kibana.

## Logs

VD's fluent-bit forwards systemd journald of the unit into the cluster
Elasticsearch. Query Kibana with `kubernetes.labels.app: jervis-whisper-gpu`
— see `memory/feedback-use-kibana-for-logs.md` for the console-proxy
template.
