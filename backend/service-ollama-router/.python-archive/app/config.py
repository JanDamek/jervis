"""Router configuration via environment variables."""

from __future__ import annotations

import json

from pydantic import Field
from pydantic_settings import BaseSettings


class GpuBackendConfig:
    """Parsed GPU backend definition."""

    def __init__(self, url: str, vram_gb: float, name: str) -> None:
        self.url = url.rstrip("/")
        self.vram_gb = vram_gb
        self.name = name

    def __repr__(self) -> str:
        return f"GpuBackendConfig(name={self.name!r}, url={self.url!r}, vram_gb={self.vram_gb})"


class Settings(BaseSettings):
    # ── Server ──────────────────────────────────────────────────────────
    router_port: int = 11430
    router_host: str = "0.0.0.0"

    # ── Backends (override via env vars / K8s ConfigMap) ────────────────
    # GPU_BACKENDS: JSON array of GPU Ollama instances
    # Example: [{"url":"http://gpu1:11434","vram_gb":24,"name":"p40-1"},{"url":"http://gpu2:11434","vram_gb":24,"name":"p40-2"}]
    gpu_backends: str = "[]"
    cpu_backend_url: str = ""

    # ── Model sets ──────────────────────────────────────────────────────
    orchestrator_model: str = "qwen3-coder-tool:30b"

    # ── Timeouts ────────────────────────────────────────────────────────
    orchestrator_reservation_timeout_s: int = 600   # 10 min max reservation (safety net)
    orchestrator_idle_timeout_s: int = 60            # 1 min no critical requests → auto-release
    model_load_timeout_s: int = 300                  # 5 min to load a model (p40-2 with 8GB RAM needs >200s)
    background_load_delay_s: int = 5                 # delay before loading bg set after release
    proxy_connect_timeout_s: float = 10.0
    proxy_write_timeout_s: float = 30.0

    # Stale active-request reaper — failsafe for stream cleanup race
    # (HTTP client disconnects mid-stream → asyncio task killed before
    # `finally` cleanup runs → zombie left in gpu.active_requests, blocks
    # all subsequent slot acquisition because active_request_count >=
    # max_concurrent_llm). Reaper sweeps on this interval and evicts any
    # request older than the threshold so dispatcher resumes.
    # Reaper threshold — must accommodate the longest legitimate generation
    # (VLM with large context can run 15-30 min). Connection loss is detected
    # by httpx (RemoteProtocolError / ConnectError) — we do NOT impose hard
    # timeouts on healthy streams. Per docs/guidelines: NEVER hard timeouts,
    # stream + heartbeat. Reaper only catches structural zombies (cleanup
    # races) where the stream is dead but the slot wasn't freed.
    stale_request_max_age_s: int = 1800              # 30 min — well past worst-case VLM
    stale_request_check_interval_s: int = 60         # sweep every 60s

    # ── Model keep_alive ────────────────────────────────────────────────
    # "-1" = never auto-unload. Router explicitly manages model lifecycle.
    default_keep_alive: str = "-1"

    # ── Request limits ──────────────────────────────────────────────────
    max_request_timeout_s: int = 0        # No timeout — request waits until done
    max_concurrent_llm: int = 1           # LLM/VLM: serial (1 per GPU)
    max_concurrent_embeddings: int = 4     # Embedding: 4 parallel per GPU (ollama NUM_PARALLEL=10)

    # ── Preemption ──────────────────────────────────────────────────────
    preempt_embeddings: bool = False     # let short embedding requests finish
    preempt_grace_s: float = 2.0        # grace before killing streaming

    # ── GPU warmup ping ─────────────────────────────────────────────────
    # Periodically ping GPU backends to keep models in VRAM (prevents cold starts).
    warmup_enabled: bool = True
    warmup_interval_s: int = 240         # 4 minutes (safely under Ollama 5min default)

    # ── GPU idle notification ────────────────────────────────────────────
    # After this many seconds of no GPU requests, router notifies Kotlin server
    # to trigger proactive analytical tasks (vulnerability scan, code quality, etc.)
    gpu_idle_notify_after_s: int = 120   # 2 minutes (faster proactive task triggering)
    kotlin_server_url: str = "http://jervis-server:5500"

    # ── MongoDB (client tier resolution) ─────────────────────────────────
    mongodb_uri: str = "mongodb://root:qusre5-mYfpox-dikpef@nas.lan.mazlusek.com:27017/jervis?authSource=admin"
    client_tier_cache_ttl_s: int = 300  # 5 min cache for client tier

    # ── Whisper GPU coordination (p40-2) ──────────────────────────────────
    # Whisper REST service runs on same GPU (p40-2). Before loading VLM,
    # router asks whisper to release GPU VRAM.
    whisper_gpu_url: str = "http://ollama.lan.mazlusek.com:8786"
    whisper_gpu_acquire_timeout_s: int = 3600   # Max wait for Kotlin's acquire request (1h)
    whisper_gpu_max_hold_s: int = 7200          # Auto-release stale lock (2h safety net)

    def parsed_gpu_backends(self) -> list[GpuBackendConfig]:
        raw = json.loads(self.gpu_backends)
        return [GpuBackendConfig(**entry) for entry in raw]

    model_config = {"env_prefix": "", "case_sensitive": False}


settings = Settings()
