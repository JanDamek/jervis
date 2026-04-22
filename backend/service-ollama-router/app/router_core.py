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
from typing import AsyncIterator, Union

import httpx

from .config import settings
from .gpu_state import GpuBackend, GpuPool
from datetime import datetime, timezone
from enum import Enum as _StdEnum

from .models import (
    EMBEDDING_MODELS,
    EMBEDDING_PATHS,
    GPU_MODEL_SETS,
    LOCAL_MODEL_CAPABILITIES,
    LOCAL_MODEL_CONTEXT,
    LOCAL_MODEL_SIZE,
    MODEL_SETS,
    MODEL_TO_PRIORITY,
    Capability,
    Priority,
    TrackedRequest,
    VLM_GPU,
)
from .proxy import is_streaming_request


class _Bucket(str, _StdEnum):
    """Private router bucket derived from (deadline - now, priority).

    Never leaves router_core.py. Callers pass `deadline_iso` (Instant) and
    `priority`; the bucket is recomputed at every call so changes to the
    deadline take effect without cache invalidation.
    """
    REALTIME = "REALTIME"   # < 10s OR priority=CASCADE (voice, preempts)
    URGENT = "URGENT"       # < 5 min   — user actively waiting
    NORMAL = "NORMAL"       # < 1 h     — standard work
    BATCH = "BATCH"         # no deadline / > 1 h (background, local-preferred)


def detect_capability_from_body(api_path: str, body: dict) -> str:
    """Infer the request capability from the HTTP path + body.

    Rules (first match wins):
      - path contains `/embed` (Ollama embedding endpoints)  → "embedding"
      - body has `images` OR any message content has image parts → "visual"
      - everything else → "chat"

    Normalized to a Capability enum value (lowercase). Callers may still pass
    an explicit X-Capability header, which takes precedence over this helper.
    """
    if "/embed" in (api_path or ""):
        return "embedding"

    if isinstance(body, dict):
        # Ollama /api/generate with images[]
        if body.get("images"):
            return "visual"
        # Ollama /api/chat — messages[].images or multimodal content parts
        messages = body.get("messages")
        if isinstance(messages, list):
            for m in messages:
                if not isinstance(m, dict):
                    continue
                if m.get("images"):
                    return "visual"
                content = m.get("content")
                if isinstance(content, list):
                    for part in content:
                        if isinstance(part, dict) and part.get("type") in ("image", "image_url"):
                            return "visual"
    return "chat"


def _bucket_from_deadline(
    deadline_iso: str | None,
    priority: Priority,
    now: datetime | None = None,
) -> _Bucket:
    """Compute the private urgency bucket. See `_Bucket` docstring for rules."""
    if priority == Priority.CASCADE:
        return _Bucket.REALTIME
    if not deadline_iso:
        return _Bucket.BATCH
    try:
        dt = datetime.fromisoformat(deadline_iso.replace("Z", "+00:00"))
    except ValueError:
        return _Bucket.BATCH
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    now = now or datetime.now(timezone.utc)
    remaining_s = (dt - now).total_seconds()
    if remaining_s < 10:
        return _Bucket.REALTIME
    if remaining_s < 300:
        return _Bucket.URGENT
    if remaining_s < 3600:
        return _Bucket.NORMAL
    return _Bucket.BATCH
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
        self._active_requests_task: asyncio.Task | None = None
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

        # XTTS GPU coordination (same flag-based contract as whisper). User
        # policy: during whisper streaming AND during XTTS generation nothing
        # else may run on the GPU, so Ollama dispatcher waits on either flag.
        self._tts_active = False
        self._tts_active_since: float = 0
        self._tts_done_event = asyncio.Event()
        self._tts_done_event.set()

    async def startup(self) -> None:
        """Initialize router state on startup."""
        self._mgmt_client = httpx.AsyncClient(timeout=httpx.Timeout(30.0))
        # Validate coverage matrix (capability × tier) — fail fast on gaps.
        await self._validate_coverage_matrix()
        # Sync GPU state from all backends (initial check only, fail fast after)
        await self.gpu_pool.sync_state(self._mgmt_client)
        # Preload per-GPU model sets (each GPU gets its own models)
        await self._preload_per_gpu_models()
        # Start request queue (GPU only, no CPU backend)
        self._queue = RequestQueue(self.gpu_pool, self)
        await self._queue.start()
        # Start background tasks
        self._watchdog_task = asyncio.create_task(self._reservation_watchdog())
        self._active_requests_task = asyncio.create_task(self._active_requests_logger())
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
        if self._active_requests_task:
            self._active_requests_task.cancel()
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

    async def _validate_coverage_matrix(self) -> None:
        """Ensure every (capability, tier) combination has at least one model available.

        Local covers tier=NONE (no cloud allowed). Cloud covers FREE/PAID/PREMIUM when
        the queue has any enabled model; local is the guaranteed fallback. We require
        a local fallback for every core capability so the router never returns a
        'no model available' runtime error.
        """
        core_caps = [
            Capability.CHAT.value,
            Capability.THINKING.value,
            Capability.CODING.value,
            Capability.EXTRACTION.value,
            Capability.EMBEDDING.value,
            Capability.VISUAL.value,
        ]
        missing: list[str] = []
        for cap in core_caps:
            has_local = any(cap in caps for caps in LOCAL_MODEL_CAPABILITIES.values())
            if not has_local:
                missing.append(f"capability={cap} has no local model")
        if missing:
            raise RuntimeError(
                "Router coverage matrix incomplete — every core capability needs a local fallback. "
                "Missing: " + "; ".join(missing)
            )
        logger.info("Coverage matrix OK: all %d core capabilities have local fallback", len(core_caps))

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
        self,
        capability: str,
        estimated_tokens: int = 0,
        client_id: str | None = None,
        require_tools: bool = False,
        max_tier_override: str | None = None,
        intent: str = "",
    ) -> dict:
        """Router's single routing decision. Caller sends only `capability`
        and `client_id`; everything else — tier, model, retries, cooldowns —
        is figured out here.

        Rules (applied in order):

          capability == embedding / embed    → local only (data locality, cost)
          client tier == NONE                → local only (cloud forbidden)
          capability == visual / vision / vlm
            + VLM GPU blocked                → cloud if any, else queue locally
          capability in {chat, thinking}     → cloud (user-facing: GPU reserved
                                                for background work)
          capability in {coding, extraction} → local if GPU idle AND the prompt
                                                fits the safe 40k budget,
                                                otherwise escalate to cloud

        The 40k budget (vs the raw 48k num_ctx) leaves headroom for tools
        schema + generated output, which is what qwen3-coder-tool:30b needs
        to avoid chat-template truncation → raw `<|im_start|>` leak.
        """
        cap_lower = (capability or "").strip().lower()
        api_base = f"http://jervis-ollama-router:{settings.router_port}"

        # Resolve tier: caller-supplied override wins over CloudModelPolicy.
        # This lets individual callers (e.g. TTS normalize) bypass the
        # client's default tier when they need a specific queue (PAID to
        # skip FREE quota exhaustion). Override is still bounded by the
        # normalize_tier() whitelist, so invalid values fall back gracefully.
        if max_tier_override:
            max_tier = normalize_tier(max_tier_override)
        elif client_id:
            from app.client_tier_cache import resolve_client_tier
            max_tier = normalize_tier(await resolve_client_tier(client_id))
        else:
            max_tier = "NONE"
        tier_level = TIER_LEVELS.get(max_tier, 0)

        local_model = self._find_local_model_for_capability(cap_lower, min_model_size=0)
        local_fallback = {
            "target": "local",
            "model": local_model or settings.orchestrator_model,
            "api_base": api_base,
        }

        async def _try_cloud(reason: str) -> dict | None:
            cloud_model = await find_cloud_model_for_context(
                estimated_tokens, tier_level, None,
                capability=cap_lower, require_tools=require_tools,
            )
            if not cloud_model:
                return None
            queue = "FREE" if cloud_model.endswith(":free") else "PAID"
            slot_ok = await acquire_openrouter_slot(queue, timeout=65.0)
            if not slot_ok:
                logger.warning("Route decision: %s → rate limit on %s queue, falling back to local", reason, queue)
                return None
            logger.info("Route decision: %s → cloud %s (tier=%s, tokens=%d)",
                        reason, cloud_model, max_tier, estimated_tokens)
            api_key = await get_api_key()
            return {"target": "openrouter", "model": cloud_model, "api_key": api_key}

        # 1. Embeddings NEVER go to cloud — data locality + cost.
        if cap_lower in ("embedding", "embed"):
            logger.info("Route decision: embedding → local (%s)", local_fallback["model"])
            return local_fallback

        # 2. Client has no cloud tier → local is the only option.
        if tier_level == 0:
            logger.info("Route decision: tier=NONE → local (cap=%s, model=%s)",
                        cap_lower, local_fallback["model"])
            return local_fallback

        # 3. VLM — local VLM GPU preferred; escalate to cloud when blocked.
        _VLM_CAPS = ("visual", "vision", "vlm")
        if cap_lower in _VLM_CAPS:
            audio_busy = self.check_whisper_busy() or self.check_tts_busy()
            vlm_gpu_blocked = audio_busy or any(
                b.name == VLM_GPU and (
                    not b.healthy
                    or b.active_request_count() > 0
                    or b.loading_in_progress
                )
                for b in self.gpu_pool.all_backends
            )
            if vlm_gpu_blocked:
                cloud = await _try_cloud(
                    "VLM GPU busy (audio)" if audio_busy else "VLM GPU busy / loading"
                )
                if cloud:
                    return cloud
                logger.warning("VLM blocked locally and no cloud VLM fits — queuing on local")
            logger.info("Route decision: visual → local VLM (%s)", local_fallback["model"])
            return local_fallback

        # 4. User-facing capabilities (chat, thinking) → cloud always when the
        #    client allows it. GPU stays free for background / qualifier / KB.
        _USER_FACING = ("chat", "thinking")
        if cap_lower in _USER_FACING:
            cloud = await _try_cloud(f"capability={cap_lower} (user-facing) tier={max_tier}")
            if cloud:
                return cloud
            logger.warning(
                "Route decision: capability=%s wanted cloud but none fits (tokens=%d, tier=%s) — "
                "degrading to local; background work may contend",
                cap_lower, estimated_tokens, max_tier,
            )
            return local_fallback

        # 5. Background capabilities (coding, extraction and the rest) —
        #    local when GPU is idle and the prompt fits the safe 40k budget;
        #    escalate to cloud on busy or oversize.
        _LOCAL_CTX_SAFE_BUDGET = 40_000
        target_model = local_fallback["model"]
        audio_busy = self.check_whisper_busy() or self.check_tts_busy()
        qdepth = self._queue.queue_depth if self._queue else {}
        total_queued = sum(v for v in qdepth.values() if isinstance(v, int))
        gpu_free = (total_queued == 0) and any(
            b.healthy and b.active_request_count() == 0
            and not (b.name == VLM_GPU and audio_busy)
            for b in self.gpu_pool.all_backends
            if target_model in GPU_MODEL_SETS.get(b.name, [])
        )
        if local_model is not None and estimated_tokens <= _LOCAL_CTX_SAFE_BUDGET and gpu_free:
            logger.info("Route decision: capability=%s → local model=%s (tokens=%d, tier=%s)",
                        cap_lower, target_model, estimated_tokens, max_tier)
            return local_fallback

        reason = (
            f"capability={cap_lower} oversize (tokens={estimated_tokens} > {_LOCAL_CTX_SAFE_BUDGET})"
            if estimated_tokens > _LOCAL_CTX_SAFE_BUDGET
            else f"capability={cap_lower} GPU busy"
        )
        cloud = await _try_cloud(reason)
        if cloud:
            return cloud
        logger.info(
            "Route decision: %s → local fallback (model=%s, tokens=%d)",
            reason, target_model, estimated_tokens,
        )
        return local_fallback

    # ── Unified single-entry dispatch (gRPC-only) ──────────────────────

    async def dispatch_inference(
        self,
        api_path: str,
        body: dict,
        *,
        capability: str | None = None,
        client_id: str | None = None,
        intent: str = "",
        priority: Priority = Priority.NORMAL,
        deadline_iso: str | None = None,
        max_tier_override: str | None = None,
    ) -> Union[AsyncIterator[dict], dict]:
        """Single-pass route + dispatch, returning an async iterator for
        streaming (chat / generate) or a parsed dict for unary (embeddings).

        Every value that used to travel as an X-* header now comes in
        typed args — the caller is the gRPC RouterInferenceServicer,
        which extracts them from RequestContext.
        """
        if not capability:
            capability = detect_capability_from_body(api_path, body)
        require_tools = bool(body.get("tools"))

        estimated_tokens = self._estimate_tokens(body)

        # Cascade: if the caller-requested tier (or the client's default
        # tier) hits a quota / credit wall on OpenRouter, step down and
        # try the next tier. Order: current → FREE → NONE (local). Stops
        # the pipeline from collapsing when a single tier is unavailable
        # — matches the user policy "429 → next; until GPU; paid if
        # allowed", with credits-exhausted (402) treated the same as
        # rate-limit (429).
        from app.proxy import ProxyError
        tier_cascade: list[str | None] = []
        current_override = max_tier_override
        # Seed: try the caller's requested tier first (if any), then drop
        # through FREE and finally local. Dedup preserves order.
        seen: set[str | None] = set()
        for step in (current_override, "FREE", "NONE"):
            key = (step or "").upper() or None
            if key in seen:
                continue
            seen.add(key)
            tier_cascade.append(step)

        last_error: Exception | None = None
        for attempt, tier_for_attempt in enumerate(tier_cascade):
            decision = await self.decide_route(
                capability=capability,
                estimated_tokens=estimated_tokens,
                client_id=client_id,
                require_tools=require_tools,
                max_tier_override=tier_for_attempt,
                intent=intent,
            )

            if decision.get("target") == "openrouter":
                request_id = f"inf-{str(uuid.uuid4())[:6]}"
                try:
                    if is_streaming_request(body) and api_path not in EMBEDDING_PATHS:
                        from app.openrouter_proxy import stream_openrouter
                        # Prime the generator so quota errors on the first
                        # upstream chunk are caught here and can trigger a
                        # cascade step, not surface to the caller mid-flow.
                        gen = stream_openrouter(
                            body, decision["model"], decision["api_key"], request_id,
                        )
                        first_chunk = await gen.__anext__()

                        async def _replay(first, rest):
                            yield first
                            async for chunk in rest:
                                yield chunk
                        return _replay(first_chunk, gen)
                    from app.openrouter_proxy import call_openrouter
                    return await call_openrouter(
                        body, decision["model"], decision["api_key"], request_id,
                    )
                except ProxyError as e:
                    # 402 insufficient credits / 429 rate limit → cascade.
                    # Anything else is a real upstream error and must
                    # surface to the caller as-is.
                    if getattr(e, "status_code", None) in (402, 429):
                        last_error = e
                        # Park the model that just hit the wall. When the
                        # upstream sends `X-RateLimit-Reset`, honor it so
                        # the catalog skips the model for the full quota
                        # window instead of probing it back open.
                        try:
                            from app.openrouter_catalog import report_model_error
                            report_model_error(
                                decision["model"],
                                error_message=f"{e.status_code}: {e.message[:200]}",
                                rate_limit_reset_epoch_ms=getattr(
                                    e, "rate_limit_reset_epoch_ms", None
                                ),
                                rate_limit_scope=getattr(
                                    e, "rate_limit_scope", None
                                ),
                            )
                        except Exception:
                            logger.debug("report_model_error failed", exc_info=True)
                        logger.warning(
                            "CLOUD_CASCADE: tier=%s exhausted (status=%s, scope=%s) — "
                            "falling back to next step",
                            tier_for_attempt, e.status_code,
                            getattr(e, "rate_limit_scope", None),
                        )
                        continue
                    raise
                except StopAsyncIteration:
                    # Empty stream — not a quota issue, just return empty.
                    async def _empty():
                        if False:
                            yield
                    return _empty()
            # Local path — substitute model and hand off to the queue.
            # Reached either because the tier we asked for is NONE, or
            # because cascade walked all the way down from cloud.
            local_model = decision.get("model")
            if local_model:
                body["model"] = local_model
            return await self.route_request(
                api_path, body,
                priority=priority, intent=intent, deadline_iso=deadline_iso,
            )

        # All cascade steps (cloud tiers + local) raised quota-style
        # errors — reraise the last one so the caller sees a real fault
        # rather than a silent empty response.
        if last_error is not None:
            raise last_error
        raise RuntimeError("dispatch cascade exhausted without dispatching")

    @staticmethod
    def _estimate_tokens(body: dict) -> int:
        """Rough token estimation from request body."""
        messages = body.get("messages", [])
        if messages:
            total_chars = sum(len(str(m.get("content", ""))) for m in messages)
            return max(total_chars // 4, 100)
        prompt = body.get("prompt", "")
        return max(len(prompt) // 4, 100)

    def _find_local_model_for_capability(
        self, capability: str, min_model_size: int = 0,
    ) -> str | None:
        """Find a local model that has the requested capability and meets the size floor.

        A request with min_model_size=30 excludes 14b models. min_model_size=0
        accepts any model. When multiple candidates pass the filter, prefer the
        one on a free GPU (load balancing).
        """
        candidates = [
            model for model, caps in LOCAL_MODEL_CAPABILITIES.items()
            if capability in caps and LOCAL_MODEL_SIZE.get(model, 0) >= min_model_size
        ]
        if not candidates:
            return None
        if len(candidates) == 1:
            return candidates[0]

        audio_busy = self.check_whisper_busy() or self.check_tts_busy()
        for model in candidates:
            for backend in self.gpu_pool.all_backends:
                if model in GPU_MODEL_SETS.get(backend.name, []):
                    if backend.healthy and backend.active_request_count() == 0:
                        if not (backend.name == VLM_GPU and audio_busy):
                            logger.debug("Model %s selected (GPU %s free, cap=%s, min_size=%d)",
                                         model, backend.name, capability, min_model_size)
                            return model

        # All busy — return first candidate (will queue)
        return candidates[0]

    # ── Main routing entry point ────────────────────────────────────────

    async def route_request(
        self,
        api_path: str,
        body: dict,
        *,
        priority: Priority | None = None,
        intent: str = "",
        deadline_iso: str | None = None,
    ) -> Union[AsyncIterator[dict], dict]:
        """Queue + dispatch an inference request. Returns an async iterator
        for streaming paths, or a dict for unary (embedding) paths.

        Router ALWAYS accepts — requests that cannot run immediately wait
        in the queue. Cancellation is signalled through
        `TrackedRequest.cancel_event` (the gRPC servicer wires this to
        the gRPC context's cancellation).
        """
        model = self._extract_model(body)
        resolved_priority = priority if priority is not None else self._resolve_priority(model, None)
        request_id = str(uuid.uuid4())[:8]

        request = TrackedRequest(
            request_id=request_id,
            model=model,
            priority=resolved_priority,
            api_path=api_path,
            body=body,
            deadline_iso=deadline_iso,
            intent=intent or "",
        )

        self._last_any_gpu_activity = time.monotonic()
        self._idle_notified = False

        num_ctx = body.get("options", {}).get("num_ctx") if isinstance(body.get("options"), dict) else None
        qdepth = self._queue.queue_depth if self._queue else {}
        logger.info(
            "REQUEST_IN: id=%s model=%s priority=%s path=%s num_ctx=%s queue=%s intent=%s",
            request_id, model, resolved_priority.name, api_path, num_ctx, qdepth, intent,
        )

        start_time = time.monotonic()
        try:
            result = await self._queue.submit(request)
        except Exception as e:
            duration = time.monotonic() - start_time
            logger.error(
                "REQUEST_ERROR: id=%s duration=%.2fs error=%s",
                request_id, duration, str(e),
            )
            raise
        duration = time.monotonic() - start_time
        if isinstance(result, dict):
            logger.info("REQUEST_OUT: id=%s duration=%.2fs (unary)", request_id, duration)
        else:
            logger.info(
                "REQUEST_OUT: id=%s routing=%.2fs (streaming — see PROXY_STREAM for total)",
                request_id, duration,
            )
        return result

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

    async def _active_requests_logger(self) -> None:
        """Periodically log active request counts for observability.

        Runs every 30s. Only logs when requests are active. Replaces the
        old timeout watchdog — router does NOT force-cancel long requests.
        Requests complete when backend returns, or are cancelled via
        cancel_event when the client disconnects (handled in proxy layer).
        Rationale: guidelines say "NEVER hard timeouts — stream + heartbeat".
        """
        logger.info("Active requests logger started (interval 30s, NO timeouts)")
        while True:
            try:
                await asyncio.sleep(30)
                total_active = 0
                for backend in self.gpu_pool.all_backends:
                    total_active += backend.active_request_count()
                if total_active > 0:
                    qdepth = self._queue.queue_depth if self._queue else {}
                    logger.info("ACTIVE_REQUESTS: gpu=%d queue=%s", total_active, qdepth)
            except asyncio.CancelledError:
                return
            except Exception as e:
                logger.error("Active requests logger error: %s", e)

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
                    from app.grpc_server_client import build_request_context, server_gpu_idle_stub
                    from jervis.server import gpu_idle_pb2

                    resp = await server_gpu_idle_stub().GpuIdle(
                        gpu_idle_pb2.GpuIdleRequest(
                            ctx=build_request_context(),
                            idle_seconds=int(idle_s),
                        ),
                        timeout=10.0,
                    )
                    if resp.ok:
                        logger.info("GPU_IDLE: Kotlin server notified successfully")
                    else:
                        logger.warning("GPU_IDLE: Kotlin server returned error: %s", resp.error)
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

    async def notify_whisper_wants_gpu(self, preempt_timeout_s: int = 30) -> tuple[bool, int, int]:
        """Whisper demands GPU. Active preemption — not passive wait.

        For every in-flight Ollama LLM/VLM request (embeddings are kept,
        they're tiny), the router:
          1. Sets `cancel_event` + marks PREEMPTED so proxy stream ends fast.
          2. Unloads the model from VRAM — Whisper needs real headroom.
          3. Re-enqueues the preempted request so it resumes automatically
             after `WhisperDone`. The original caller's future keeps waiting.

        XTTS is a separate GPU process (different systemd unit, permanent
        resident) and is never touched.

        Returns `(granted, preempted_count, unloaded_models)`. `granted=False`
        means we timed out before GPU went quiet.
        """
        from .models import EMBEDDING_MODELS, RequestState

        self._whisper_active = True
        self._whisper_active_since = time.monotonic()
        self._whisper_done_event.clear()

        preempted: list[TrackedRequest] = []
        for gpu in self.gpu_pool.healthy_backends:
            for req_id, req in list(gpu.active_requests.items()):
                if req.model in EMBEDDING_MODELS:
                    continue
                if req.state == RequestState.PREEMPTED:
                    continue
                logger.warning(
                    "WHISPER_PREEMPT: cancel id=%s model=%s gpu=%s",
                    req_id, req.model, gpu.name,
                )
                req.cancel_event.set()
                req.state = RequestState.PREEMPTED_BY_WHISPER
                preempted.append(req)

        # The caller-side gRPC layer detects state=PREEMPTED_BY_WHISPER and
        # retries the whole dispatch_inference once the whisper semaphore
        # clears — see grpc_server.py:_dispatch_stream. Keeps the re-dispatch
        # logic out of the priority queue.

        # Unload the models so the VRAM is actually free when whisper loads.
        # Done in parallel per GPU to keep the notify fast.
        unloaded_models = 0
        if self._mgmt_client:
            unload_tasks: list[asyncio.Task] = []
            for gpu in self.gpu_pool.healthy_backends:
                for model_id in list(gpu.loaded_models.keys()):
                    if model_id in EMBEDDING_MODELS:
                        continue
                    unload_tasks.append(asyncio.create_task(
                        self.gpu_pool.unload_model(gpu, model_id, self._mgmt_client)
                    ))
            if unload_tasks:
                # Bounded wait — we must answer whisper promptly.
                done, pending = await asyncio.wait(
                    unload_tasks, timeout=max(5, preempt_timeout_s - 2),
                )
                for t in pending:
                    t.cancel()
                for t in done:
                    try:
                        if t.result():
                            unloaded_models += 1
                    except Exception:
                        pass

        # Wait for any embedding/untracked request to finish so we don't
        # compete for VRAM while whisper loads. This is bounded.
        deadline = time.monotonic() + preempt_timeout_s
        while time.monotonic() < deadline:
            still_busy = any(
                r.model not in EMBEDDING_MODELS
                for gpu in self.gpu_pool.healthy_backends
                for r in gpu.active_requests.values()
            )
            if not still_busy:
                break
            await asyncio.sleep(0.2)

        granted = time.monotonic() < deadline or not any(
            r.model not in EMBEDDING_MODELS
            for gpu in self.gpu_pool.healthy_backends
            for r in gpu.active_requests.values()
        )
        logger.info(
            "WHISPER_NOTIFY: granted=%s preempted=%d unloaded=%d",
            granted, len(preempted), unloaded_models,
        )
        return granted, len(preempted), unloaded_models

    def notify_whisper_done(self) -> None:
        """Whisper finished. Dispatcher wakes up, queued Ollama work resumes."""
        self._whisper_active = False
        self._whisper_done_event.set()
        logger.info("WHISPER_DONE: flag cleared, dispatcher resumed")
        # Kick every group dispatcher — preempted requests are back in
        # the queue waiting to run.
        if self._queue:
            self._queue.notify_slot_freed(None)

    # ── TTS GPU coordination ────────────────────────────────────────────
    # Mirror of the whisper flow: XTTS wants exclusive GPU, we preempt
    # Ollama + unload its models. Both flags (whisper, tts) guard the
    # dispatcher so they compose — either one being active keeps Ollama
    # blocked.

    def check_tts_busy(self) -> bool:
        """Check if XTTS is actively synthesizing (based on notify/done)."""
        if not self._tts_active:
            return False
        # Stale safety: auto-reset after 2h (crash resilience).
        if time.monotonic() - self._tts_active_since > 7200:
            logger.warning("TTS_STALE: active for >2h, auto-resetting")
            self._tts_active = False
            self._tts_done_event.set()
            return False
        return True

    async def wait_for_tts_done(self, timeout: float = 3600) -> bool:
        if not self.check_tts_busy():
            return True
        logger.info("TTS_WAIT: waiting for tts-done event (timeout=%ds)", timeout)
        try:
            await asyncio.wait_for(self._tts_done_event.wait(), timeout=timeout)
            logger.info("TTS_WAIT: tts done, GPU available")
            return True
        except asyncio.TimeoutError:
            logger.warning("TTS_WAIT: timeout after %ds", timeout)
            return False

    async def notify_tts_wants_gpu(self, preempt_timeout_s: int = 30) -> tuple[bool, int, int]:
        """XTTS demands GPU. Same preempt flow as whisper, with one
        difference: XTTS itself calls `RouterInferenceService.Chat`
        (intent=`tts_normalize`) for per-sentence text rewriting while
        synthesis is running. We MUST keep those requests — and the
        chat model they loaded — alive so the XTTS sentence pump doesn't
        stall. Everything else (qualifier, kb-extract, VLM work) gets
        preempted + unloaded as usual.
        """
        from .models import RequestState

        self._tts_active = True
        self._tts_active_since = time.monotonic()
        self._tts_done_event.clear()

        # Preempt only the VLM GPU (p40-2) — that's where XTTS lives.
        # Normalize LLM (qwen3:14b) lives on the SAME card by design:
        # XTTS owns the GPU during synthesis, normalize runs in-process
        # on the same GPU so there's no inter-pod round-trip and no
        # cloud dependency. So two carve-outs compared to whisper:
        #   * tts_normalize requests are NOT cancelled
        #   * the normalize model is NOT unloaded from VRAM
        TTS_NORMALIZE_MODEL = "qwen3:14b"
        preempted: list[TrackedRequest] = []
        for gpu in self.gpu_pool.healthy_backends:
            if gpu.name != VLM_GPU:
                continue
            for req_id, req in list(gpu.active_requests.items()):
                if getattr(req, "intent", None) == "tts_normalize":
                    continue  # our own normalize path
                if req.state == RequestState.PREEMPTED:
                    continue
                logger.warning(
                    "TTS_PREEMPT: cancel id=%s model=%s gpu=%s",
                    req_id, req.model, gpu.name,
                )
                req.cancel_event.set()
                req.state = RequestState.PREEMPTED_BY_WHISPER  # reuses whisper retry path
                preempted.append(req)

        unloaded_models = 0
        if self._mgmt_client:
            unload_tasks: list[asyncio.Task] = []
            for gpu in self.gpu_pool.healthy_backends:
                if gpu.name != VLM_GPU:
                    continue
                for model_id in list(gpu.loaded_models.keys()):
                    if model_id == TTS_NORMALIZE_MODEL:
                        continue  # keep normalize model resident
                    unload_tasks.append(asyncio.create_task(
                        self.gpu_pool.unload_model(gpu, model_id, self._mgmt_client)
                    ))
            if unload_tasks:
                done, pending = await asyncio.wait(
                    unload_tasks, timeout=max(5, preempt_timeout_s - 2),
                )
                for t in pending:
                    t.cancel()
                for t in done:
                    try:
                        if t.result():
                            unloaded_models += 1
                    except Exception:
                        pass

        def _still_busy() -> bool:
            # Normalize requests share the card on purpose — they're
            # not contention from our point of view.
            for gpu in self.gpu_pool.healthy_backends:
                if gpu.name != VLM_GPU:
                    continue
                for req in gpu.active_requests.values():
                    if getattr(req, "intent", None) == "tts_normalize":
                        continue
                    return True
            return False

        deadline = time.monotonic() + preempt_timeout_s
        while time.monotonic() < deadline:
            if not _still_busy():
                break
            await asyncio.sleep(0.2)

        granted = not _still_busy()
        logger.info(
            "TTS_NOTIFY: granted=%s preempted=%d unloaded=%d",
            granted, len(preempted), unloaded_models,
        )
        return granted, len(preempted), unloaded_models

    def notify_tts_done(self) -> None:
        """XTTS finished synthesis. Dispatcher wakes up and queued Ollama
        work resumes — unless whisper is still active, in which case the
        dispatcher gate stays closed via check_whisper_busy."""
        self._tts_active = False
        self._tts_done_event.set()
        logger.info("TTS_DONE: flag cleared, dispatcher resumed")
        if self._queue:
            self._queue.notify_slot_freed(None)

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
