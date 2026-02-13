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

        logger.debug(
            "Routing request %s: model=%s priority=%s path=%s",
            request_id, model, priority.name, api_path,
        )

        try:
            return await self._do_route(request)
        finally:
            # Cleanup active request tracking
            for backend in self.gpu_pool.all_backends:
                backend.active_requests.pop(request_id, None)

    async def _do_route(self, request: TrackedRequest) -> Response:
        model = request.model
        priority = request.priority

        # ── 1. Find GPU that already has this model loaded ──────────────
        gpu = self.gpu_pool.find_with_model(model)
        if gpu:
            if priority <= Priority.CRITICAL or not self._is_reserved_by_other(gpu):
                return await self._send_to_gpu(gpu, request)

        # ── 2. P0 CRITICAL: preempt if needed, ensure model loaded ──────
        if priority <= Priority.CRITICAL:
            gpu = self.gpu_pool.find_for_reservation()
            if not gpu:
                # No healthy GPU – fall back to CPU
                logger.warning("No healthy GPU for CRITICAL request %s, falling back to CPU", request.request_id)
                return await self._send_to_cpu(request)

            # Preempt background work on this GPU
            if gpu.has_active_background():
                await self._preempt_background(gpu)

            # Ensure model is loaded
            if not gpu.has_model(model):
                # Unload other sets first
                await self.gpu_pool.unload_all(gpu, self._mgmt_client, except_models={model})
                loaded = await self.gpu_pool.load_model(gpu, model, self._mgmt_client)
                if not loaded:
                    logger.error("Failed to load %s on GPU %s, falling back to CPU", model, gpu.name)
                    return await self._send_to_cpu(request)

            self._last_critical_activity = time.monotonic()
            return await self._send_to_gpu(gpu, request)

        # ── 3. If GPU is reserved by orchestrator → background to CPU ──
        if priority >= Priority.BACKGROUND and self._reservation_session:
            return await self._send_to_cpu(request)

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
        logger.info(
            "→ GPU %s: request=%s model=%s priority=%s",
            gpu.name, request.request_id, request.model, request.priority.name,
        )

        if request.api_path in EMBEDDING_PATHS or not is_streaming_request(request.body):
            return await proxy_non_streaming(target_url, request)
        return await proxy_streaming(target_url, request)

    async def _send_to_cpu(self, request: TrackedRequest) -> Response:
        request.state = RequestState.RUNNING_CPU

        if not self.cpu_healthy:
            return JSONResponse(
                status_code=503,
                content={"error": "no_backend_available", "message": "Both GPU and CPU backends are unavailable"},
            )

        logger.info(
            "→ CPU: request=%s model=%s priority=%s",
            request.request_id, request.model, request.priority.name,
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
                preempted.append(req_id)

        if preempted:
            logger.info(
                "Preempted %d background request(s) on GPU %s: %s",
                len(preempted), gpu.name, preempted,
            )
            # Give a brief grace period for streams to close
            await asyncio.sleep(settings.preempt_grace_s)

    # ── Orchestrator announce/release ───────────────────────────────────

    async def announce(self, req: AnnounceRequest) -> AnnounceResponse:
        """Reserve a GPU for orchestrator/critical work."""
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

            # Set reservation
            self._reservation_session = req.session_id
            self._reservation_gpu = gpu.name
            self._reservation_at = time.monotonic()
            self._last_critical_activity = time.monotonic()
            gpu.reserved_by = req.session_id
            gpu.reserved_at = time.monotonic()

            logger.info(
                "GPU %s reserved for session %s (model=%s)",
                gpu.name, req.session_id, req.model,
            )

            # Preempt background work
            if gpu.has_active_background():
                await self._preempt_background(gpu)

            # Ensure model is loaded
            model_loaded = gpu.has_model(req.model)
            if not model_loaded:
                await self.gpu_pool.unload_all(gpu, self._mgmt_client, except_models={req.model})
                model_loaded = await self.gpu_pool.load_model(
                    gpu, req.model, self._mgmt_client,
                    keep_alive=settings.default_keep_alive,
                )

            return AnnounceResponse(
                status="ready" if model_loaded else "error",
                model_loaded=model_loaded,
                gpu_available=True,
                gpu_name=gpu.name,
            )

    async def release(self, req: ReleaseRequest) -> ReleaseResponse:
        """Release GPU reservation and start loading background models."""
        async with self._reservation_lock:
            if self._reservation_session != req.session_id:
                logger.warning(
                    "Release called for session %s but reservation is %s",
                    req.session_id, self._reservation_session,
                )
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

            logger.info("GPU reservation released for session %s", req.session_id)
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
