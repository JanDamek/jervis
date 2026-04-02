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
    """Lower number = higher priority."""
    CASCADE = -1   # Server-only instant cascade (merge AI, voice quick). Preempts queue, not running GPU.
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
    EXTRACTION = "extraction"  # Lightweight LLM for KB extraction, qualification (GPU-2)


# ── Per-GPU model sets ──────────────────────────────────────────────────
# Which models to keep loaded on each GPU. Loaded from env var GPU_MODEL_SETS
# with fallback to defaults. p40-1 stays stable (never swaps models).

_DEFAULT_GPU_MODEL_SETS: dict[str, list[str]] = {
    "p40-1": ["qwen3-coder-tool:30b"],                                                   # 48k ctx, heavy LLM
    "p40-2": ["bge-m3", "qwen3:14b", "qwen3-vl-tool:latest"],   # embedding + extraction 14b (permanent) + VLM (on-demand swap)
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

# VLM assigned to p40-2 — on-demand swap (swaps with 14b when needed).
# p40-1 stays stable (never swaps models).
VLM_GPU = "p40-2"

# Local model capabilities — used by /route-decision to find model for capability.
# _find_local_model_for_capability() returns FIRST match, so order matters.
# Both LLM models handle all text capabilities — 30b is preferred for complex tasks,
# 14b is faster for simple ones. Router picks based on GPU availability.
LOCAL_MODEL_CAPABILITIES: dict[str, list[str]] = {
    "qwen3-coder-tool:30b": ["thinking", "coding", "chat", "extraction"],
    "qwen3:14b": ["chat", "extraction", "thinking", "coding"],
    "bge-m3": ["embedding"],
    "qwen3-vl-tool:latest": ["visual"],
}

# Local model context limits (fixed num_ctx per GPU Modelfile)
LOCAL_MODEL_CONTEXT: dict[str, int] = {
    "p40-1": 48_000,  # 30b model, full VRAM
    "p40-2": 32_000,  # 14b + embedding + VL on-demand
}

# Model equivalence — when a requested model is busy, the router can redirect
# to an equivalent model on a different GPU. Both models must handle the same tasks.
# Format: requested_model → list of equivalent models (tried in order)
MODEL_EQUIVALENTS: dict[str, list[str]] = {
    "qwen3:14b": ["qwen3-coder-tool:30b"],         # 30b handles everything 14b can
    "qwen3-coder-tool:30b": ["qwen3:14b"],          # 14b can handle simpler 30b tasks
}


# ── Model sets (legacy — used by gpu_state.py for VRAM tracking) ───────

# keep_alive="-1" → Ollama keeps models in VRAM indefinitely.
# Router manages what's loaded/unloaded explicitly — no Ollama auto-eviction.
MODEL_SETS: dict[str, dict] = {
    "llm": {
        "models": ["qwen3-coder-tool:30b"],
        "vram_gb": 18.5,
        "keep_alive": "-1",   # p40-1: always loaded
    },
    "extraction": {
        "models": ["qwen3:14b"],
        "vram_gb": 11.0,
        "keep_alive": "-1",   # p40-2: permanent (KB graph extraction, summaries)
    },
    "embedding": {
        "models": ["bge-m3"],
        "vram_gb": 1.0,
        "keep_alive": "-1",   # p40-2: always loaded (568M model, ~1GB VRAM)
    },
    "vlm": {
        "models": ["qwen3-vl-tool:latest"],
        "vram_gb": 8.8,
        "keep_alive": "10m",  # p40-2: on-demand swap (swaps 14b out, whisper coordination)
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
    "qwen3:14b": Priority.NORMAL,
    "bge-m3": Priority.NORMAL,
    "qwen3-vl-tool:latest": Priority.NORMAL,
}

# Embedding models (fast single-pass, don't preempt)
EMBEDDING_MODELS = {"bge-m3"}

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
    original_model: str | None = None  # original model before redirect
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
