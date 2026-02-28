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
    model_load_timeout_s: int = 120                  # 2 min to load a model
    background_load_delay_s: int = 5                 # delay before loading bg set after release
    proxy_connect_timeout_s: float = 10.0
    proxy_write_timeout_s: float = 30.0

    # ── Model keep_alive ────────────────────────────────────────────────
    # "-1" = never auto-unload. Router explicitly manages model lifecycle.
    default_keep_alive: str = "-1"

    # ── Request limits ──────────────────────────────────────────────────
    max_request_timeout_s: int = 300     # 5 min per request (cancel + REQUEST_OUT if exceeded)
    max_concurrent_per_backend: int = 1  # Serial is faster than parallel when VRAM spills to RAM
    normal_queue_max: int = 10           # NORMAL queue limit (back-pressure when full)

    # ── Preemption ──────────────────────────────────────────────────────
    preempt_embeddings: bool = False     # let short embedding requests finish
    preempt_grace_s: float = 2.0        # grace before killing streaming

    # ── GPU idle notification ────────────────────────────────────────────
    # After this many seconds of no GPU requests, router notifies Kotlin server
    # to trigger proactive analytical tasks (vulnerability scan, code quality, etc.)
    gpu_idle_notify_after_s: int = 300   # 5 minutes
    kotlin_server_url: str = "http://jervis-server:8080"

    def parsed_gpu_backends(self) -> list[GpuBackendConfig]:
        raw = json.loads(self.gpu_backends)
        return [GpuBackendConfig(**entry) for entry in raw]

    model_config = {"env_prefix": "", "case_sensitive": False}


settings = Settings()
