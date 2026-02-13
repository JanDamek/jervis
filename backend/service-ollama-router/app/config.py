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

    # ── Backends ────────────────────────────────────────────────────────
    # JSON list: [{"url":"http://127.0.0.1:11434","vram_gb":24,"name":"p40"}]
    gpu_backends: str = '[{"url":"http://127.0.0.1:11434","vram_gb":24,"name":"p40"}]'
    cpu_backend_url: str = "http://127.0.0.1:11435"

    # ── Model sets ──────────────────────────────────────────────────────
    orchestrator_model: str = "qwen3-coder-tool:30b"

    # ── Timeouts ────────────────────────────────────────────────────────
    orchestrator_reservation_timeout_s: int = 1800  # 30 min max reservation
    orchestrator_idle_timeout_s: int = 300           # 5 min no requests → auto-release
    model_load_timeout_s: int = 120                  # 2 min to load a model
    background_load_delay_s: int = 5                 # delay before loading bg set after release
    proxy_connect_timeout_s: float = 10.0
    proxy_write_timeout_s: float = 30.0

    # ── Model keep_alive ────────────────────────────────────────────────
    default_keep_alive: str = "10m"  # Must match Ollama server's OLLAMA_KEEP_ALIVE

    # ── Preemption ──────────────────────────────────────────────────────
    preempt_embeddings: bool = False     # let short embedding requests finish
    preempt_grace_s: float = 2.0        # grace before killing streaming

    def parsed_gpu_backends(self) -> list[GpuBackendConfig]:
        raw = json.loads(self.gpu_backends)
        return [GpuBackendConfig(**entry) for entry in raw]

    model_config = {"env_prefix": "", "case_sensitive": False}


settings = Settings()
