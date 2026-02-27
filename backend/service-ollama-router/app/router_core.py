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
    MODEL_TO_PRIORITY,
    Priority,
    TrackedRequest,
)
from .request_queue import RequestQueue

logger = logging.getLogger("ollama-router.core")


class OllamaRouter:
    def __init__(self, gpu_pool: GpuPool) -> None:
        self.gpu_pool = gpu_pool
        self.cpu_url = settings.cpu_backend_url.rstrip("/")
        self.cpu_healthy = True

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

    async def startup(self) -> None:
        """Initialize router state on startup."""
        self._mgmt_client = httpx.AsyncClient(timeout=httpx.Timeout(30.0))
        # Sync GPU state from all backends (initial check only, fail fast after)
        await self.gpu_pool.sync_state(self._mgmt_client)
        await self._check_cpu_health()
        # Start request queue
        self._queue = RequestQueue(self.gpu_pool, self.cpu_url, self)
        await self._queue.start()
        # Start background tasks
        self._watchdog_task = asyncio.create_task(self._reservation_watchdog())
        self._request_timeout_task = asyncio.create_task(self._request_timeout_watchdog())
        logger.info("Router started with %d GPU backend(s), queue enabled", len(self.gpu_pool.all_backends))

    async def shutdown(self) -> None:
        """Cleanup on shutdown."""
        if self._queue:
            await self._queue.stop()
        if self._watchdog_task:
            self._watchdog_task.cancel()
        if self._request_timeout_task:
            self._request_timeout_task.cancel()
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

        # ── Entry logging ──
        qdepth = self._queue.queue_depth if self._queue else {}
        logger.info(
            "REQUEST_IN: id=%s model=%s priority=%s path=%s queue=%s",
            request_id, model, priority.name, api_path, qdepth,
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
        """After a delay, load background models onto GPU.

        When :30b is loaded: only load embedding models (14b on CPU — too much offload).
        When no :30b: load full background set (14b + embedding).
        """
        try:
            await asyncio.sleep(settings.background_load_delay_s)

            # Double-check GPU didn't get reserved during the delay
            if gpu.name in self._reservations:
                return

            has_big_model = any(":30b" in m for m in gpu.loaded_models)
            if has_big_model:
                # Only load embedding alongside :30b (14b would cause too much CPU offload)
                logger.info(
                    "GPU %s has :30b — loading only embedding models alongside",
                    gpu.name,
                )
                for emb_model in EMBEDDING_MODELS:
                    if not gpu.has_model(emb_model):
                        await self.gpu_pool.load_model(gpu, emb_model, self._mgmt_client)
            else:
                logger.info(
                    "Loading background model set on GPU %s (existing: %s)",
                    gpu.name, list(gpu.loaded_models.keys()),
                )
                await self.gpu_pool.load_model_set(gpu, "background", self._mgmt_client)
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

                # Also check CPU active requests via queue
                cpu_active = 0
                if self._queue:
                    cpu_active = self._queue._cpu_active_count()

                if total_active > 0 or cpu_active > 0:
                    qdepth = self._queue.queue_depth if self._queue else {}
                    logger.info(
                        "ACTIVE_REQUESTS: gpu=%d cpu=%d queue=%s",
                        total_active, cpu_active, qdepth,
                    )

            except asyncio.CancelledError:
                return
            except Exception as e:
                logger.error("Request timeout watchdog error: %s", e)

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
        return MODEL_TO_PRIORITY.get(model, Priority.NORMAL)

    @property
    def is_reserved(self) -> bool:
        return bool(self._reservations)
