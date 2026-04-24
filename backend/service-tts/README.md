# jervis-tts — XTTS v2 neural TTS on the VD GPU VM

Neural TTS using Coqui XTTS v2 (multilingual voice cloning, Czech + English).
Hosts pod-to-pod gRPC (`jervis.tts.TtsService`) on :5501, plus a dev-only
FastAPI on :8787.

## Where it runs

**Not in K8s.** TTS lives directly on the VD GPU VM
(`ollama.lan.mazlusek.com`, p40-2) as a Docker container (`--gpus all
--network host`), sharing the P40 with Ollama and Whisper. See
`memory/feedback-audio-services-on-vd.md` for the rationale (CUDA VRAM
overhead on CPU-only K8s nodes is a dealbreaker).

Kotlin consumers (`TtsGrpcClient.kt`) dial
`ollama.lan.mazlusek.com:5501` directly over h2c.

## Deploy

Run from the repo root on your Mac. The deploy runs as a Docker
container on the VD (nvidia/cuda:12.4.1-runtime base image), not as
systemd anymore:

```bash
./k8s/deploy_xtts_gpu.sh                   # build + push + pull + run
SKIP_BUILD=1 ./k8s/deploy_xtts_gpu.sh      # pull + run only (faster redeploy)
```

The script:

1. Builds `Dockerfile.gpu` on the Mac (`buildx --platform linux/amd64`)
   and pushes to `registry.damek-soft.eu/jandamek/jervis-xtts-gpu:latest`.
2. SSHes to the VD, stops + disables the legacy `jervis-tts-gpu` systemd
   unit (one-shot migration; idempotent on re-runs).
3. `docker pull` the new image.
4. `docker stop` + `docker rm` the old container, `docker run -d --gpus all
   --network host --restart unless-stopped` with bind-mounts to
   `/opt/jervis/data/tts` (voice samples) and `/opt/jervis/hf-cache`
   (XTTS model weights).
5. Polls `:5501` until the gRPC server is listening.

To add or update a voice reference sample:

```bash
scp -i ~/.ssh/id_starkys voice.wav \
  damekjan@ollama.lan.mazlusek.com:/opt/jervis/data/tts/speakers/speaker.wav
ssh -i ~/.ssh/id_starkys damekjan@ollama.lan.mazlusek.com "docker restart jervis-xtts-gpu"
```

## What's on disk

Host paths (bind-mounted into the container):

```
/opt/jervis/data/tts/
└── speakers/*.wav            # voice reference samples (NOT in git)
/opt/jervis/hf-cache/         # XTTS v2 model cache (~2 GB, shared with Whisper)
```

Everything else lives inside the image — no venv, no editable installs,
no source files on the host. To change the image, edit
`backend/service-tts/Dockerfile.gpu` and re-run the deploy script.

Voice samples are **not** tracked in the repo (`.gitignore` blocks `*.wav` /
`*.aifc` / `*.flac` / `*.mp3`).

## Normalization (CPU-side, on-box)

Text normalization happens in `app/normalizer.py` before XTTS sees the text:

1. **STRIP** rules (regex → delete, e.g. emojis, bracketed notes).
2. **REPLACE** rules (regex → replacement word).
3. **ACRONYM** rules (word-boundary match → spelled-out pronunciation).
4. Standalone numbers → `num2words` (CZ / EN).
5. Sentence splitter + hard 186-char chunker for the XTTS tokenizer limit.

Rules live in the Kotlin server's `ttsRules` MongoDB collection
(scope precedence `PROJECT > CLIENT > GLOBAL`). The TTS pod fetches them
per `SpeakStream` request via `rules_client.py` — acceptable because
~10-20 ms of gRPC is negligible next to 2-5 s of synthesis.

SSOT: [`docs/tts-normalization.md`](../../docs/tts-normalization.md).

## Local dev

Not supported on macOS — `coqui-tts` + CUDA torch are gated by
`sys_platform == 'linux'` in `pyproject.toml`. For protocol-level changes
edit on Mac, push via `deploy_xtts_gpu.sh`, and read logs in Kibana.

## Logs

Docker container stdout/stderr is picked up by the VD's fluent-bit (not
the K8s DaemonSet) and forwarded to the cluster Elasticsearch. Query in
Kibana for the container name — see
`memory/feedback-use-kibana-for-logs.md` for the console-proxy template.

Ad-hoc on the VD: `ssh damekjan@ollama.lan.mazlusek.com 'docker logs --tail 100 -f jervis-xtts-gpu'`.
