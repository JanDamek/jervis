"""Request/response schemas, priority enum, model-set definitions, capability catalog."""

from __future__ import annotations

import json
import os
import time
import uuid
import asyncio
from dataclasses import dataclass, field
from enum import IntEnum, Enum
from typing import Any

from pydantic import BaseModel


# ── Priority ────────────────────────────────────────────────────────────

class Priority(IntEnum):
    """Lower number = higher priority. Only 2 effective levels."""
    CRITICAL = 0   # User waiting (jervis_mcp, orchestrator FOREGROUND)
    NORMAL = 1     # System work (background, correction, KB ingest)


# ── Capability enum ─────────────────────────────────────────────────────

class Capability(str, Enum):
    """LLM capability requested by caller (orchestrator/chat)."""
    THINKING = "thinking"      # Reasoning, analysis, architecture
    CODING = "coding"          # Code generation/editing
    CHAT = "chat"              # General conversation, Q&A
    EMBEDDING = "embedding"    # Text embeddings
    VISUAL = "visual"          # Image understanding (VLM)


# ── Per-GPU model sets ──────────────────────────────────────────────────
# Which models to keep loaded on each GPU. Loaded from env var GPU_MODEL_SETS
# with fallback to defaults. p40-1 stays stable (never swaps models).

_DEFAULT_GPU_MODEL_SETS: dict[str, list[str]] = {
    "p40-1": ["qwen3-coder-tool:30b"],                        # 48k ctx, stable
    "p40-2": ["qwen3-coder-tool:30b", "qwen3-embedding:8b"],  # 32k ctx, + VLM on-demand
}

def _load_gpu_model_sets() -> dict[str, list[str]]:
    """Load GPU_MODEL_SETS from env var (JSON), fallback to defaults."""
    raw = os.environ.get("GPU_MODEL_SETS", "")
    if raw:
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                return parsed
        except (json.JSONDecodeError, TypeError):
            pass
    return _DEFAULT_GPU_MODEL_SETS

GPU_MODEL_SETS: dict[str, list[str]] = _load_gpu_model_sets()

# VLM assigned to p40-2 — temporarily replaces coder model when needed.
# p40-1 stays stable (never swaps models).
VLM_GPU = "p40-2"

# Local model capabilities
LOCAL_MODEL_CAPABILITIES: dict[str, list[str]] = {
    "qwen3-coder-tool:30b": ["thinking", "coding", "chat"],
    "qwen3-embedding:8b": ["embedding"],
    "qwen3-vl:latest": ["visual"],
}

# Local model context limits (fixed num_ctx per GPU Modelfile)
LOCAL_MODEL_CONTEXT: dict[str, int] = {
    "p40-1": 48_000,
    "p40-2": 32_000,
}


# ── Model sets (legacy — used by gpu_state.py for VRAM tracking) ───────

# keep_alive="-1" → Ollama keeps models in VRAM indefinitely.
# Router manages what's loaded/unloaded explicitly — no Ollama auto-eviction.
MODEL_SETS: dict[str, dict] = {
    "primary": {
        "models": ["qwen3-coder-tool:30b", "qwen3-embedding:8b"],
        "vram_gb": 25.0,
        "keep_alive": "-1",
    },
    "vlm": {
        "models": ["qwen3-vl:latest"],
        "vram_gb": 12.0,
        "keep_alive": "10m",  # VLM: auto-unload after 10min idle (loaded on-demand)
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
    status: str              # "healthy" | "unhealthy"
    gpu_backends: list[dict[str, Any]]
    orchestrator_reserved: bool
    queue_depth: int


class StatusResponse(BaseModel):
    gpu_backends: list[dict[str, Any]]
    orchestrator: dict[str, Any]
    metrics: dict[str, Any]
