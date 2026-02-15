"""OllamaRouter – multi-GPU routing logic, preemption, announce/release.

Supports multiple GPU backends with per-GPU reservations:
- CRITICAL requests spread across GPUs (2 CRITICALs → 2 GPUs)
- BACKGROUND requests use unreserved GPUs (not blanket-CPU)
- Reservations track per-GPU with independent idle/absolute timeouts
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid

import httpx
from starlette.responses import Response, JSONResponse

from .config import settings
from .gpu_state import GpuBackend, GpuPool
from .models import (
    EMBEDDING_MODELS,
    EMBEDDING_PATHS,
    MODEL_TO_PRIORITY,
    MODEL_TO_SET,
    AnnounceRequest,
    AnnounceResponse,
    Priority,
    ReleaseRequest,
    ReleaseResponse,
    RequestState,
    TrackedRequest,
)
from .proxy import is_streaming_request, proxy_non_streaming, proxy_streaming

logger = logging.getLogger("ollama-router.core")


class OllamaRouter:
    def __init__(self, gpu_pool: GpuPool) -> None:
        self.gpu_pool = gpu_pool
        self.cpu_url = settings.cpu_backend_url.rstrip("/")
        self.cpu_healthy = True

        # Per-GPU reservation state (supports multiple concurrent reservations)
        self._reservations: dict[str, str] = {}            # gpu_name → session_id
        self._reservation_times: dict[str, float] = {}     # gpu_name → reserved_at
        self._last_critical_activity: dict[str, float] = {}  # gpu_name → last_activity
        self._reservation_lock = asyncio.Lock()

        # Background model loading tasks (per GPU)
        self._bg_load_tasks: dict[str, asyncio.Task] = {}  # gpu_name → task

        # HTTP client for internal management calls (load/unload/health)
        self._mgmt_client: httpx.AsyncClient | None = None

        # Watchdog task
        self._watchdog_task: asyncio.Task | None = None

    async def startup(self) -> None:
        """Initialize router state on startup."""
        self._mgmt_client = httpx.AsyncClient(timeout=httpx.Timeout(30.0))
        # Sync GPU state from all backends (initial check only, fail fast after)
        await self.gpu_pool.sync_state(self._mgmt_client)
        await self._check_cpu_health()
        # Start background tasks
        self._watchdog_task = asyncio.create_task(self._reservation_watchdog())
        logger.info("Router started with %d GPU backend(s)", len(self.gpu_pool.all_backends))

    async def shutdown(self) -> None:
        """Cleanup on shutdown."""
        if self._watchdog_task:
            self._watchdog_task.cancel()
        for task in self._bg_load_tasks.values():
            if not task.done():
                task.cancel()
        if self._mgmt_client:
            await self._mgmt_client.aclose()

    # ── Main routing entry point ────────────────────────────────────────

    async def route_request(
        self,
        api_path: str,
        body: dict,
        priority_header: int | None = None,
    ) -> Response:
        """Route an Ollama API request to the best backend."""
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

        # ── Entry logging ──
        logger.info(
            "REQUEST_IN: id=%s model=%s priority=%s path=%s",
            request_id, model, priority.name, api_path,
        )

        start_time = time.monotonic()
        try:
            response = await self._do_route(request)
            duration = time.monotonic() - start_time
            # ── Exit logging ──
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
            # Cleanup active request tracking
            for backend in self.gpu_pool.all_backends:
                backend.active_requests.pop(request_id, None)

    async def _do_route(self, request: TrackedRequest) -> Response:
        model = request.model
        priority = request.priority

        # ── 1. BACKGROUND with reservations → use unreserved GPU or CPU ──
        if priority >= Priority.BACKGROUND and self._reservations:
            # If ALL GPUs are reserved, go to CPU immediately
            if not self.gpu_pool.find_unreserved():
                return await self._send_to_cpu(request)
            # Otherwise fall through — steps below use reservation-aware methods

        # ── 1.5. BACKGROUND embedding yields GPU to ORCHESTRATOR_EMBEDDING ──
        if priority >= Priority.BACKGROUND and model in EMBEDDING_MODELS:
            for b in self.gpu_pool.healthy_backends:
                if not b.reserved_by and b.has_active_orchestrator_embedding():
                    logger.info("BACKGROUND embedding → CPU (ORCHESTRATOR_EMBEDDING active on GPU %s)", b.name)
                    return await self._send_to_cpu(request)

        # ── 2. Find GPU that already has this model loaded ──────────────
        gpu = self.gpu_pool.find_with_model(model)
        if gpu:
            if priority <= Priority.CRITICAL:
                # CRITICAL: prefer spreading across GPUs
                # If this GPU already has active CRITICAL, try another in step 3
                if not gpu.has_active_critical():
                    # Ensure reservation exists (e.g. after router restart, model still loaded)
                    await self._ensure_critical_reservation(gpu)
                    return await self._send_to_gpu(gpu, request)
                # Fall through to step 3 for load-balancing
            elif not gpu.reserved_by:
                # Non-CRITICAL: use only unreserved GPUs
                return await self._send_to_gpu(gpu, request)

        # ── 3. CRITICAL: auto-reserve GPU, ensure model loaded ──────────
        if priority <= Priority.CRITICAL:
            return await self._route_critical(request)

        # ── 3.5. ORCHESTRATOR_EMBEDDING: prefer unreserved GPU, fallback co-locate ──
        if priority == Priority.ORCHESTRATOR_EMBEDDING:
            # 1. Prefer unreserved GPU with embedding model (2-GPU: uses GPU2)
            gpu = self.gpu_pool.find_unreserved_with_model(model)
            if gpu:
                logger.info("ORCHESTRATOR_EMBEDDING: Using unreserved GPU %s", gpu.name)
                return await self._send_to_gpu(gpu, request)

            # 2. Try any unreserved GPU (load model there)
            gpu = self.gpu_pool.find_unreserved_with_free_vram(model)
            if gpu:
                loaded = await self.gpu_pool.load_model(gpu, model, self._mgmt_client)
                if loaded:
                    logger.info("ORCHESTRATOR_EMBEDDING: Loaded %s on unreserved GPU %s", model, gpu.name)
                    return await self._send_to_gpu(gpu, request)

            # 3. Co-locate with CRITICAL on reserved GPU (1-GPU fallback)
            for gpu_name in self._reservations:
                gpu = self.gpu_pool.backends.get(gpu_name)
                if not gpu:
                    continue
                logger.info("ORCHESTRATOR_EMBEDDING: Co-locating on CRITICAL GPU %s (1-GPU fallback)", gpu.name)
                if not gpu.has_model(model):
                    loaded = await self.gpu_pool.load_model(gpu, model, self._mgmt_client)
                    if not loaded:
                        logger.warning("ORCHESTRATOR_EMBEDDING: Failed to load %s on GPU %s", model, gpu.name)
                        continue
                    logger.info("ORCHESTRATOR_EMBEDDING: Loaded %s on GPU %s", model, gpu.name)
                return await self._send_to_gpu(gpu, request)

            # 4. CPU fallback
            logger.info("ORCHESTRATOR_EMBEDDING: No GPU available, using CPU")
            return await self._send_to_cpu(request)

        # ── 4. Find unreserved GPU with model or free VRAM ──────────────
        gpu = self.gpu_pool.find_unreserved_with_model(model)
        if gpu:
            return await self._send_to_gpu(gpu, request)

        gpu = self.gpu_pool.find_unreserved_with_free_vram(model)
        if gpu:
            loaded = await self.gpu_pool.load_model(gpu, model, self._mgmt_client)
            if loaded:
                return await self._send_to_gpu(gpu, request)

        # ── 5. BACKGROUND: idle unreserved GPU or CPU ───────────────────
        if priority >= Priority.BACKGROUND:
            gpu = self.gpu_pool.find_unreserved_least_busy()
            if gpu and gpu.active_request_count() == 0:
                # GPU is IDLE - unload old models and load requested model
                await self.gpu_pool.unload_all(gpu, self._mgmt_client)
                loaded = await self.gpu_pool.load_model(gpu, model, self._mgmt_client)
                if loaded:
                    return await self._send_to_gpu(gpu, request)
            # No idle unreserved GPU → CPU
            return await self._send_to_cpu(request)

        # ── 6. Last resort – CPU ────────────────────────────────────────
        return await self._send_to_cpu(request)

    # ── CRITICAL routing (step 3) ────────────────────────────────────────

    async def _route_critical(self, request: TrackedRequest) -> Response:
        """Route a CRITICAL request: auto-reserve GPU, spread across GPUs."""
        model = request.model

        if ":30b" not in model:
            logger.error("CRITICAL priority requires :30b model, got: %s", model)
            return JSONResponse(
                status_code=503,
                content={"error": "invalid_critical_model", "message": f"CRITICAL requires :30b model, got {model}"},
            )

        target_gpu = None
        async with self._reservation_lock:
            target_gpu = self._find_best_gpu_for_critical(model)

            if not target_gpu:
                logger.error("CRITICAL: No healthy GPU available - REFUSING to run on CPU")
                return JSONResponse(
                    status_code=503,
                    content={"error": "no_gpu_available", "message": "CRITICAL requests require GPU, no GPU available"},
                )

            # Auto-reserve if not already reserved
            if target_gpu.name not in self._reservations:
                logger.info(
                    "Auto-reserving GPU %s for CRITICAL request %s (model=%s)",
                    target_gpu.name, request.request_id, model,
                )

                # Cancel background load on this GPU
                bg_task = self._bg_load_tasks.get(target_gpu.name)
                if bg_task and not bg_task.done():
                    bg_task.cancel()

                self._reservations[target_gpu.name] = "critical"
                self._reservation_times[target_gpu.name] = time.monotonic()
                self._last_critical_activity[target_gpu.name] = time.monotonic()
                target_gpu.reserved_by = "critical"
                target_gpu.reserved_at = time.monotonic()

                # Preempt all non-CRITICAL work
                if target_gpu.active_request_count() > 0:
                    logger.warning(
                        "GPU %s has %d active request(s), preempting for CRITICAL",
                        target_gpu.name, target_gpu.active_request_count(),
                    )
                    await self._preempt_all(target_gpu)

                # Unload ALL models (orchestrator :30b fills entire GPU)
                await self.gpu_pool.unload_all(target_gpu, self._mgmt_client)
            else:
                # GPU already reserved — update activity timestamp
                self._last_critical_activity[target_gpu.name] = time.monotonic()

        # Load :30b if not present
        if not target_gpu.has_model(model):
            loaded = await self.gpu_pool.load_model(target_gpu, model, self._mgmt_client)
            if not loaded:
                # Aggressive retry: unload from ALL GPUs
                logger.error(
                    "CRITICAL: Failed to load %s on GPU %s, retrying with aggressive cleanup",
                    model, target_gpu.name,
                )
                for backend in self.gpu_pool.all_backends:
                    if backend.name != target_gpu.name:
                        await self.gpu_pool.unload_all(backend, self._mgmt_client)
                loaded = await self.gpu_pool.load_model(target_gpu, model, self._mgmt_client)
                if not loaded:
                    logger.error("CRITICAL: Failed to load %s even after aggressive cleanup - REFUSING CPU fallback", model)
                    return JSONResponse(
                        status_code=503,
                        content={"error": "model_load_failed", "message": f"Failed to load {model} on GPU, CRITICAL cannot run on CPU"},
                    )
        elif len(target_gpu.loaded_models) > 1:
            # :30b is loaded but other models too - unload extras
            logger.warning("GPU %s has :30b + other models, unloading extras", target_gpu.name)
            await self.gpu_pool.unload_all(target_gpu, self._mgmt_client, except_models={model})

        self._last_critical_activity[target_gpu.name] = time.monotonic()
        return await self._send_to_gpu(target_gpu, request)

    async def _ensure_critical_reservation(self, gpu: GpuBackend) -> None:
        """Ensure GPU has a CRITICAL reservation (creates one if missing).

        Needed when router restarts but model is still loaded on GPU — step 2
        routes CRITICAL there directly, but the reservation dict is empty.
        Without this, ORCHESTRATOR_EMBEDDING won't find a reservation to co-locate with.
        """
        if gpu.name in self._reservations:
            self._last_critical_activity[gpu.name] = time.monotonic()
            return
        async with self._reservation_lock:
            if gpu.name in self._reservations:  # Double-check after lock
                self._last_critical_activity[gpu.name] = time.monotonic()
                return
            self._reservations[gpu.name] = "critical"
            self._reservation_times[gpu.name] = time.monotonic()
            self._last_critical_activity[gpu.name] = time.monotonic()
            gpu.reserved_by = "critical"
            gpu.reserved_at = time.monotonic()
            logger.info("Auto-created reservation for GPU %s (CRITICAL fast-path)", gpu.name)

    def _find_best_gpu_for_critical(self, model: str) -> GpuBackend | None:
        """Find best GPU for CRITICAL request, preferring to spread across GPUs.

        Priority order:
        1. GPU with model loaded and NO active CRITICAL (ideal: zero wait)
        2. Unreserved GPU (will load model, but no contention)
        3. GPU with model loaded, least busy (serialize behind other CRITICAL)
        4. Any healthy GPU, least busy
        """
        # 1. GPU with model loaded and NO active CRITICAL
        for b in self.gpu_pool.healthy_backends:
            if b.has_model(model) and not b.has_active_critical():
                return b

        # 2. Unreserved GPU (will need to load model)
        for b in self.gpu_pool.healthy_backends:
            if b.name not in self._reservations:
                return b

        # 3. GPU with model loaded, least busy (serialize)
        candidates = [b for b in self.gpu_pool.healthy_backends if b.has_model(model)]
        if candidates:
            return min(candidates, key=lambda b: b.active_request_count())

        # 4. Any healthy GPU
        if self.gpu_pool.healthy_backends:
            return min(self.gpu_pool.healthy_backends, key=lambda b: b.active_request_count())

        return None

    # ── Send to backend ─────────────────────────────────────────────────

    async def _send_to_gpu(self, gpu: GpuBackend, request: TrackedRequest) -> Response:
        request.state = RequestState.RUNNING_GPU
        request.target_gpu = gpu.name
        gpu.active_requests[request.request_id] = request
        gpu.last_activity = time.monotonic()

        target_url = gpu.url
        # ── Proxy logging ──
        logger.info(
            "REQUEST_PROXY: id=%s → GPU %s (%s) model=%s priority=%s",
            request.request_id, gpu.name, target_url, request.model, request.priority.name,
        )

        if request.api_path in EMBEDDING_PATHS or not is_streaming_request(request.body):
            return await proxy_non_streaming(target_url, request)
        return await proxy_streaming(target_url, request)

    async def _send_to_cpu(self, request: TrackedRequest) -> Response:
        request.state = RequestState.RUNNING_CPU

        if not self.cpu_healthy:
            logger.error(
                "REQUEST_PROXY: id=%s → CPU unavailable (no healthy backend)",
                request.request_id,
            )
            return JSONResponse(
                status_code=503,
                content={"error": "no_backend_available", "message": "Both GPU and CPU backends are unavailable"},
            )

        # ── Proxy logging ──
        logger.info(
            "REQUEST_PROXY: id=%s → CPU (%s) model=%s priority=%s",
            request.request_id, self.cpu_url, request.model, request.priority.name,
        )

        if request.api_path in EMBEDDING_PATHS or not is_streaming_request(request.body):
            return await proxy_non_streaming(self.cpu_url, request)
        return await proxy_streaming(self.cpu_url, request)

    # ── Preemption ──────────────────────────────────────────────────────

    async def _preempt_background(self, gpu: GpuBackend) -> None:
        """Preempt all background requests running on a GPU backend."""
        preempted = []
        for req_id, req in list(gpu.active_requests.items()):
            if req.priority >= Priority.BACKGROUND:
                # Don't preempt short embedding requests
                if req.model in EMBEDDING_MODELS and not settings.preempt_embeddings:
                    continue
                req.cancel_event.set()
                req.state = RequestState.PREEMPTED
                preempted.append((req_id, req.priority.name))

        if preempted:
            logger.info(
                "Preempted %d background request(s) on GPU %s: %s",
                len(preempted), gpu.name, preempted,
            )
            # Give a brief grace period for streams to close
            await asyncio.sleep(settings.preempt_grace_s)

    async def _preempt_all(self, gpu: GpuBackend) -> None:
        """Preempt ALL non-CRITICAL requests running on a GPU backend."""
        preempted = []
        for req_id, req in list(gpu.active_requests.items()):
            # Never preempt CRITICAL requests (they take precedence)
            if req.priority <= Priority.CRITICAL:
                continue
            # Don't preempt short embedding requests
            if req.model in EMBEDDING_MODELS and not settings.preempt_embeddings:
                continue
            req.cancel_event.set()
            req.state = RequestState.PREEMPTED
            preempted.append((req_id, req.model, req.priority.name))
            logger.warning(
                "PREEMPT: id=%s model=%s priority=%s → killed by CRITICAL request",
                req_id, req.model, req.priority.name,
            )

        if preempted:
            logger.warning(
                "Preempted %d request(s) on GPU %s for CRITICAL work: %s",
                len(preempted), gpu.name, preempted,
            )
            # Give a brief grace period for streams to close
            await asyncio.sleep(settings.preempt_grace_s)

    # ── Orchestrator announce/release ───────────────────────────────────

    async def announce(self, req: AnnounceRequest) -> AnnounceResponse:
        """Reserve a GPU for critical work. Supports multiple concurrent reservations."""
        async with self._reservation_lock:
            # Find best GPU: prefer unreserved, exclude already-reserved
            gpu = self.gpu_pool.find_for_reservation(exclude_gpus=set(self._reservations.keys()))
            if not gpu:
                return AnnounceResponse(
                    status="degraded",
                    model_loaded=False,
                    gpu_available=False,
                )

            # Cancel background model loading on this GPU
            bg_task = self._bg_load_tasks.get(gpu.name)
            if bg_task and not bg_task.done():
                bg_task.cancel()

            # Reserve this GPU
            self._reservations[gpu.name] = req.session_id
            self._reservation_times[gpu.name] = time.monotonic()
            self._last_critical_activity[gpu.name] = time.monotonic()
            gpu.reserved_by = req.session_id
            gpu.reserved_at = time.monotonic()

            logger.info(
                "GPU %s reserved for CRITICAL work (session=%s, model=%s, total_reservations=%d)",
                gpu.name, req.session_id, req.model, len(self._reservations),
            )

            # Immediately preempt non-CRITICAL active requests
            if gpu.active_request_count() > 0:
                logger.warning(
                    "GPU %s has %d active request(s), preempting for reservation",
                    gpu.name, gpu.active_request_count(),
                )
                await self._preempt_all(gpu)

            # CRITICAL: Orchestrator reservation = ONLY :30b, nothing else!
            if ":30b" not in req.model:
                logger.error("Orchestrator reservation requires :30b model, got: %s", req.model)
                return AnnounceResponse(
                    status="error",
                    model_loaded=False,
                    gpu_available=True,
                )

            # Unload ALL models (orchestrator :30b fills entire GPU)
            await self.gpu_pool.unload_all(gpu, self._mgmt_client)

            # Load orchestrator model
            model_loaded = await self.gpu_pool.load_model(
                gpu, req.model, self._mgmt_client,
                keep_alive=settings.default_keep_alive,
            )

            # If load failed, retry with aggressive cleanup from ALL GPUs
            if not model_loaded:
                logger.error("CRITICAL: Failed to load %s on GPU %s, retrying with aggressive cleanup", req.model, gpu.name)
                for backend in self.gpu_pool.all_backends:
                    if backend.name != gpu.name:
                        await self.gpu_pool.unload_all(backend, self._mgmt_client)
                model_loaded = await self.gpu_pool.load_model(
                    gpu, req.model, self._mgmt_client,
                    keep_alive=settings.default_keep_alive,
                )
                if not model_loaded:
                    logger.error("CRITICAL: Failed to load %s even after aggressive cleanup", req.model)

            return AnnounceResponse(
                status="ready" if model_loaded else "error",
                model_loaded=model_loaded,
                gpu_available=True,
                gpu_name=gpu.name,
            )

    async def release(self, req: ReleaseRequest) -> ReleaseResponse:
        """Release GPU reservation. Finds GPU by session_id, or releases all if not found."""
        async with self._reservation_lock:
            if not self._reservations:
                logger.info("Release called but no reservations active")
                return ReleaseResponse(status="released", background_loading=False)

            # Find GPU reserved by this session_id
            gpu_to_release = None
            for gpu_name, session_id in self._reservations.items():
                if session_id == req.session_id:
                    gpu_to_release = gpu_name
                    break

            if gpu_to_release:
                # Release specific GPU
                self._clear_reservation(gpu_to_release)
                self._schedule_bg_load(gpu_to_release)
                logger.info("GPU %s reservation released (session=%s)", gpu_to_release, req.session_id)
                return ReleaseResponse(status="released", background_loading=True)

            # Session not found — release all "critical" auto-reservations
            released = []
            for gpu_name, session_id in list(self._reservations.items()):
                if session_id == "critical":
                    released.append(gpu_name)
                    self._clear_reservation(gpu_name)
                    self._schedule_bg_load(gpu_name)

            if released:
                logger.info("Released %d auto-reservation(s): %s", len(released), released)
                return ReleaseResponse(status="released", background_loading=True)

            logger.info("Release called but no matching reservation for session=%s", req.session_id)
            return ReleaseResponse(status="released", background_loading=False)

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
        """After a delay, load background model set onto GPU."""
        try:
            await asyncio.sleep(settings.background_load_delay_s)

            # Double-check GPU didn't get reserved during the delay
            if gpu.name in self._reservations:
                return

            # CRITICAL: If :30b model still loaded, don't load background set (won't fit)
            if any(":30b" in m for m in gpu.loaded_models):
                logger.warning("GPU %s has :30b model, skipping background load (won't fit)", gpu.name)
                return

            logger.info("Loading background model set on GPU %s", gpu.name)
            await self.gpu_pool.unload_all(gpu, self._mgmt_client)
            await self.gpu_pool.load_model_set(gpu, "background", self._mgmt_client)
        except asyncio.CancelledError:
            logger.info("Background model loading cancelled for GPU %s", gpu.name)
        except Exception as e:
            logger.error("Background model loading failed for GPU %s: %s", gpu.name, e)

    # ── Reservation watchdog ────────────────────────────────────────────

    async def _reservation_watchdog(self) -> None:
        """Auto-release GPU reservations on timeout (per-GPU)."""
        while True:
            try:
                await asyncio.sleep(30)
                async with self._reservation_lock:
                    if not self._reservations:
                        continue

                    now = time.monotonic()

                    for gpu_name in list(self._reservations.keys()):
                        reserved_at = self._reservation_times.get(gpu_name)
                        last_activity = self._last_critical_activity.get(gpu_name)

                        # Absolute timeout
                        if reserved_at and (now - reserved_at) > settings.orchestrator_reservation_timeout_s:
                            logger.warning(
                                "GPU %s reservation exceeded %ds absolute timeout, auto-releasing",
                                gpu_name, settings.orchestrator_reservation_timeout_s,
                            )
                            self._clear_reservation(gpu_name)
                            self._schedule_bg_load(gpu_name)
                            continue

                        # Idle timeout
                        if last_activity and (now - last_activity) > settings.orchestrator_idle_timeout_s:
                            logger.warning(
                                "GPU %s reservation idle for %ds, auto-releasing",
                                gpu_name, settings.orchestrator_idle_timeout_s,
                            )
                            self._clear_reservation(gpu_name)
                            self._schedule_bg_load(gpu_name)

            except asyncio.CancelledError:
                return
            except Exception as e:
                logger.error("Watchdog error: %s", e)

    async def _check_cpu_health(self) -> None:
        try:
            resp = await self._mgmt_client.head(f"{self.cpu_url}/", timeout=5.0)
            was_healthy = self.cpu_healthy
            self.cpu_healthy = resp.status_code == 200
            if not was_healthy and self.cpu_healthy:
                logger.info("CPU backend recovered")
        except Exception:
            if self.cpu_healthy:
                logger.warning("CPU backend is unhealthy")
            self.cpu_healthy = False

    # ── Helpers ─────────────────────────────────────────────────────────

    def _extract_model(self, body: dict) -> str:
        return body.get("model", "unknown")

    def _resolve_priority(self, model: str, priority_header: int | None) -> Priority:
        if priority_header is not None:
            try:
                return Priority(priority_header)
            except ValueError:
                pass
        return MODEL_TO_PRIORITY.get(model, Priority.BACKGROUND)

    def _is_reserved_by_other(self, gpu: GpuBackend) -> bool:
        """Check if GPU is reserved."""
        return gpu.reserved_by is not None

    @property
    def is_reserved(self) -> bool:
        return bool(self._reservations)
