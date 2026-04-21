"""FastAPI application – Ollama-compatible transparent proxy with priority routing."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST
from starlette.responses import Response

from .config import settings
from .gpu_state import GpuPool
from .models import (
    HealthResponse,
    Priority,
    StatusResponse,
)
from .router_core import OllamaRouter
from . import metrics as m
from .logging_utils import LocalTimeFormatter

# Configure logging with local timezone
handler = logging.StreamHandler()
handler.setFormatter(LocalTimeFormatter("%(asctime)s %(name)s %(levelname)s %(message)s"))
logging.root.addHandler(handler)
logging.root.setLevel(logging.INFO)

logger = logging.getLogger("ollama-router")


class _HealthCheckAccessFilter(logging.Filter):
    """Drop GET /router/health from uvicorn access log."""

    def filter(self, record: logging.LogRecord) -> bool:
        msg = record.getMessage()
        if "GET /router/health " in msg:
            return False
        return True


# ── Lifespan ────────────────────────────────────────────────────────────

router: OllamaRouter | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global router
    logging.getLogger("uvicorn.access").addFilter(_HealthCheckAccessFilter())
    backends = settings.parsed_gpu_backends()
    if not backends:
        raise RuntimeError(
            "GPU_BACKENDS env var is empty or not set. "
            "Configure it as JSON array, e.g.: "
            '[{"url":"http://gpu1:11434","vram_gb":24,"name":"p40-1"}]'
        )
    # CPU backend removed — all requests go to GPU only.
    # If GPU is busy and OpenRouter is enabled, requests route to cloud.
    gpu_pool = GpuPool(backends)
    router = OllamaRouter(gpu_pool)
    await router.startup()
    # Load persisted stats from MongoDB (via Kotlin server)
    from .openrouter_catalog import load_persisted_stats, persist_stats
    await load_persisted_stats()
    probe_task = asyncio.create_task(_model_probe_loop())
    persist_task = asyncio.create_task(_stats_persist_loop())

    # gRPC admin + inference surface on :5501. Every internal Jervis
    # module dials this; no REST inference endpoint is exposed. FastAPI
    # :11430 only keeps the Ollama-compatible *administrative* passthrough
    # (show/pull/tags/ps/delete) used by operator-side debugging.
    from .grpc_server import start_grpc_server
    grpc_server = await start_grpc_server(router, port=5501)

    yield
    # Persist stats before shutdown
    await persist_stats()
    probe_task.cancel()
    persist_task.cancel()
    await grpc_server.stop(grace=5)
    await router.shutdown()


app = FastAPI(title="Ollama Router", lifespan=lifespan)


# ── Model probe loop ──────────────────────────────────────────────────

async def _model_probe_loop():
    """Periodically test disabled models to check if they're back online.

    Runs every 60s. For models with probe_ready=True (cooldown expired after 429),
    sends a tiny test call. On success → re-enable. On failure → escalate cooldown.
    After 3 failed probes → permanently disable (manual reset only).
    """
    from .openrouter_catalog import (
        get_models_needing_probe, handle_probe_success, handle_probe_failure, get_api_key,
    )

    await asyncio.sleep(30)  # Initial delay
    while True:
        try:
            models = get_models_needing_probe()
            if models:
                api_key = await get_api_key()
                if api_key:
                    for model_id in models:
                        ok = await _probe_model(model_id, api_key)
                        if ok:
                            handle_probe_success(model_id)
                        else:
                            handle_probe_failure(model_id)
                        await asyncio.sleep(5)  # Space out probes
        except asyncio.CancelledError:
            return
        except Exception as e:
            logger.debug("Probe loop error: %s", e)
        await asyncio.sleep(60)


async def _probe_model(model_id: str, api_key: str) -> bool:
    """Send a tiny test call to check if model responds (not 429)."""
    body = {
        "model": model_id,
        "messages": [{"role": "user", "content": "Reply: OK"}],
        "max_tokens": 5,
        "temperature": 0.0,
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                "https://openrouter.ai/api/v1/chat/completions",
                json=body, headers=headers,
            )
            if resp.status_code == 200:
                logger.info("PROBE %s: OK (%d)", model_id, resp.status_code)
                return True
            logger.info("PROBE %s: FAILED (%d)", model_id, resp.status_code)
            return False
    except Exception as e:
        logger.info("PROBE %s: ERROR %s", model_id, e)
        return False


async def _stats_persist_loop():
    """Periodically persist model stats to MongoDB (every 5 min)."""
    from .openrouter_catalog import persist_stats
    await asyncio.sleep(300)  # First persist after 5 min
    while True:
        try:
            await persist_stats()
        except asyncio.CancelledError:
            return
        except Exception as e:
            logger.debug("Stats persist error: %s", e)
        await asyncio.sleep(300)


# ── All Ollama-compat REST endpoints REMOVED ──────────────────────────
# 2026-04-21 — hard cut. Every Ollama admin / inference path the router
# used to expose (`/api/generate`, `/api/chat`, `/api/embed`,
# `/api/embeddings`, `/api/show`, `/api/pull`, `/api/tags`, `/api/ps`,
# `/api/delete`, `GET /`, `HEAD /`) is gone. Internal modules use gRPC
# exclusively: `RouterInferenceService` for inference and
# `RouterAdminService` for admin / telemetry, both on :5501.
#
# K8s probes + Prometheus scraping are the only HTTP endpoints that
# remain on this server — they are infra contracts (kubelet and
# Prometheus do not speak gRPC natively).


# ── K8s probes + Prometheus (HTTP unavoidable — kubelet/Prometheus contract)

@app.get("/router/health")
async def router_health():
    """Health check for K8s probes."""
    gpu_backends = []
    for backend in router.gpu_pool.all_backends:
        gpu_backends.append({
            "name": backend.name,
            "healthy": backend.healthy,
            "url": backend.url,
            "loaded_models": list(backend.loaded_models.keys()),
            "vram_used_gb": round(backend.used_vram_gb, 1),
            "vram_total_gb": backend.vram_gb,
            "active_requests": backend.active_request_count(),
            "reserved_by": backend.reserved_by,
        })

    any_gpu_healthy = any(b.healthy for b in router.gpu_pool.all_backends)
    status = "healthy" if any_gpu_healthy else "unhealthy"

    qdepth = router._queue.queue_depth if router._queue else {}
    return {
        "status": status,
        "gpu_backends": gpu_backends,
        "orchestrator_reserved": router.is_reserved,
        "queue_depth": sum(qdepth.values()),
    }


# /router/status, /queue-status — retired. Same data is available via
# RouterAdminService gRPC (ListModelStats, ListModelErrors,
# GetRateLimits). Debug queries: grpcurl against :5501.

# /router/whisper-notify, /router/whisper-done — retired. Whisper on VD
# must migrate to RouterAdminService.WhisperNotify / WhisperDone
# (proto addition tracked in Phase 1 deferred, blocked on VD gRPC
# onboarding). Meanwhile whisper runs without the coordination; p40-2
# VRAM contention falls back to load-on-demand which is slower but
# never blocks.


@app.get("/router/metrics")
async def router_metrics():
    """Prometheus metrics endpoint — infra contract, HTTP unavoidable."""
    return Response(
        content=generate_latest(),
        media_type=CONTENT_TYPE_LATEST,
    )
