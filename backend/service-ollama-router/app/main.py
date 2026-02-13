"""FastAPI application – Ollama-compatible transparent proxy with priority routing."""

from __future__ import annotations

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
    AnnounceRequest,
    AnnounceResponse,
    HealthResponse,
    Priority,
    ReleaseRequest,
    ReleaseResponse,
    StatusResponse,
)
from .proxy import proxy_passthrough_get, proxy_passthrough_head
from .router_core import OllamaRouter
from . import metrics as m

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(name)s %(levelname)s %(message)s",
)
logger = logging.getLogger("ollama-router")

# ── Lifespan ────────────────────────────────────────────────────────────

router: OllamaRouter | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global router
    gpu_pool = GpuPool(settings.parsed_gpu_backends())
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
    resp = await router.route_request("/api/generate", body, priority)
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
    resp = await router.route_request("/api/chat", body, priority)
    duration = time.monotonic() - start
    model = body.get("model", "unknown")
    m.requests_total.labels(target="routed", model=model, priority=str(priority or "auto")).inc()
    m.requests_duration.labels(target="routed", model=model).observe(duration)
    return resp


@app.post("/api/embeddings")
async def api_embeddings(request: Request):
    body = await request.json()
    priority = _get_priority_header(request)
    resp = await router.route_request("/api/embeddings", body, priority)
    model = body.get("model", "unknown")
    m.requests_total.labels(target="routed", model=model, priority=str(priority or "auto")).inc()
    return resp


@app.post("/api/embed")
async def api_embed(request: Request):
    body = await request.json()
    priority = _get_priority_header(request)
    resp = await router.route_request("/api/embed", body, priority)
    model = body.get("model", "unknown")
    m.requests_total.labels(target="routed", model=model, priority=str(priority or "auto")).inc()
    return resp


@app.post("/api/show")
async def api_show(request: Request):
    """Model info – try GPU backends first, then CPU."""
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

    # Fall back to CPU
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(f"{router.cpu_url}/api/show", json=body)
            return Response(
                content=resp.content,
                status_code=resp.status_code,
                media_type="application/json",
            )
    except Exception as e:
        return JSONResponse(status_code=503, content={"error": str(e)})


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

    # Also pull on CPU for fallback availability
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(connect=10, read=None, write=30, pool=30)) as client:
            resp = await client.post(f"{router.cpu_url}/api/pull", json=body)
            return Response(
                content=resp.content,
                status_code=resp.status_code,
                media_type="application/json",
            )
    except Exception as e:
        return JSONResponse(status_code=503, content={"error": str(e)})


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

    # Collect from CPU
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(f"{router.cpu_url}/api/tags")
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

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(f"{router.cpu_url}/api/ps")
            if resp.status_code == 200:
                data = resp.json()
                for m_info in data.get("models", []):
                    m_info["_backend"] = "cpu"
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

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.request("DELETE", f"{router.cpu_url}/api/delete", json=body)
            results["cpu"] = resp.status_code
    except Exception as e:
        results["cpu"] = str(e)

    return {"results": results}


@app.head("/")
async def root_head():
    """Health check – return 200 if any backend is up."""
    if router.gpu_pool.healthy_backends or router.cpu_healthy:
        return Response(status_code=200)
    return Response(status_code=503)


@app.get("/")
async def root_get():
    """Ollama version info – proxy to first available backend."""
    for backend in router.gpu_pool.healthy_backends:
        return await proxy_passthrough_get(backend.url, "/")

    if router.cpu_healthy:
        return await proxy_passthrough_get(router.cpu_url, "/")

    return JSONResponse(status_code=503, content={"error": "no_backend_available"})


# ── Router-specific endpoints ───────────────────────────────────────────

@app.post("/router/announce")
async def router_announce(req: AnnounceRequest) -> AnnounceResponse:
    """Orchestrator pre-announces GPU need."""
    return await router.announce(req)


@app.post("/router/release")
async def router_release(req: ReleaseRequest) -> ReleaseResponse:
    """Orchestrator releases GPU."""
    return await router.release(req)


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
    if any_gpu_healthy and router.cpu_healthy:
        status = "healthy"
    elif any_gpu_healthy or router.cpu_healthy:
        status = "degraded"
    else:
        status = "unhealthy"

    return HealthResponse(
        status=status,
        gpu_backends=gpu_backends,
        cpu_backend={"healthy": router.cpu_healthy, "url": router.cpu_url},
        orchestrator_reserved=router.is_reserved,
        queue_depth=0,
    )


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

    return StatusResponse(
        gpu_backends=gpu_backends,
        cpu_backend={
            "healthy": router.cpu_healthy,
            "url": router.cpu_url,
        },
        orchestrator={
            "reserved": router.is_reserved,
            "session_id": router._reservation_session,
            "gpu": router._reservation_gpu,
            "reserved_at": router._reservation_at,
            "last_activity": router._last_critical_activity,
        },
        metrics={
            "note": "See /router/metrics for Prometheus format",
        },
    )


@app.get("/router/metrics")
async def router_metrics():
    """Prometheus metrics endpoint."""
    return Response(
        content=generate_latest(),
        media_type=CONTENT_TYPE_LATEST,
    )
