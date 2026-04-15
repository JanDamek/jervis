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
    yield
    # Persist stats before shutdown
    await persist_stats()
    probe_task.cancel()
    persist_task.cancel()
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


@app.post("/api/cascade")
async def api_cascade(request: Request):
    """Instant cascade: GPU-1 → GPU-2 → OpenRouter FREE → PAID → PREMIUM → queue.

    SERVER-ONLY. Not for orchestrator or external callers.
    Used for: merge AI text resolution, voice quick KB lookup.
    Prompt format only (not chat). Highest priority (CASCADE=-1).
    Does NOT kill running GPU work — jumps to front of queue.
    """
    body = await request.json()
    return await router.cascade_route("/api/generate", body, http_request=request)


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


@app.post("/route-decision")
async def route_decision(request: Request):
    """Unified routing decision endpoint.

    Preferred input (see KB agent://claude-code/task-routing-unified-design):
      {
        "capability": "chat",              # chat|thinking|coding|extraction|embedding|visual
        "max_tier": "FREE",                # NONE|FREE|PAID|PREMIUM (or resolved from client_id)
        "estimated_tokens": 5000,
        "deadline_iso": "2026-04-15T12:34:56Z",  # absolute deadline; null = no pressure
        "priority": "NORMAL",              # CASCADE|CRITICAL|NORMAL
        "min_model_size": 0,               # 0=any, 14, 30, 120
        "require_tools": false,
        "skip_models": [...],
        "client_id": "..."
      }

    Legacy (accepted for migration until callers are updated):
      - `processing_mode`: FOREGROUND → `deadline_iso = now+300s`, BACKGROUND → null
      - `speed`: FAST → `deadline_iso = now+60s`, STANDARD → null, SLOW → null

    Output: {"target": "local"|"openrouter", "model": "...", "api_base"|"api_key": "..."}
    """
    body = await request.json()

    # Legacy speed / processing_mode → deadline translation (removed once commit 6 lands)
    deadline_iso = body.get("deadline_iso")
    if deadline_iso is None:
        legacy_speed = (body.get("speed") or "").upper()
        legacy_mode = (body.get("processing_mode") or "").upper()
        from datetime import datetime, timezone, timedelta
        if legacy_speed == "FAST" or legacy_mode == "FOREGROUND":
            deadline_iso = (datetime.now(timezone.utc) + timedelta(seconds=60)).isoformat()
        # STANDARD / BACKGROUND / SLOW / missing → None (BATCH bucket)

    priority_str = (body.get("priority") or "NORMAL").upper()
    priority_val = {
        "CASCADE": Priority.CASCADE,
        "CRITICAL": Priority.CRITICAL,
        "NORMAL": Priority.NORMAL,
    }.get(priority_str, Priority.NORMAL)

    decision = await router.decide_route(
        capability=body.get("capability", "chat"),
        max_tier=body.get("max_tier", "NONE"),
        estimated_tokens=body.get("estimated_tokens", 0),
        deadline_iso=deadline_iso,
        priority=priority_val,
        min_model_size=body.get("min_model_size", 0),
        skip_models=body.get("skip_models"),
        require_tools=body.get("require_tools", False),
        client_id=body.get("client_id"),
    )
    return JSONResponse(content=decision)


@app.get("/route-decision/max-context")
async def route_max_context(request: Request):
    """Get max available context tokens for a given tier."""
    max_tier = request.query_params.get("max_tier", "NONE")
    from .openrouter_catalog import get_max_context_tokens
    max_ctx = await get_max_context_tokens(max_tier)
    return JSONResponse(content={"max_context_tokens": max_ctx})


@app.post("/route-decision/model-error")
async def report_model_error_endpoint(request: Request):
    """Report a model error (called by orchestrator after provider 400/500).

    Input: {"model_id": "stepfun/step-3.5-flash:free"}
    Output: {"disabled": true/false, "error_count": N}
    """
    body = await request.json()
    model_id = body.get("model_id", "")
    error_message = body.get("error_message", "")
    from .openrouter_catalog import report_model_error, get_model_errors
    just_disabled = report_model_error(model_id, error_message)
    errors = get_model_errors()
    info = errors.get(model_id, {})
    return JSONResponse(content={
        "model_id": model_id,
        "disabled": info.get("disabled", False),
        "error_count": info.get("count", 0),
        "just_disabled": just_disabled,
    })


@app.post("/route-decision/model-success")
async def report_model_success_endpoint(request: Request):
    """Report a successful model call (resets error counter + records stats).

    Input: {"model_id": "...", "duration_s": 2.5, "input_tokens": 500, "output_tokens": 200}
    """
    body = await request.json()
    model_id = body.get("model_id", "")
    duration_s = body.get("duration_s", 0.0)
    input_tokens = body.get("input_tokens", 0)
    output_tokens = body.get("output_tokens", 0)
    from .openrouter_catalog import report_model_success, record_model_call
    report_model_success(model_id)
    if duration_s > 0:
        record_model_call(model_id, duration_s, input_tokens, output_tokens)
    return JSONResponse(content={"model_id": model_id, "reset": True})


@app.get("/route-decision/model-errors")
async def get_model_errors_endpoint():
    """Get current model error state (for UI monitoring)."""
    from .openrouter_catalog import get_model_errors
    return JSONResponse(content=get_model_errors())


@app.get("/route-decision/model-stats")
async def get_model_stats_endpoint():
    """Get usage statistics for all models (call count, avg response time)."""
    from .openrouter_catalog import get_model_stats
    return JSONResponse(content=get_model_stats())


@app.post("/route-decision/invalidate-client-tier")
async def invalidate_client_tier_endpoint(request: Request):
    """Invalidate cached client tier after client update on server.

    Called by Kotlin server after client's cloudModelPolicy changes.
    Input: {"client_id": "..."} or empty body to invalidate all.
    """
    from app.client_tier_cache import invalidate_cache
    try:
        body = await request.json()
    except Exception:
        body = {}
    client_id = body.get("client_id")
    invalidate_cache(client_id)
    return JSONResponse(content={"invalidated": client_id or "all"})


@app.post("/route-decision/model-reset")
async def reset_model_error_endpoint(request: Request):
    """Re-enable a disabled model (called from UI after manual testing).

    Input: {"model_id": "stepfun/step-3.5-flash:free"}
    """
    body = await request.json()
    model_id = body.get("model_id", "")
    from .openrouter_catalog import reset_model_error
    was_disabled = reset_model_error(model_id)
    return JSONResponse(content={"model_id": model_id, "re_enabled": was_disabled})


@app.get("/route-decision/rate-limits")
async def get_rate_limits_endpoint():
    """Get current rate limit status for OpenRouter queues."""
    from .rate_limiter import get_rate_limit_status
    return JSONResponse(content=get_rate_limit_status())


@app.post("/route-decision/test-model")
async def test_model_endpoint(request: Request):
    """Send a tiny completion to an OpenRouter model to verify it responds.

    Input: {"model_id": "qwen/qwen3-next-80b-a3b-instruct:free"}
    Output: {"ok": true/false, "model_id": "...", "response_ms": N, "response_preview": "...", "error": "..."}
    """
    body = await request.json()
    model_id = body.get("model_id", "")
    if not model_id:
        return JSONResponse(status_code=400, content={"ok": False, "error": "model_id required"})

    from .openrouter_catalog import get_api_key

    api_key = await get_api_key()
    if not api_key:
        return JSONResponse(content={"ok": False, "model_id": model_id, "error": "No OpenRouter API key configured"})

    openai_body = {
        "model": model_id,
        "messages": [{"role": "user", "content": "Reply with exactly: OK"}],
        "stream": False,
        "max_tokens": 10,
        "temperature": 0.0,
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://jervis.damek-soft.eu",
        "X-Title": "Jervis AI Assistant",
    }

    start = time.monotonic()
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(connect=10.0, read=30.0, write=10.0, pool=10.0)) as client:
            resp = await client.post(
                "https://openrouter.ai/api/v1/chat/completions",
                json=openai_body, headers=headers,
            )
            elapsed_ms = int((time.monotonic() - start) * 1000)

            if resp.status_code != 200:
                error_text = resp.text[:300]
                logger.warning("TEST_MODEL: %s returned %d: %s", model_id, resp.status_code, error_text)
                return JSONResponse(content={
                    "ok": False, "model_id": model_id, "response_ms": elapsed_ms,
                    "error": f"HTTP {resp.status_code}: {error_text}",
                })

            data = resp.json()
            choices = data.get("choices") or []
            first_choice = choices[0] if choices else {}
            message = first_choice.get("message") or {}
            content = message.get("content") or ""
            logger.info("TEST_MODEL: %s OK in %dms — response: %s", model_id, elapsed_ms, content[:50])
            return JSONResponse(content={
                "ok": True, "model_id": model_id, "response_ms": elapsed_ms,
                "response_preview": content[:100],
            })
    except Exception as e:
        elapsed_ms = int((time.monotonic() - start) * 1000)
        logger.warning("TEST_MODEL: %s error: %s", model_id, e)
        return JSONResponse(content={
            "ok": False, "model_id": model_id, "response_ms": elapsed_ms,
            "error": str(e)[:300],
        })


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
