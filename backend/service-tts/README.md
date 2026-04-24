# jervis-tts — XTTS v2 neural TTS on the VD GPU VM

Neural TTS using Coqui XTTS v2 (multilingual voice cloning, Czech + English).
Hosts pod-to-pod gRPC (`jervis.tts.TtsService`) on :5501, plus a dev-only
FastAPI on :8787.

## Where it runs

**Not in K8s.** TTS lives directly on the VD GPU VM
(`ollama.lan.mazlusek.com`, p40-2) as a systemd process, sharing the P40
with Ollama and Whisper. See `memory/feedback-audio-services-on-vd.md`
for the rationale (CUDA VRAM overhead on CPU-only K8s nodes is a dealbreaker).

Kotlin consumers (`TtsGrpcClient.kt`) dial
`ollama.lan.mazlusek.com:5501` directly over h2c.

## Deploy

Run from the repo root on your Mac (SSH-pushes code + deps to the VM):

```bash
./k8s/deploy_xtts_gpu.sh                    # uses SSH key auth (~/.ssh/id_starkys)
SSH_PASS=secret ./k8s/deploy_xtts_gpu.sh    # or sshpass
```

The script:

1. Stops any running XTTS process (pkill `app.xtts_server`).
2. Ensures `/opt/jervis/xtts/venv` exists.
3. Installs `torch` + `coqui-tts` + `grpcio` deps into that venv (CUDA 12.4 wheels from pytorch.org).
4. `scp`s `backend/service-tts/app/` → `/opt/jervis/xtts/app/`
   and `libs/jervis_contracts/` → `/opt/jervis/xtts/jervis_contracts/`, then `pip install -e` the contracts.
5. Pre-downloads the XTTS v2 model (~2 GB, first run only).
6. Launches `python -m app` under the `jervis-tts-gpu` systemd user unit.
7. Health-checks `:8787/health`.

To add or update a voice reference sample:

```bash
scp -i ~/.ssh/id_starkys voice.wav \
  damekjan@ollama.lan.mazlusek.com:/opt/jervis/data/tts/speakers/speaker.wav
```

## What's on disk

```
/opt/jervis/xtts/
├── venv/                     # Python 3.11 + CUDA 12.4 torch
├── app/                      # code from backend/service-tts/app
├── jervis_contracts/         # local editable install
└── ...
/opt/jervis/data/tts/
└── speakers/*.wav            # voice reference samples (NOT in git — too big)
```

Voice samples are **not** tracked in the repo (`.gitignore` blocks `*.wav` /
`*.aifc` / `*.flac` / `*.mp3`). They sit on the VD and any new VM bootstrap
copies them from there, not from git.

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

All stdout/stderr from the systemd unit is forwarded by journald to the
cluster's Elasticsearch via the VD's own fluent-bit (not the K8s DaemonSet).
Query in Kibana with `kubernetes.labels.app: jervis-tts-gpu` — see
`memory/feedback-use-kibana-for-logs.md`.
