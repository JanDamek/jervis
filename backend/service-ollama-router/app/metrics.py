"""Prometheus metrics, /router/status, /router/health endpoints."""

from __future__ import annotations

import time

from prometheus_client import Counter, Gauge, Histogram

# ── Request routing ─────────────────────────────────────────────────────

requests_total = Counter(
    "ollama_router_requests_total",
    "Total requests routed",
    ["target", "model", "priority"],
)

requests_preempted = Counter(
    "ollama_router_preempted_total",
    "Requests preempted by higher priority",
    ["model"],
)

requests_duration = Histogram(
    "ollama_router_request_duration_seconds",
    "Request duration",
    ["target", "model"],
    buckets=[0.5, 1, 2, 5, 10, 30, 60, 120, 300, 600, 1800],
)

# ── GPU state ───────────────────────────────────────────────────────────

gpu_loaded_models = Gauge(
    "ollama_router_gpu_loaded_models",
    "Number of models loaded in GPU VRAM",
    ["gpu"],
)

gpu_vram_used = Gauge(
    "ollama_router_gpu_vram_used_gb",
    "GPU VRAM used (GB)",
    ["gpu"],
)

gpu_active_requests = Gauge(
    "ollama_router_gpu_active_requests",
    "Active requests on GPU",
    ["gpu"],
)

orchestrator_reserved = Gauge(
    "ollama_router_orchestrator_reserved",
    "1 if orchestrator has GPU reservation",
)

# ── Model swap ──────────────────────────────────────────────────────────

model_swaps_total = Counter(
    "ollama_router_model_swaps_total",
    "Total GPU model swaps",
)

model_swap_duration = Histogram(
    "ollama_router_model_swap_seconds",
    "Time to swap models on GPU",
    buckets=[5, 10, 20, 30, 60, 120],
)

# ── Queue ───────────────────────────────────────────────────────────────

cpu_fallback_total = Counter(
    "ollama_router_cpu_fallback_total",
    "Total requests that fell back to CPU",
    ["model"],
)
