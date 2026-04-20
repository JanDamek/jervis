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


# ── Helper ──────────────────────────────────────────────────────────────

def _get_priority_header(request: Request) -> int | None:
    val = request.headers.get("x-ollama-priority")
    if val is not None:
        try:
            return int(val)
        except ValueError:
            pass
    return None


# ── Ollama inference endpoints — TRANSITIONAL REST WRAP ───────────────
# The canonical surface is `RouterInferenceService` (gRPC on :5501). The
# FastAPI handlers below exist only while the remaining internal Jervis
# modules are being cut over to gRPC. They delegate to the same
# `router.dispatch_inference` path the gRPC servicer uses, then wrap the
# iterator/dict back into a Starlette response.
#
# Delete these handlers once every caller of `/api/chat`, `/api/generate`,
# `/api/embed`, `/api/embeddings` has been migrated — see Phase 1
# deferred in `docs/inter-service-contracts-bigbang.md`.

import json as _json
from starlette.responses import StreamingResponse, JSONResponse as _JSON
from .proxy import ProxyError as _ProxyError
from .request_queue import QueueCancelled as _QueueCancelled


async def _rest_inference(api_path: str, body: dict, http_request: Request):
    """Run inference via the gRPC-shared dispatch path and wrap into HTTP."""
    capability = (http_request.headers.get("X-Capability") or "").strip().lower() or None
    client_id = http_request.headers.get("X-Client-Id") or None
    try:
        result = await router.dispatch_inference(
            api_path, body,
            capability=capability,
            client_id=client_id,
            intent=http_request.headers.get("X-Intent", "") or "",
        )
    except _QueueCancelled:
        return _JSON(status_code=499, content={"error": "cancelled"})
    except _ProxyError as e:
        status = e.status_code or 502
        return _JSON(
            status_code=status,
            content={"error": e.reason, "message": e.message},
        )

    if isinstance(result, dict):
        return _JSON(content=result)

    async def _stream():
        try:
            async for chunk in result:
                yield (_json.dumps(chunk) + "\n").encode()
        except _ProxyError as e:
            yield (_json.dumps({
                "error": e.reason, "status_code": e.status_code,
                "message": e.message, "done": True,
            }) + "\n").encode()

    return StreamingResponse(_stream(), media_type="application/x-ndjson")


@app.post("/api/generate")
async def api_generate(request: Request):
    body = await request.json()
    return await _rest_inference("/api/generate", body, request)


@app.post("/api/chat")
async def api_chat(request: Request):
    body = await request.json()
    return await _rest_inference("/api/chat", body, request)


@app.post("/api/embeddings")
async def api_embeddings(request: Request):
    body = await request.json()
    return await _rest_inference("/api/embeddings", body, request)


@app.post("/api/embed")
async def api_embed(request: Request):
    body = await request.json()
    return await _rest_inference("/api/embed", body, request)


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

    qdepth = router._queue.queue_depth if router._queue else {}
    return {
        "status": status,
        "gpu_backends": gpu_backends,
        "orchestrator_reserved": router.is_reserved,
        "queue_depth": sum(qdepth.values()),
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

    from .rate_limiter import get_rate_limit_status
    from .openrouter_catalog import get_model_errors

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
        "openrouter": {
            "rate_limits": get_rate_limit_status(),
            "model_errors": get_model_errors(),
        },
    }


# ── /router/admin/* + /router/internal/* migrated to gRPC (jervis.router.RouterAdminService)
#    on port 5501. See app/grpc_server.py. No REST fallback — hard cut.


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
    qdepth = router._queue.queue_depth if router._queue else {}
    gpu_free = [b.name for b in router.gpu_pool.all_backends if b.healthy and b.active_request_count() == 0]
    gpu_busy = [b.name for b in router.gpu_pool.all_backends if b.healthy and b.active_request_count() > 0]
    return {
        "queue_depth_by_group": qdepth,
        "queue_depth_total": sum(qdepth.values()),
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
