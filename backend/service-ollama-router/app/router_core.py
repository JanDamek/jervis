"""OllamaRouter – multi-GPU routing with two-tier request queue.

Two priority levels: CRITICAL (0) and NORMAL (1).
All requests go through RequestQueue — router NEVER rejects.
CRITICAL always GPU (never CPU), preempts NORMAL if needed.
GPU reservations are fully automatic for CRITICAL orchestrator requests.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid

import httpx
from starlette.responses import Response, JSONResponse, StreamingResponse

from .config import settings
from .gpu_state import GpuBackend, GpuPool
from .models import (
    EMBEDDING_MODELS,
    GPU_MODEL_SETS,
    LOCAL_MODEL_CAPABILITIES,
    LOCAL_MODEL_CONTEXT,
    MODEL_SETS,
    MODEL_TO_PRIORITY,
    Priority,
    TrackedRequest,
    VLM_GPU,
)
from .openrouter_catalog import (
    TIER_LEVELS,
    find_cloud_model_for_context,
    get_api_key,
    normalize_tier,
)
from .rate_limiter import acquire_openrouter_slot
from .request_queue import RequestQueue

logger = logging.getLogger("ollama-router.core")


class OllamaRouter:
    def __init__(self, gpu_pool: GpuPool) -> None:
        self.gpu_pool = gpu_pool

        # Request queue (initialized in startup)
        self._queue: RequestQueue | None = None

        # Per-GPU reservation state (supports multiple concurrent reservations)
        self._reservations: dict[str, str] = {}            # gpu_name → session_id
        self._reservation_times: dict[str, float] = {}     # gpu_name → reserved_at
        self._last_critical_activity: dict[str, float] = {}  # gpu_name → last_activity
        self._reservation_lock = asyncio.Lock()

        # Background model loading tasks (per GPU)
        self._bg_load_tasks: dict[str, asyncio.Task] = {}  # gpu_name → task

        # HTTP client for internal management calls (load/unload/health)
        self._mgmt_client: httpx.AsyncClient | None = None

        # Watchdog tasks
        self._watchdog_task: asyncio.Task | None = None
        self._request_timeout_task: asyncio.Task | None = None
        self._gpu_recovery_task: asyncio.Task | None = None
        self._idle_notify_task: asyncio.Task | None = None
        self._warmup_task: asyncio.Task | None = None

        # GPU idle notification state
        self._last_any_gpu_activity: float = time.monotonic()
        self._idle_notified: bool = False  # True after idle notification sent; reset on activity

        # Whisper GPU coordination (p40-2)
        # Flag-based: whisper calls /router/whisper-notify and /router/whisper-done.
        # No polling — router only knows what whisper tells it.
        # Stale safety: auto-reset after 2h (crash resilience).
        self._whisper_active = False
        self._whisper_active_since: float = 0
        self._whisper_done_event = asyncio.Event()
        self._whisper_done_event.set()  # Initially idle

    async def startup(self) -> None:
        """Initialize router state on startup."""
        self._mgmt_client = httpx.AsyncClient(timeout=httpx.Timeout(30.0))
        # Sync GPU state from all backends (initial check only, fail fast after)
        await self.gpu_pool.sync_state(self._mgmt_client)
        # Preload per-GPU model sets (each GPU gets its own models)
        await self._preload_per_gpu_models()
        # Start request queue (GPU only, no CPU backend)
        self._queue = RequestQueue(self.gpu_pool, self)
        await self._queue.start()
        # Start background tasks
        self._watchdog_task = asyncio.create_task(self._reservation_watchdog())
        self._request_timeout_task = asyncio.create_task(self._request_timeout_watchdog())
        self._idle_notify_task = asyncio.create_task(self._idle_notify_watchdog())
        # Start warmup loop (keeps models in VRAM)
        if settings.warmup_enabled:
            self._warmup_task = asyncio.create_task(self._warmup_loop())
        # Start GPU recovery only if any backend failed initial sync
        if any(not b.healthy for b in self.gpu_pool.all_backends):
            self._start_gpu_recovery()
        logger.info("Router started with %d GPU backend(s), queue enabled, warmup=%s",
                     len(self.gpu_pool.all_backends), settings.warmup_enabled)

    async def shutdown(self) -> None:
        """Cleanup on shutdown."""
        if self._queue:
            await self._queue.stop()
        if self._watchdog_task:
            self._watchdog_task.cancel()
        if self._request_timeout_task:
            self._request_timeout_task.cancel()
        if self._gpu_recovery_task:
            self._gpu_recovery_task.cancel()
        if self._idle_notify_task:
            self._idle_notify_task.cancel()
        if self._warmup_task:
            self._warmup_task.cancel()
        for task in self._bg_load_tasks.values():
            if not task.done():
                task.cancel()
        if self._mgmt_client:
            await self._mgmt_client.aclose()

    async def _preload_per_gpu_models(self) -> None:
        """Preload per-GPU model sets according to GPU_MODEL_SETS config.

        Skips on-demand models (keep_alive != "-1") to avoid VRAM overcommit.
        VLM is loaded on-demand when requested — swaps with 14b on GPU-2.
        """
        # Models with keep_alive != "-1" are on-demand — skip at startup
        on_demand_models = {
            m
            for s in MODEL_SETS.values()
            if s.get("keep_alive") != "-1"
            for m in s["models"]
        }

        for backend in self.gpu_pool.healthy_backends:
            gpu_models = GPU_MODEL_SETS.get(backend.name, [settings.orchestrator_model])
            for model in gpu_models:
                if model in on_demand_models:
                    logger.info("Skipping on-demand model %s on GPU %s (loaded when requested)", model, backend.name)
                    continue
                if backend.has_model(model):
                    logger.info("GPU %s already has %s loaded", backend.name, model)
                    continue
                logger.info("Preloading %s on GPU %s ...", model, backend.name)
                ok = await self.gpu_pool.load_model(backend, model, self._mgmt_client)
                if ok:
                    logger.info("Preloaded %s on GPU %s", model, backend.name)
                else:
                    logger.warning("Failed to preload %s on GPU %s", model, backend.name)

    # ── Capability-based route decision ────────────────────────────────

    async def decide_route(
        self, capability: str, max_tier: str, estimated_tokens: int,
        processing_mode: str = "FOREGROUND",
        skip_models: list[str] | None = None,
        require_tools: bool = False,
    ) -> dict:
        """Capability-based routing decision.

        1. NONE → always local GPU (FG NONE preempts BG via CRITICAL priority)
        2. FG + FREE+ → always OpenRouter
        3. BG + FREE+ ≤48k + GPU free → local GPU
        4. BG + FREE+ >48k OR GPU busy → OpenRouter (yield GPU for FG)
        5. No cloud model fits → local fallback

        skip_models: model IDs to skip (already tried and failed in this request).
        require_tools: if True, only cloud models with supportsTools=True are eligible.
        Returns: {"target": "local"|"openrouter", "model": "...", "api_base": "..."}
        """
        max_tier = normalize_tier(max_tier)  # backward compat: PAID_LOW→PAID, PAID_HIGH→PREMIUM
        tier_level = TIER_LEVELS.get(max_tier, 0)
        is_background = processing_mode == "BACKGROUND"

        # Find local model for requested capability
        local_model = self._find_local_model_for_capability(capability)
        api_base = f"http://jervis-ollama-router:{settings.router_port}"
        local_result = {
            "target": "local",
            "model": local_model or settings.orchestrator_model,
            "api_base": api_base,
        }

        # Rule 1: NONE → always local GPU (FG NONE preempts BG via CRITICAL priority)
        if tier_level == 0:
            logger.info("Route decision: max_tier=NONE → local (tokens=%d)", estimated_tokens)
            return local_result

        # Rule 2: FOREGROUND + FREE+ → prefer local GPU, OpenRouter as fallback
        # Local GPU models (30b, 14b) are much better at following tool instructions
        # than FREE OpenRouter models. Use cloud only when GPU is busy or context > 48k.
        if not is_background:
            # Try local GPU first (if context fits and GPU is free)
            whisper_busy = self.check_whisper_busy()
            gpu_free = any(
                b.healthy and b.active_request_count() == 0
                and not (b.name == VLM_GPU and whisper_busy)
                for b in self.gpu_pool.all_backends
                if local_model in GPU_MODEL_SETS.get(b.name, [])
            )
            if estimated_tokens <= 48_000 and gpu_free:
                logger.info("Route decision: FG tier=%s → local GPU (free, tokens=%d)",
                            max_tier, estimated_tokens)
                return local_result

            # GPU busy or context too large → fall back to OpenRouter
            cloud_model = await find_cloud_model_for_context(estimated_tokens, tier_level, skip_models, capability=capability, require_tools=require_tools)
            if cloud_model:
                queue = "FREE" if cloud_model.endswith(":free") else "PAID"
                slot_ok = await acquire_openrouter_slot(queue, timeout=65.0)
                if not slot_ok:
                    logger.warning("Route decision: FG rate limit timeout for %s queue → local fallback", queue)
                    return local_result
                logger.info("Route decision: FG tier=%s, GPU busy → cloud %s (tokens=%d, skip=%s)",
                            max_tier, cloud_model, estimated_tokens, skip_models or [])
                api_key = await get_api_key()
                return {"target": "openrouter", "model": cloud_model, "api_key": api_key}
            # No cloud model → local fallback (queue behind current GPU work)
            logger.info("Route decision: FG tier=%s, no cloud model → local fallback (tokens=%d)",
                        max_tier, estimated_tokens)
            return local_result

        # Rule 3: BACKGROUND + FREE+ — local only if ≤48k AND GPU free
        # If GPU busy (e.g. FG NONE running) → BG yields GPU, goes to cloud
        target_model = local_result["model"]
        whisper_busy = self.check_whisper_busy()
        gpu_free = any(
            b.healthy and b.active_request_count() == 0
            and not (b.name == VLM_GPU and whisper_busy)
            for b in self.gpu_pool.all_backends
            if target_model in GPU_MODEL_SETS.get(b.name, [])
        )
        if estimated_tokens <= 48_000 and gpu_free:
            logger.info("Route decision: BG tier=%s ≤48k + GPU free → local (tokens=%d)",
                        max_tier, estimated_tokens)
            return local_result

        # Rule 4: BG >48k OR GPU busy → OpenRouter (with rate limiting)
        cloud_model = await find_cloud_model_for_context(estimated_tokens, tier_level, skip_models, capability=capability, require_tools=require_tools)
        if cloud_model:
            queue = "FREE" if cloud_model.endswith(":free") else "PAID"
            slot_ok = await acquire_openrouter_slot(queue, timeout=65.0)
            if not slot_ok:
                logger.warning("Route decision: BG rate limit timeout for %s queue → local fallback", queue)
                return local_result
            reason = ">48k" if estimated_tokens > 48_000 else "GPU busy"
            logger.info("Route decision: BG %s tier=%s → cloud %s (tokens=%d, skip=%s)",
                        reason, max_tier, cloud_model, estimated_tokens, skip_models or [])
            api_key = await get_api_key()
            return {"target": "openrouter", "model": cloud_model, "api_key": api_key}

        # Rule 4: No cloud model fits → local fallback
        logger.info("Route decision: tier=%s, no cloud model fits → local fallback (tokens=%d)",
                    max_tier, estimated_tokens)
        return local_result

    # ── Instant cascade routing ────────────────────────────────────────

    async def cascade_route(
        self,
        api_path: str,
        body: dict,
        http_request=None,
    ) -> Response:
        """Instant cascade: try GPU-1 → GPU-2 → OpenRouter FREE → PAID → PREMIUM → queue.

        Used for latency-critical internal calls (voice pipeline, live assist).
        Does NOT preempt running GPU work. Falls back to queuing if all busy.

        Priority order:
        1. GPU-1 (p40-1) — if idle, route immediately
        2. GPU-2 (p40-2) — if idle, route immediately
        3. OpenRouter FREE — if available model found
        4. OpenRouter PAID — if available
        5. OpenRouter PREMIUM — if available
        6. Queue on first GPU that frees up (NORMAL priority, no preemption)
        """
        model = self._extract_model(body)
        request_id = f"cascade-{str(uuid.uuid4())[:6]}"
        start = time.monotonic()
        whisper_busy = self.check_whisper_busy()

        # Step 1-2: Try GPUs immediately (no queuing, no preemption)
        for backend in self.gpu_pool.all_backends:
            if not backend.healthy:
                continue
            if backend.active_request_count() > 0:
                continue
            if backend.loading_in_progress:
                continue
            if backend.name == VLM_GPU and whisper_busy:
                continue
            # Check if this GPU can run the model
            gpu_models = GPU_MODEL_SETS.get(backend.name, [])
            if model and model not in gpu_models:
                continue

            logger.info("CASCADE: %s → %s (free, immediate dispatch)", request_id, backend.name)
            return await self._cascade_dispatch_gpu(
                request_id, api_path, body, model, backend, http_request,
            )

        # Step 3-5: Try OpenRouter tiers (FREE → PAID → PREMIUM)
        estimated_tokens = self._estimate_tokens(body)
        for tier_name, tier_level in [("FREE", 1), ("PAID", 2), ("PREMIUM", 3)]:
            cloud_model = await find_cloud_model_for_context(
                estimated_tokens, tier_level, capability="chat",
            )
            if cloud_model:
                api_key = await get_api_key()
                logger.info("CASCADE: %s → OpenRouter %s (%s) after %.1fms",
                            request_id, tier_name, cloud_model, (time.monotonic() - start) * 1000)
                return await self._cascade_dispatch_cloud(
                    request_id, api_path, body, cloud_model, api_key,
                )

        # Step 6: All busy — CASCADE queue (highest priority, preempts entire queue, waits for running GPU)
        logger.info("CASCADE: %s → CASCADE queue (all targets busy, front of queue)", request_id)
        return await self.route_request(api_path, body, Priority.CASCADE.value, http_request)

    async def _cascade_dispatch_gpu(
        self, request_id: str, api_path: str, body: dict,
        model: str, backend, http_request,
    ) -> Response:
        """Dispatch directly to a specific GPU backend via the queue."""
        request = TrackedRequest(
            request_id=request_id,
            model=model or settings.orchestrator_model,
            priority=Priority.CASCADE,
            api_path=api_path,
            body=body,
        )
        self._last_any_gpu_activity = time.monotonic()
        self._idle_notified = False
        return await self._queue.submit(request)

    async def _cascade_dispatch_cloud(
        self, request_id: str, api_path: str, body: dict,
        cloud_model: str, api_key: str,
    ) -> Response:
        """Proxy request to OpenRouter cloud model."""
        from app.openrouter_proxy import proxy_to_openrouter
        return await proxy_to_openrouter(api_path, body, cloud_model, api_key, request_id)

    @staticmethod
    def _estimate_tokens(body: dict) -> int:
        """Rough token estimation from request body."""
        messages = body.get("messages", [])
        if messages:
            total_chars = sum(len(str(m.get("content", ""))) for m in messages)
            return max(total_chars // 4, 100)
        prompt = body.get("prompt", "")
        return max(len(prompt) // 4, 100)

    def _find_local_model_for_capability(self, capability: str) -> str | None:
        """Find a local model that has the requested capability.

        When multiple models support the capability, prefer the one on a free GPU.
        This enables load balancing: 30b on p40-1 + 14b on p40-2 both handle 'chat'.
        """
        candidates = [
            model for model, caps in LOCAL_MODEL_CAPABILITIES.items()
            if capability in caps
        ]
        if not candidates:
            return None
        if len(candidates) == 1:
            return candidates[0]

        # Multiple candidates — prefer the one on a free GPU
        whisper_busy = self.check_whisper_busy()
        for model in candidates:
            for backend in self.gpu_pool.all_backends:
                if model in GPU_MODEL_SETS.get(backend.name, []):
                    if backend.healthy and backend.active_request_count() == 0:
                        if not (backend.name == VLM_GPU and whisper_busy):
                            logger.debug("Model %s selected (GPU %s is free)", model, backend.name)
                            return model

        # All busy — return first candidate (will queue)
        return candidates[0]

    # ── Main routing entry point ────────────────────────────────────────

    async def route_request(
        self,
        api_path: str,
        body: dict,
        priority_header: int | None = None,
        http_request=None,
    ) -> Response:
        """Route an Ollama API request via the request queue.

        Router ALWAYS accepts. Request is queued and dispatched when a
        backend slot becomes available. Never returns 503/reject (except
        when NORMAL queue is full → 429 back-pressure).

        If http_request (FastAPI Request) is provided, monitors client disconnect
        and sets cancel_event to abort zombie requests / dequeue.
        """
        model = self._extract_model(body)
        priority = self._resolve_priority(model, priority_header)
        request_id = str(uuid.uuid4())[:8]

        request = TrackedRequest(
            request_id=request_id,
            model=model,
            priority=priority,
            api_path=api_path,
            body=body,
        )

        # Track GPU activity for idle notification
        self._last_any_gpu_activity = time.monotonic()
        self._idle_notified = False

        # ── Entry logging ──
        num_ctx = body.get("options", {}).get("num_ctx") if isinstance(body.get("options"), dict) else None
        qdepth = self._queue.queue_depth if self._queue else {}
        logger.info(
            "REQUEST_IN: id=%s model=%s priority=%s path=%s num_ctx=%s queue=%s",
            request_id, model, priority.name, api_path, num_ctx, qdepth,
        )

        # Start client disconnect monitor if http_request provided
        disconnect_task = None
        if http_request is not None:
            disconnect_task = asyncio.create_task(
                self._monitor_client_disconnect(http_request, request)
            )

        start_time = time.monotonic()
        try:
            response = await self._queue.submit(request)
            duration = time.monotonic() - start_time
            if isinstance(response, StreamingResponse):
                logger.info(
                    "REQUEST_OUT: id=%s routing=%.2fs (streaming — see PROXY_STREAM for total)",
                    request_id, duration,
                )
            else:
                logger.info(
                    "REQUEST_OUT: id=%s duration=%.2fs",
                    request_id, duration,
                )
            return response
        except Exception as e:
            duration = time.monotonic() - start_time
            logger.error(
                "REQUEST_ERROR: id=%s duration=%.2fs error=%s",
                request_id, duration, str(e),
            )
            raise
        finally:
            # Cancel disconnect monitor
            if disconnect_task and not disconnect_task.done():
                disconnect_task.cancel()
                try:
                    await disconnect_task
                except (asyncio.CancelledError, Exception):
                    pass
            # Notify queue that a slot may have been freed
            if self._queue:
                self._queue.notify_slot_freed()

    @staticmethod
    async def _monitor_client_disconnect(http_request, tracked_request: TrackedRequest) -> None:
        """Monitor HTTP client disconnect and set cancel_event to abort zombie proxying."""
        try:
            while not await http_request.is_disconnected():
                await asyncio.sleep(2)
            # Client disconnected — signal cancellation
            if not tracked_request.cancel_event.is_set():
                logger.warning(
                    "CLIENT_DISCONNECT: id=%s model=%s — setting cancel_event",
                    tracked_request.request_id, tracked_request.model,
                )
                tracked_request.cancel_event.set()
        except asyncio.CancelledError:
            pass  # Normal cleanup when request completes before disconnect

    # ── CRITICAL reservation management ─────────────────────────────────
    # Reservations are managed here (not in RequestQueue) because they're
    # tied to the orchestrator's session lifecycle, not individual requests.
    # The queue calls notify_critical_activity() when dispatching CRITICAL
    # to a GPU, which creates/refreshes reservations automatically.

    def notify_critical_activity(self, gpu_name: str) -> None:
        """Called by RequestQueue when a CRITICAL request is dispatched to a GPU.

        Creates or refreshes the reservation for this GPU. The watchdog will
        auto-release it after orchestrator_idle_timeout_s of no CRITICAL activity.
        Also cancels any background model loading on the GPU.
        """
        now = time.monotonic()
        gpu = self.gpu_pool.backends.get(gpu_name)

        if gpu_name in self._reservations:
            # Refresh existing reservation
            self._last_critical_activity[gpu_name] = now
            return

        # Create new reservation
        self._reservations[gpu_name] = "critical"
        self._reservation_times[gpu_name] = now
        self._last_critical_activity[gpu_name] = now
        if gpu:
            gpu.reserved_by = "critical"
            gpu.reserved_at = now

        # Cancel background model loading if running on this GPU
        bg_task = self._bg_load_tasks.pop(gpu_name, None)
        if bg_task and not bg_task.done():
            bg_task.cancel()
            logger.info("Cancelled background model loading on GPU %s (CRITICAL reservation)", gpu_name)

        logger.info("GPU %s reserved for CRITICAL (auto-created by queue dispatch)", gpu_name)

    # ── Reservation lifecycle ───────────────────────────────────────────

    def _clear_reservation(self, gpu_name: str) -> None:
        """Clear reservation for a specific GPU."""
        gpu = self.gpu_pool.backends.get(gpu_name)
        if gpu:
            gpu.reserved_by = None
            gpu.reserved_at = None
        self._reservations.pop(gpu_name, None)
        self._reservation_times.pop(gpu_name, None)
        self._last_critical_activity.pop(gpu_name, None)

    def _schedule_bg_load(self, gpu_name: str) -> None:
        """Schedule background model loading on a GPU after reservation release."""
        gpu = self.gpu_pool.backends.get(gpu_name)
        if gpu:
            task = asyncio.create_task(self._delayed_background_load(gpu))
            self._bg_load_tasks[gpu_name] = task

    async def _delayed_background_load(self, gpu: GpuBackend) -> None:
        """After a delay, ensure per-GPU model set is loaded.

        Uses GPU_MODEL_SETS to determine which models belong on each GPU.
        p40-1: only 30b (no embedding). p40-2: 30b + embedding.
        """
        try:
            await asyncio.sleep(settings.background_load_delay_s)

            # Double-check GPU didn't get reserved during the delay
            if gpu.name in self._reservations:
                return

            # Ensure per-GPU model set is loaded
            gpu_models = GPU_MODEL_SETS.get(gpu.name, [settings.orchestrator_model])
            logger.info(
                "Ensuring per-GPU model set on GPU %s: %s (existing: %s)",
                gpu.name, gpu_models, list(gpu.loaded_models.keys()),
            )
            for model in gpu_models:
                if not gpu.has_model(model):
                    await self.gpu_pool.load_model(gpu, model, self._mgmt_client)
        except asyncio.CancelledError:
            logger.info("Background model loading cancelled for GPU %s", gpu.name)
        except Exception as e:
            logger.error("Background model loading failed for GPU %s: %s", gpu.name, e)

    # ── Reservation watchdog ────────────────────────────────────────────

    async def _reservation_watchdog(self) -> None:
        """Auto-release GPU reservations on idle timeout (per-GPU).

        Runs every 15s. Releases reservation if no CRITICAL request
        arrived within ``orchestrator_idle_timeout_s`` (default 60s).
        """
        logger.info("Watchdog started (check every 15s, idle limit=%ds)", settings.orchestrator_idle_timeout_s)
        while True:
            try:
                await asyncio.sleep(15)
                if not self._reservations:
                    continue

                now = time.monotonic()
                async with self._reservation_lock:
                    for gpu_name in list(self._reservations.keys()):
                        reserved_at = self._reservation_times.get(gpu_name)
                        last_activity = self._last_critical_activity.get(gpu_name)
                        idle_s = int(now - last_activity) if last_activity else 0
                        age_s = int(now - reserved_at) if reserved_at else 0

                        logger.info(
                            "Watchdog tick: GPU %s reserved %ds, idle %ds (limit=%ds)",
                            gpu_name, age_s, idle_s, settings.orchestrator_idle_timeout_s,
                        )

                        # Absolute timeout (safety net)
                        if reserved_at and age_s > settings.orchestrator_reservation_timeout_s:
                            logger.warning(
                                "GPU %s reservation exceeded %ds absolute timeout, auto-releasing",
                                gpu_name, settings.orchestrator_reservation_timeout_s,
                            )
                            self._clear_reservation(gpu_name)
                            self._schedule_bg_load(gpu_name)
                            continue

                        # Idle timeout
                        if last_activity and idle_s > settings.orchestrator_idle_timeout_s:
                            logger.info(
                                "GPU %s reservation idle for %ds (limit=%ds), auto-releasing",
                                gpu_name, idle_s, settings.orchestrator_idle_timeout_s,
                            )
                            self._clear_reservation(gpu_name)
                            self._schedule_bg_load(gpu_name)

            except asyncio.CancelledError:
                return
            except Exception as e:
                logger.error("Watchdog error: %s", e)

    async def _request_timeout_watchdog(self) -> None:
        """Cancel requests exceeding max_request_timeout_s.

        Runs every 15s. Detects zombie requests (client disconnected but
        proxy still running) and cancels them to free GPU resources.
        Also logs periodic active request counts for observability.
        """
        logger.info("Request timeout watchdog started (check every 15s, limit=%ds)", settings.max_request_timeout_s)
        while True:
            try:
                await asyncio.sleep(15)
                now = time.monotonic()
                total_active = 0
                for backend in self.gpu_pool.all_backends:
                    total_active += backend.active_request_count()
                    for req_id, req in list(backend.active_requests.items()):
                        age_s = now - req.created_at
                        if age_s > settings.max_request_timeout_s and not req.cancel_event.is_set():
                            logger.warning(
                                "REQUEST_TIMEOUT: id=%s model=%s age=%.0fs (limit=%ds) — cancelling",
                                req_id, req.model, age_s, settings.max_request_timeout_s,
                            )
                            req.cancel_event.set()

                if total_active > 0:
                    qdepth = self._queue.queue_depth if self._queue else {}
                    logger.info(
                        "ACTIVE_REQUESTS: gpu=%d queue=%s",
                        total_active, qdepth,
                    )

            except asyncio.CancelledError:
                return
            except Exception as e:
                logger.error("Request timeout watchdog error: %s", e)

    async def _idle_notify_watchdog(self) -> None:
        """Notify Kotlin server when GPU has been idle for gpu_idle_notify_after_s.

        Runs every 30s. When all GPUs have had no requests for the configured
        idle threshold (default 5 min), sends a POST to Kotlin server's
        /internal/gpu-idle endpoint. This triggers the BackgroundEngine to
        immediately run analytical/proactive tasks instead of waiting for
        the normal 30-min idle review interval.

        Notification is sent once per idle period — reset when new activity arrives.
        """
        idle_limit = settings.gpu_idle_notify_after_s
        logger.info("Idle notify watchdog started (check every 30s, idle limit=%ds)", idle_limit)
        while True:
            try:
                await asyncio.sleep(30)

                # Check if any GPU has active requests
                total_active = sum(b.active_request_count() for b in self.gpu_pool.all_backends)
                if total_active > 0:
                    # GPUs are busy — skip
                    continue

                idle_s = time.monotonic() - self._last_any_gpu_activity
                if idle_s < idle_limit:
                    continue

                if self._idle_notified:
                    # Already notified for this idle period
                    continue

                # GPU idle for >= threshold — notify Kotlin server
                self._idle_notified = True
                logger.info("GPU_IDLE: no requests for %ds (limit=%ds), notifying Kotlin server", int(idle_s), idle_limit)
                try:
                    async with httpx.AsyncClient(timeout=10) as http:
                        resp = await http.post(
                            f"{settings.kotlin_server_url}/internal/gpu-idle",
                            json={"idle_seconds": int(idle_s)},
                        )
                        if resp.status_code == 200:
                            logger.info("GPU_IDLE: Kotlin server notified successfully")
                        else:
                            logger.warning("GPU_IDLE: Kotlin server returned %d", resp.status_code)
                except Exception as e:
                    logger.warning("GPU_IDLE: Failed to notify Kotlin server: %s", e)

            except asyncio.CancelledError:
                return
            except Exception as e:
                logger.error("Idle notify watchdog error: %s", e)

    async def _warmup_loop(self) -> None:
        """Periodically ping GPU backends to keep models loaded in VRAM.

        Pings only models assigned to each GPU via GPU_MODEL_SETS.
        Only pings idle GPUs (no active requests and idle for >80% of interval).
        """
        interval = settings.warmup_interval_s
        logger.info("Warmup loop started (interval=%ds, gpu_model_sets=%s)", interval, GPU_MODEL_SETS)

        while True:
            try:
                await asyncio.sleep(interval)

                for backend in self.gpu_pool.all_backends:
                    if not backend.healthy:
                        continue
                    if backend.active_request_count() > 0:
                        continue
                    # Skip if GPU had recent activity
                    last_activity = time.monotonic() - self._last_any_gpu_activity
                    if last_activity < interval * 0.8:
                        continue

                    # Ping only permanent models assigned to this GPU (skip on-demand like VLM)
                    gpu_models = GPU_MODEL_SETS.get(backend.name, [settings.orchestrator_model])
                    on_demand_models = {
                        m for s in MODEL_SETS.values()
                        if s.get("keep_alive") != "-1"
                        for m in s["models"]
                    }
                    for model_name in gpu_models:
                        if model_name in on_demand_models:
                            continue
                        try:
                            if model_name in EMBEDDING_MODELS:
                                payload = {"model": model_name, "prompt": "warmup", "keep_alive": -1}
                                resp = await self._mgmt_client.post(
                                    f"{backend.url}/api/embeddings",
                                    json=payload,
                                    timeout=settings.model_load_timeout_s,
                                )
                            else:
                                payload = {"model": model_name, "prompt": "", "keep_alive": -1, "stream": False}
                                resp = await self._mgmt_client.post(
                                    f"{backend.url}/api/generate",
                                    json=payload,
                                    timeout=settings.model_load_timeout_s,
                                )
                            if resp.status_code == 200:
                                logger.debug("Warmup ping OK: %s model=%s", backend.name, model_name)
                            else:
                                logger.warning(
                                    "Warmup ping HTTP %d: %s model=%s",
                                    resp.status_code, backend.name, model_name,
                                )
                        except Exception as e:
                            logger.warning("Warmup ping failed: %s model=%s error=%s", backend.name, model_name, e)

            except asyncio.CancelledError:
                return
            except Exception as e:
                logger.error("Warmup loop error: %s", e)

    # ── GPU recovery (on-demand, not heartbeat) ──────────────────────────

    def _start_gpu_recovery(self) -> None:
        """Start GPU recovery loop if not already running.

        Called when a GPU is detected as unhealthy (startup sync failure,
        proxy error, etc.). The loop stops automatically once all GPUs recover.
        """
        if self._gpu_recovery_task and not self._gpu_recovery_task.done():
            return  # Already recovering
        self._gpu_recovery_task = asyncio.create_task(self._gpu_recovery_loop())

    async def _gpu_recovery_loop(self) -> None:
        """Check unhealthy GPU backends every 60s until all recover.

        NOT a permanent heartbeat — only runs when triggered by a failure,
        stops as soon as all backends are healthy again.
        """
        unhealthy_names = [b.name for b in self.gpu_pool.all_backends if not b.healthy]
        logger.info("GPU_RECOVERY: started for %s (checking every 60s)", unhealthy_names)
        while True:
            try:
                await asyncio.sleep(60)
                unhealthy = [b for b in self.gpu_pool.all_backends if not b.healthy]
                if not unhealthy:
                    logger.info("GPU_RECOVERY: all backends healthy, stopping")
                    return

                logger.info("GPU_RECOVERY: checking %d unhealthy backend(s): %s",
                            len(unhealthy), [b.name for b in unhealthy])
                await self.gpu_pool.check_health(self._mgmt_client)

                # Preload per-GPU model set on any newly recovered GPUs
                for backend in unhealthy:
                    if backend.healthy:
                        gpu_models = GPU_MODEL_SETS.get(backend.name, [settings.orchestrator_model])
                        logger.info("GPU_RECOVERY: %s is back — preloading %s",
                                    backend.name, gpu_models)
                        for model in gpu_models:
                            await self.gpu_pool.load_model(
                                backend, model, self._mgmt_client
                            )

            except asyncio.CancelledError:
                return
            except Exception as e:
                logger.error("GPU recovery loop error: %s", e)

    # ── Whisper GPU coordination (p40-2) ────────────────────────────────
    # Flag-based: whisper calls notify/done. No polling, no health checks.
    # Stale safety: auto-reset after 2h (crash resilience).

    def check_whisper_busy(self) -> bool:
        """Check if whisper is actively transcribing (based on notify/done)."""
        if not self._whisper_active:
            return False
        # Stale safety: auto-reset after 2h
        if time.monotonic() - self._whisper_active_since > 7200:
            logger.warning("WHISPER_STALE: active for >2h, auto-resetting")
            self._whisper_active = False
            self._whisper_done_event.set()
            return False
        return True

    async def wait_for_whisper_done(self, timeout: float = 3600) -> bool:
        """Wait for whisper-done event (no polling — event-based)."""
        if not self.check_whisper_busy():
            return True
        logger.info("WHISPER_WAIT: waiting for whisper-done event (timeout=%ds)", timeout)
        try:
            await asyncio.wait_for(self._whisper_done_event.wait(), timeout=timeout)
            logger.info("WHISPER_WAIT: whisper done, GPU available")
            return True
        except asyncio.TimeoutError:
            logger.warning("WHISPER_WAIT: timeout after %ds", timeout)
            return False

    async def notify_whisper_wants_gpu(self) -> bool:
        """Called when whisper wants GPU. Sets flag, waits for VLM to finish."""
        self._whisper_active = True
        self._whisper_active_since = time.monotonic()
        self._whisper_done_event.clear()

        p40_2 = self.gpu_pool.backends.get(VLM_GPU)
        if p40_2 is None:
            logger.info("WHISPER_NOTIFY: no VLM GPU configured, granting immediately")
            return True

        vlm_active = any(
            r.model not in EMBEDDING_MODELS
            for r in p40_2.active_requests.values()
        )
        if not vlm_active:
            logger.info("WHISPER_NOTIFY: GPU available, granting immediately")
            return True

        logger.info("WHISPER_NOTIFY: VLM active on p40-2, waiting for completion")
        while any(
            r.model not in EMBEDDING_MODELS
            for r in p40_2.active_requests.values()
        ):
            await asyncio.sleep(2)
        logger.info("WHISPER_NOTIFY: VLM finished, granting GPU to whisper")
        return True

    def notify_whisper_done(self) -> None:
        """Called when whisper finishes transcription. Clears flag."""
        self._whisper_active = False
        self._whisper_done_event.set()
        logger.info("WHISPER_DONE: flag cleared")

    # ── Helpers ─────────────────────────────────────────────────────────

    def _extract_model(self, body: dict) -> str:
        return body.get("model", "unknown")

    def _resolve_priority(self, model: str, priority_header: int | None) -> Priority:
        if priority_header is not None:
            try:
                return Priority(priority_header)
            except ValueError:
                pass
        return MODEL_TO_PRIORITY.get(model, Priority.NORMAL)

    @property
    def is_reserved(self) -> bool:
        return bool(self._reservations)
