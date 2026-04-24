# jervis-whisper — faster-whisper STT + speaker diarization on the VD GPU VM

Speech-to-text using `faster-whisper` (CTranslate2-backed) with optional
`pyannote.audio` speaker diarization. Hosts pod-to-pod gRPC
(`jervis.whisper.WhisperService`) on :5501 plus a FastAPI REST/SSE surface
on :8788 for the legacy streaming path.

## Where it runs

**Not in K8s.** Whisper lives directly on the VD GPU VM
(`ollama.lan.mazlusek.com`, p40-2) as a Docker container (`--gpus all
--network host`), sharing the P40 with Ollama and XTTS. See
`memory/feedback-audio-services-on-vd.md` for the rationale (CUDA VRAM on
CPU-only K8s nodes is a non-starter; co-locating with Ollama wins on the
shared idle-semaphore path).

Kotlin consumers (`WhisperGrpcClient.kt`) dial
`ollama.lan.mazlusek.com:5502` directly over h2c (XTTS owns :5501).

## Deploy

Run from the repo root on your Mac:

```bash
./k8s/deploy_whisper_gpu.sh                  # build + push + pull + run
SKIP_BUILD=1 ./k8s/deploy_whisper_gpu.sh     # pull + run only (faster redeploy)
HF_TOKEN=hf_xxx ./k8s/deploy_whisper_gpu.sh  # diarization token
                                             # (auto-fetched from K8s jervis-secrets when omitted)
```

The script:

1. Builds `Dockerfile.gpu` on the Mac (`buildx --platform linux/amd64`)
   and pushes to `registry.damek-soft.eu/jandamek/jervis-whisper-gpu:latest`.
2. SSHes to the VD, stops + disables the legacy `jervis-whisper` systemd
   unit (one-shot migration).
3. Resolves HF_TOKEN (env > K8s `jervis-secrets` > empty).
4. `docker pull` + `docker run -d --gpus all --network host --restart
   unless-stopped` with bind-mount to `/opt/jervis/hf-cache` (shared
   model-weights cache with XTTS).
5. Polls `:8786/health`.

Models (`tiny`, `base`, `small`, `medium`, `large-v3`) download on first
request and persist in `/opt/jervis/hf-cache` via the bind mount.

## What's on disk

Host paths (bind-mounted into the container):

```
/opt/jervis/hf-cache/         # faster-whisper + pyannote models (~2-5 GB)
                              # shared with XTTS
```

Everything else lives inside the image — no venv, no source files on
the host. To change the image, edit `backend/service-whisper/Dockerfile.gpu`
and re-run the deploy script.

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

Docker container stdout/stderr is picked up by the VD's fluent-bit
(not the K8s DaemonSet) and forwarded to the cluster Elasticsearch.
Query Kibana for the container name — see
`memory/feedback-use-kibana-for-logs.md` for the console-proxy template.

Ad-hoc on the VD:
`ssh damekjan@ollama.lan.mazlusek.com 'docker logs --tail 100 -f jervis-whisper-gpu'`.
