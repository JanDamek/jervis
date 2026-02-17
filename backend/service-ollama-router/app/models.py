"""Request/response schemas, priority enum, model-set definitions."""

from __future__ import annotations

import time
import uuid
import asyncio
from dataclasses import dataclass, field
from enum import IntEnum
from typing import Any

from pydantic import BaseModel


# ── Priority ────────────────────────────────────────────────────────────

class Priority(IntEnum):
    """Lower number = higher priority. Only 2 effective levels."""
    CRITICAL = 0   # User waiting (jervis_mcp, orchestrator FOREGROUND)
    NORMAL = 1     # System work (background, correction, KB ingest)


# ── Model sets ──────────────────────────────────────────────────────────

MODEL_SETS: dict[str, dict] = {
    "orchestrator": {
        "models": ["qwen3-coder-tool:30b"],
        "vram_gb": 20.0,
        "keep_alive": "30m",
    },
    "background": {
        "models": ["qwen2.5:7b", "qwen2.5:14b", "qwen3-embedding:8b"],
        "vram_gb": 20.0,
        "keep_alive": "10m",
    },
    "vlm": {
        "models": ["qwen3-vl:latest"],
        "vram_gb": 12.0,
        "keep_alive": "5m",
    },
}

# model → set name lookup
MODEL_TO_SET: dict[str, str] = {}
for _set_name, _set_def in MODEL_SETS.items():
    for _model in _set_def["models"]:
        MODEL_TO_SET[_model] = _set_name

# model → default priority lookup (CRITICAL is set via header, not model)
MODEL_TO_PRIORITY: dict[str, Priority] = {
    "qwen3-coder-tool:30b": Priority.NORMAL,
    "qwen2.5:7b": Priority.NORMAL,
    "qwen2.5:14b": Priority.NORMAL,
    "qwen3-embedding:8b": Priority.NORMAL,
    "qwen3-vl:latest": Priority.NORMAL,
}

# Embedding models (fast single-pass, don't preempt)
EMBEDDING_MODELS = {"qwen3-embedding:8b"}

# Embedding API paths
EMBEDDING_PATHS = {"/api/embeddings", "/api/embed"}


# ── Tracked request ────────────────────────────────────────────────────

class RequestState:
    QUEUED = "queued"
    LOADING_MODEL = "loading_model"
    RUNNING_GPU = "running_gpu"
    RUNNING_CPU = "running_cpu"
    PREEMPTED = "preempted"
    COMPLETED = "completed"
    FAILED = "failed"


@dataclass
class TrackedRequest:
    request_id: str
    model: str
    priority: Priority
    api_path: str
    body: dict
    created_at: float = field(default_factory=time.monotonic)
    state: str = RequestState.QUEUED
    target_gpu: str | None = None      # gpu backend name
    cancel_event: asyncio.Event = field(default_factory=asyncio.Event)

    def __lt__(self, other: TrackedRequest) -> bool:
        """Priority queue ordering: lower priority number first, then FIFO."""
        if self.priority != other.priority:
            return self.priority < other.priority
        return self.created_at < other.created_at


# ── API schemas ─────────────────────────────────────────────────────────

class HealthResponse(BaseModel):
    status: str              # "healthy" | "degraded" | "unhealthy"
    gpu_backends: list[dict[str, Any]]
    cpu_backend: dict[str, Any]
    orchestrator_reserved: bool
    queue_depth: int


class StatusResponse(BaseModel):
    gpu_backends: list[dict[str, Any]]
    cpu_backend: dict[str, Any]
    orchestrator: dict[str, Any]
    metrics: dict[str, Any]
