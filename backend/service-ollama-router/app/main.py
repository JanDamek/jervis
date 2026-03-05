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
from .proxy import proxy_passthrough_get, proxy_passthrough_head
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
    yield
    await router.shutdown()


app = FastAPI(title="Ollama Router", lifespan=lifespan)


# ── Helper ──────────────────────────────────────────────────────────────

def _get_priority_header(request: Request) -> int | None:
    val = request.headers.get("x-ollama-priority")
    if val is not None:
        try:
            return int(val)
        except ValueError:
            pass
    return None


# ── Standard Ollama API – transparent proxy ─────────────────────────────

@app.post("/api/generate")
async def api_generate(request: Request):
    body = await request.json()
    priority = _get_priority_header(request)
    start = time.monotonic()
    resp = await router.route_request("/api/generate", body, priority, http_request=request)
    duration = time.monotonic() - start
    model = body.get("model", "unknown")
    target = "gpu" if "running_gpu" in str(getattr(resp, '_body', '')) else "cpu"
    m.requests_total.labels(target=target, model=model, priority=str(priority or "auto")).inc()
    m.requests_duration.labels(target=target, model=model).observe(duration)
    return resp


@app.post("/api/chat")
async def api_chat(request: Request):
    body = await request.json()
    priority = _get_priority_header(request)
    start = time.monotonic()
    resp = await router.route_request("/api/chat", body, priority, http_request=request)
    duration = time.monotonic() - start
    model = body.get("model", "unknown")
    m.requests_total.labels(target="routed", model=model, priority=str(priority or "auto")).inc()
    m.requests_duration.labels(target="routed", model=model).observe(duration)
    return resp


@app.post("/api/embeddings")
async def api_embeddings(request: Request):
    body = await request.json()
    priority = _get_priority_header(request)
    resp = await router.route_request("/api/embeddings", body, priority, http_request=request)
    model = body.get("model", "unknown")
    m.requests_total.labels(target="routed", model=model, priority=str(priority or "auto")).inc()
    return resp


@app.post("/api/embed")
async def api_embed(request: Request):
    body = await request.json()
    priority = _get_priority_header(request)
    resp = await router.route_request("/api/embed", body, priority, http_request=request)
    model = body.get("model", "unknown")
    m.requests_total.labels(target="routed", model=model, priority=str(priority or "auto")).inc()
    return resp


@app.post("/api/show")
@app.post("/api/generate/api/show")
@app.post("/api/chat/api/show")
async def api_show(request: Request):
    """Model info – try GPU backends first, then CPU.

    Extra paths handle litellm appending /api/show relative to its base endpoint.
    """
    body = await request.json()
    model = body.get("name", body.get("model", ""))

    # Try GPU backends first
    for backend in router.gpu_pool.healthy_backends:
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.post(f"{backend.url}/api/show", json=body)
                if resp.status_code == 200:
                    return Response(
                        content=resp.content,
                        status_code=resp.status_code,
                        media_type="application/json",
                    )
        except Exception:
            pass

    return JSONResponse(status_code=503, content={"error": "model_not_found"})


@app.post("/api/pull")
async def api_pull(request: Request):
    """Pull model – proxy to first healthy GPU backend."""
    body = await request.json()

    # Pull on first healthy GPU
    for backend in router.gpu_pool.healthy_backends:
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(connect=10, read=None, write=30, pool=30)) as client:
                resp = await client.post(f"{backend.url}/api/pull", json=body)
                return Response(
                    content=resp.content,
                    status_code=resp.status_code,
                    media_type="application/json",
                )
        except Exception:
            pass

    return JSONResponse(status_code=503, content={"error": "no_gpu_available"})


@app.get("/api/tags")
async def api_tags():
    """List models – aggregated from all backends."""
    all_models = {}

    # Collect from GPU backends
    for backend in router.gpu_pool.all_backends:
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(f"{backend.url}/api/tags")
                if resp.status_code == 200:
                    data = resp.json()
                    for m_info in data.get("models", []):
                        name = m_info.get("name", "")
                        if name not in all_models:
                            all_models[name] = m_info
        except Exception:
            pass

    return {"models": list(all_models.values())}


@app.get("/api/ps")
async def api_ps():
    """Running models – aggregated from all backends."""
    all_running = []

    for backend in router.gpu_pool.all_backends:
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(f"{backend.url}/api/ps")
                if resp.status_code == 200:
                    data = resp.json()
                    for m_info in data.get("models", []):
                        m_info["_backend"] = backend.name
                        all_running.append(m_info)
        except Exception:
            pass

    return {"models": all_running}


@app.delete("/api/delete")
async def api_delete(request: Request):
    """Delete model – proxy to all backends."""
    body = await request.json()
    results = {}

    for backend in router.gpu_pool.all_backends:
        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                resp = await client.request("DELETE", f"{backend.url}/api/delete", json=body)
                results[backend.name] = resp.status_code
        except Exception as e:
            results[backend.name] = str(e)

    return {"results": results}


@app.head("/")
async def root_head():
    """Health check – return 200 if any GPU backend is up."""
    if router.gpu_pool.healthy_backends:
        return Response(status_code=200)
    return Response(status_code=503)


@app.get("/")
async def root_get():
    """Ollama version info – proxy to first healthy GPU backend."""
    for backend in router.gpu_pool.healthy_backends:
        return await proxy_passthrough_get(backend.url, "/")

    return JSONResponse(status_code=503, content={"error": "no_backend_available"})


# ── Router-specific endpoints ───────────────────────────────────────────

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

    qdepth = router._queue.queue_depth if router._queue else {"critical": 0, "normal": 0}
    return {
        "status": status,
        "gpu_backends": gpu_backends,
        "orchestrator_reserved": router.is_reserved,
        "queue_depth": qdepth["critical"] + qdepth["normal"],
    }


@app.get("/router/status")
async def router_status():
    """Detailed router status for debugging."""
    gpu_backends = []
    for backend in router.gpu_pool.all_backends:
        # Update prometheus gauges
        m.gpu_loaded_models.labels(gpu=backend.name).set(len(backend.loaded_models))
        m.gpu_vram_used.labels(gpu=backend.name).set(round(backend.used_vram_gb, 1))
        m.gpu_active_requests.labels(gpu=backend.name).set(backend.active_request_count())

        gpu_backends.append({
            "name": backend.name,
            "healthy": backend.healthy,
            "url": backend.url,
            "loaded_models": backend.loaded_models,
            "current_set": backend.current_set,
            "vram_used_gb": round(backend.used_vram_gb, 1),
            "vram_total_gb": backend.vram_gb,
            "active_requests": {
                req_id: {
                    "model": req.model,
                    "priority": req.priority.name,
                    "state": req.state,
                    "age_s": round(time.monotonic() - req.created_at, 1),
                }
                for req_id, req in backend.active_requests.items()
            },
            "reserved_by": backend.reserved_by,
        })

    m.orchestrator_reserved.set(1 if router.is_reserved else 0)

    # Whisper state (flag-based, no HTTP call)
    whisper_busy = router.check_whisper_busy()

    return {
        "gpu_backends": gpu_backends,
        "orchestrator": {
            "reserved": router.is_reserved,
            "reservations": router._reservations,
            "reservation_times": {k: v for k, v in router._reservation_times.items()},
            "last_activity": {k: v for k, v in router._last_critical_activity.items()},
        },
        "metrics": {
            "queue_depth": router._queue.queue_depth if router._queue else {},
            "note": "See /router/metrics for Prometheus format",
        },
        "whisper": {"busy": whisper_busy},
    }


@app.post("/route-decision")
async def route_decision(request: Request):
    """Capability-based routing decision endpoint.

    Input: {"capability": "chat", "max_tier": "FREE", "estimated_tokens": 5000, "processing_mode": "FOREGROUND"}
    Output: {"target": "local"|"openrouter", "model": "...", "api_base": "..."}

    processing_mode: "FOREGROUND" (chat, always cloud) or "BACKGROUND" (local GPU, cloud >48k)
    """
    body = await request.json()
    decision = await router.decide_route(
        capability=body.get("capability", "chat"),
        max_tier=body.get("max_tier", "NONE"),
        estimated_tokens=body.get("estimated_tokens", 0),
        processing_mode=body.get("processing_mode", "FOREGROUND"),
    )
    return JSONResponse(content=decision)


@app.get("/route-decision/max-context")
async def route_max_context(request: Request):
    """Get max available context tokens for a given tier."""
    max_tier = request.query_params.get("max_tier", "NONE")
    from .openrouter_catalog import get_max_context_tokens
    max_ctx = await get_max_context_tokens(max_tier)
    return JSONResponse(content={"max_context_tokens": max_ctx})


# ── Whisper GPU coordination (p40-2) ──────────────────────────────────

@app.post("/router/whisper-notify")
async def whisper_notify():
    """Whisper wants GPU. Blocks until VLM finishes (if running).

    Called by whisper REST server directly before loading model.
    Returns immediately if GPU is free, or waits for VLM to finish.
    """
    timeout = settings.whisper_gpu_acquire_timeout_s
    try:
        granted = await asyncio.wait_for(
            router.notify_whisper_wants_gpu(),
            timeout=timeout,
        )
        if granted:
            return JSONResponse(content={"status": "granted"})
        return JSONResponse(status_code=503, content={"status": "denied"})
    except asyncio.TimeoutError:
        return JSONResponse(
            status_code=408,
            content={"status": "timeout", "waited_s": timeout},
        )


@app.post("/router/whisper-done")
async def whisper_done():
    """Whisper finished transcription. Clears active flag.

    Called by whisper REST server after transcription completes.
    Signals waiting VLM requests that GPU is available.
    """
    router.notify_whisper_done()
    return JSONResponse(content={"status": "ok"})


@app.get("/queue-status")
async def queue_status():
    """Minimal queue status for orchestrator routing decisions."""
    qdepth = router._queue.queue_depth if router._queue else {"critical": 0, "normal": 0}
    gpu_free = [b.name for b in router.gpu_pool.all_backends if b.healthy and b.active_request_count() == 0]
    gpu_busy = [b.name for b in router.gpu_pool.all_backends if b.healthy and b.active_request_count() > 0]
    return {
        "critical_queue": qdepth.get("critical", 0),
        "normal_queue": qdepth.get("normal", 0),
        "gpu_free": gpu_free,
        "gpu_busy": gpu_busy,
    }


@app.get("/router/metrics")
async def router_metrics():
    """Prometheus metrics endpoint."""
    return Response(
        content=generate_latest(),
        media_type=CONTENT_TYPE_LATEST,
    )
