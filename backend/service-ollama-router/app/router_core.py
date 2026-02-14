"""OllamaRouter – multi-GPU routing logic, preemption, announce/release."""

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

        # Orchestrator reservation state
        self._reservation_session: str | None = None
        self._reservation_gpu: str | None = None
        self._reservation_at: float | None = None
        self._last_critical_activity: float | None = None
        self._reservation_lock = asyncio.Lock()

        # Background model loading task
        self._bg_load_task: asyncio.Task | None = None

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
        if self._bg_load_task and not self._bg_load_task.done():
            self._bg_load_task.cancel()
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

        # ── 1. If GPU is reserved → BACKGROUND to CPU (MUST BE FIRST!) ──
        if priority >= Priority.BACKGROUND and self._reservation_session:
            return await self._send_to_cpu(request)

        # ── 2. Find GPU that already has this model loaded ──────────────
        gpu = self.gpu_pool.find_with_model(model)
        if gpu:
            if priority <= Priority.CRITICAL or not self._is_reserved_by_other(gpu):
                return await self._send_to_gpu(gpu, request)

        # ── 3. P0 CRITICAL: auto-reserve GPU, ensure model loaded ──────
        if priority <= Priority.CRITICAL:
            # CRITICAL: Reservation allows ONLY :30b model (orchestrator)
            if ":30b" not in model:
                logger.error("CRITICAL priority requires :30b model, got: %s", model)
                return JSONResponse(
                    status_code=503,
                    content={"error": "invalid_critical_model", "message": f"CRITICAL requires :30b model, got {model}"},
                )

            # Auto-create reservation if not exists
            if not self._reservation_session:
                logger.info("Auto-reserving GPU for CRITICAL request %s (model=%s)", request.request_id, model)
                async with self._reservation_lock:
                    gpu = self.gpu_pool.find_for_reservation()
                    if not gpu:
                        logger.error("CRITICAL: No healthy GPU available - REFUSING to run on CPU")
                        return JSONResponse(
                            status_code=503,
                            content={"error": "no_gpu_available", "message": "CRITICAL requests require GPU, no GPU available"},
                        )

                    self._reservation_session = "critical"
                    self._reservation_gpu = gpu.name
                    self._reservation_at = time.monotonic()
                    self._last_critical_activity = time.monotonic()
                    gpu.reserved_by = "critical"
                    gpu.reserved_at = time.monotonic()
                    # Immediately preempt ALL active requests (orchestrator priority)
                    if gpu.active_request_count() > 0:
                        logger.warning(
                            "GPU %s has %d active request(s), preempting ALL for CRITICAL",
                            gpu.name, gpu.active_request_count(),
                        )
                        await self._preempt_all(gpu)
                    # Unload ALL models (orchestrator :30b fills entire GPU)
                    await self.gpu_pool.unload_all(gpu, self._mgmt_client)

            gpu = self.gpu_pool.find_for_reservation()
            if not gpu:
                # No healthy GPU - CRITICAL NEVER runs on CPU
                logger.error("CRITICAL: No healthy GPU available - REFUSING to run on CPU")
                return JSONResponse(
                    status_code=503,
                    content={"error": "no_gpu_available", "message": "CRITICAL requests require GPU, no GPU available"},
                )

            # Immediately preempt ALL active requests (orchestrator needs GPU NOW)
            if gpu.active_request_count() > 0:
                logger.warning(
                    "GPU %s has %d active request(s), preempting ALL for CRITICAL request %s",
                    gpu.name, gpu.active_request_count(), request.request_id,
                )
                await self._preempt_all(gpu)

            # Ensure ONLY :30b model is loaded (nothing else fits)
            if not gpu.has_model(model):
                # Unload everything first (force cleanup)
                await self.gpu_pool.unload_all(gpu, self._mgmt_client)
                loaded = await self.gpu_pool.load_model(gpu, model, self._mgmt_client)
                if not loaded:
                    # Retry: unload from ALL GPUs and try again
                    logger.error("CRITICAL: Failed to load %s on GPU %s, retrying with aggressive cleanup", model, gpu.name)
                    for backend in self.gpu_pool.all_backends:
                        await self.gpu_pool.unload_all(backend, self._mgmt_client)
                    loaded = await self.gpu_pool.load_model(gpu, model, self._mgmt_client)
                    if not loaded:
                        logger.error("CRITICAL: Failed to load %s even after aggressive cleanup - REFUSING CPU fallback", model)
                        return JSONResponse(
                            status_code=503,
                            content={"error": "model_load_failed", "message": f"Failed to load {model} on GPU, CRITICAL cannot run on CPU"},
                        )
            elif len(gpu.loaded_models) > 1:
                # :30b is loaded but other models too - unload them!
                logger.warning("GPU has :30b + other models, unloading extras")
                await self.gpu_pool.unload_all(gpu, self._mgmt_client, except_models={model})

            self._last_critical_activity = time.monotonic()
            return await self._send_to_gpu(gpu, request)

        # ── 4. Find GPU with the model or free VRAM ─────────────────────
        gpu = self.gpu_pool.find_with_model(model)
        if gpu:
            return await self._send_to_gpu(gpu, request)

        gpu = self.gpu_pool.find_with_free_vram(model)
        if gpu:
            loaded = await self.gpu_pool.load_model(gpu, model, self._mgmt_client)
            if loaded:
                return await self._send_to_gpu(gpu, request)

        # ── 5. Try to make space on GPU if idle (unload IDLE models) ───
        if priority >= Priority.BACKGROUND:
            gpu = self.gpu_pool.find_least_busy()
            if gpu and gpu.active_request_count() == 0:
                # GPU is IDLE - unload old models and load requested model
                await self.gpu_pool.unload_all(gpu, self._mgmt_client)
                loaded = await self.gpu_pool.load_model(gpu, model, self._mgmt_client)
                if loaded:
                    return await self._send_to_gpu(gpu, request)
            # No idle GPU - fallback to CPU
            return await self._send_to_cpu(request)

        # ── 6. Mid-priority (CODING, VLM) – already handled above ──────
        # (CODING/VLM would have been routed in step 5 if GPU was idle)

        # Last resort – CPU
        return await self._send_to_cpu(request)

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
        """Preempt ALL requests running on a GPU backend (for CRITICAL priority)."""
        preempted = []
        for req_id, req in list(gpu.active_requests.items()):
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
                "Preempted ALL %d request(s) on GPU %s for CRITICAL work: %s",
                len(preempted), gpu.name, preempted,
            )
            # Give a brief grace period for streams to close
            await asyncio.sleep(settings.preempt_grace_s)

    # ── Orchestrator announce/release ───────────────────────────────────

    async def announce(self, req: AnnounceRequest) -> AnnounceResponse:
        """Reserve a GPU for critical work (session_id ignored - global reservation)."""
        async with self._reservation_lock:
            gpu = self.gpu_pool.find_for_reservation()
            if not gpu:
                return AnnounceResponse(
                    status="degraded",
                    model_loaded=False,
                    gpu_available=False,
                )

            # Cancel background model loading if in progress
            if self._bg_load_task and not self._bg_load_task.done():
                self._bg_load_task.cancel()

            # Set global reservation (session_id not tracked)
            self._reservation_session = "critical"
            self._reservation_gpu = gpu.name
            self._reservation_at = time.monotonic()
            self._last_critical_activity = time.monotonic()
            gpu.reserved_by = "critical"
            gpu.reserved_at = time.monotonic()

            logger.info(
                "GPU %s reserved for CRITICAL work (model=%s)",
                gpu.name, req.model,
            )

            # Immediately preempt ALL active requests (orchestrator reservation)
            if gpu.active_request_count() > 0:
                logger.warning(
                    "GPU %s has %d active request(s), preempting ALL for reservation",
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
        """Release GPU reservation and start loading background models (session_id ignored)."""
        async with self._reservation_lock:
            if not self._reservation_session:
                logger.info("Release called but no reservation active")
                return ReleaseResponse(status="released", background_loading=False)

            gpu_name = self._reservation_gpu
            self._clear_reservation()

            # Schedule background model loading after a delay
            bg_loading = False
            if gpu_name:
                gpu = self.gpu_pool.backends.get(gpu_name)
                if gpu:
                    self._bg_load_task = asyncio.create_task(
                        self._delayed_background_load(gpu)
                    )
                    bg_loading = True

            logger.info("GPU reservation released")
            return ReleaseResponse(status="released", background_loading=bg_loading)

    def _clear_reservation(self) -> None:
        if self._reservation_gpu:
            gpu = self.gpu_pool.backends.get(self._reservation_gpu)
            if gpu:
                gpu.reserved_by = None
                gpu.reserved_at = None
        self._reservation_session = None
        self._reservation_gpu = None
        self._reservation_at = None
        self._last_critical_activity = None

    async def _delayed_background_load(self, gpu: GpuBackend) -> None:
        """After a delay, load background model set onto GPU."""
        try:
            await asyncio.sleep(settings.background_load_delay_s)

            # Double-check no reservation happened during the delay
            if self._reservation_session:
                return

            # CRITICAL: If :30b model still loaded, don't load background set (won't fit)
            if any(":30b" in m for m in gpu.loaded_models):
                logger.warning("GPU %s has :30b model, skipping background load (won't fit)", gpu.name)
                return

            logger.info("Loading background model set on GPU %s", gpu.name)
            await self.gpu_pool.unload_all(gpu, self._mgmt_client)
            await self.gpu_pool.load_model_set(gpu, "background", self._mgmt_client)
        except asyncio.CancelledError:
            logger.info("Background model loading cancelled")
        except Exception as e:
            logger.error("Background model loading failed: %s", e)

    # ── Reservation watchdog ────────────────────────────────────────────

    async def _reservation_watchdog(self) -> None:
        """Auto-release GPU reservation on timeout."""
        while True:
            try:
                await asyncio.sleep(30)
                async with self._reservation_lock:
                    if not self._reservation_session:
                        continue

                    now = time.monotonic()

                    # Absolute timeout
                    if self._reservation_at and (now - self._reservation_at) > settings.orchestrator_reservation_timeout_s:
                        logger.warning(
                            "Reservation for session %s exceeded %ds absolute timeout, auto-releasing",
                            self._reservation_session, settings.orchestrator_reservation_timeout_s,
                        )
                        self._clear_reservation()
                        continue

                    # Idle timeout
                    if self._last_critical_activity and (now - self._last_critical_activity) > settings.orchestrator_idle_timeout_s:
                        logger.warning(
                            "Reservation for session %s idle for %ds, auto-releasing",
                            self._reservation_session, settings.orchestrator_idle_timeout_s,
                        )
                        gpu_name = self._reservation_gpu
                        self._clear_reservation()

                        # Start background loading
                        if gpu_name:
                            gpu = self.gpu_pool.backends.get(gpu_name)
                            if gpu:
                                self._bg_load_task = asyncio.create_task(
                                    self._delayed_background_load(gpu)
                                )

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
        """Check if GPU is reserved by a different session."""
        return gpu.reserved_by is not None

    @property
    def is_reserved(self) -> bool:
        return self._reservation_session is not None
