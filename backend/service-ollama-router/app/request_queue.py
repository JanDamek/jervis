"""Two-tier request queue: unlimited CRITICAL + bounded NORMAL.

Router ALWAYS accepts requests. Never returns 503/reject.
Each request is queued and dispatched when a backend slot becomes available.

CRITICAL requests preempt NORMAL when all GPU slots are busy.
CRITICAL NEVER goes to CPU — only GPU.
CPU is exclusively for NORMAL/background work.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from starlette.responses import Response, JSONResponse, StreamingResponse

from .config import settings
from .gpu_state import GpuBackend, estimate_vram
from .models import (
    EMBEDDING_MODELS,
    EMBEDDING_PATHS,
    Priority,
    RequestState,
    TrackedRequest,
)
from .proxy import is_streaming_request, proxy_non_streaming, proxy_streaming

if TYPE_CHECKING:
    from .gpu_state import GpuPool

logger = logging.getLogger("ollama-router.queue")


@dataclass
class QueueEntry:
    request: TrackedRequest
    future: asyncio.Future
    queued_at: float = field(default_factory=time.monotonic)


class RequestQueue:
    """Two-tier request queue with backend-aware dispatch.

    CRITICAL queue: unlimited — chat, foreground, interactive.
    NORMAL queue: bounded (back-pressure when full).

    Dispatch rules:
    - CRITICAL always gets GPU, never CPU. Preempts NORMAL if needed.
    - NORMAL gets GPU or CPU, whichever is available.
    - Max 2 concurrent requests per backend (Ollama handles 2 well).
    """

    def __init__(
        self,
        gpu_pool: GpuPool,
        cpu_url: str,
        router,   # OllamaRouter — back-reference for model loading etc.
    ) -> None:
        self.gpu_pool = gpu_pool
        self.cpu_url = cpu_url
        self._router = router

        self._critical: asyncio.Queue[QueueEntry] = asyncio.Queue()
        self._normal: asyncio.Queue[QueueEntry] = asyncio.Queue(
            maxsize=settings.normal_queue_max,
        )
        self._dispatch_event = asyncio.Event()
        self._dispatcher_task: asyncio.Task | None = None

        # Track CPU active requests (GPUs tracked in GpuBackend.active_requests)
        self._cpu_active: dict[str, TrackedRequest] = {}

        self._max_per_backend = settings.max_concurrent_per_backend

    async def start(self) -> None:
        self._dispatcher_task = asyncio.create_task(self._dispatch_loop())
        logger.info(
            "RequestQueue started (NORMAL max=%d, max_concurrent_per_backend=%d)",
            settings.normal_queue_max, self._max_per_backend,
        )

    async def stop(self) -> None:
        if self._dispatcher_task:
            self._dispatcher_task.cancel()
            try:
                await self._dispatcher_task
            except (asyncio.CancelledError, Exception):
                pass

    # ── Public API ───────────────────────────────────────────────────────

    async def submit(self, request: TrackedRequest) -> Response:
        """Submit a request. Always accepts. Returns when request completes."""

        # Try immediate dispatch (fast path — no queuing if slot available)
        backend = self._find_slot(request)
        if backend:
            return await self._execute(backend, request)

        # Queue the request
        future: asyncio.Future[Response] = asyncio.get_event_loop().create_future()
        entry = QueueEntry(request=request, future=future)

        if request.priority <= Priority.CRITICAL:
            self._critical.put_nowait(entry)  # unlimited — never fails
            logger.info(
                "QUEUE_IN: id=%s priority=CRITICAL model=%s (queue_depth=%d)",
                request.request_id, request.model, self._critical.qsize(),
            )
            # CRITICAL waiting behind NORMAL → preempt
            self._preempt_normal_for_critical()
        else:
            try:
                self._normal.put_nowait(entry)
            except asyncio.QueueFull:
                logger.warning(
                    "QUEUE_FULL: id=%s model=%s — NORMAL queue full (%d), back-pressure",
                    request.request_id, request.model, settings.normal_queue_max,
                )
                return JSONResponse(
                    status_code=429,
                    content={
                        "error": "queue_full",
                        "message": f"NORMAL queue full ({settings.normal_queue_max}). Try again later.",
                    },
                )
            logger.info(
                "QUEUE_IN: id=%s priority=NORMAL model=%s (queue_depth=%d)",
                request.request_id, request.model, self._normal.qsize(),
            )

        self._dispatch_event.set()

        # Wait for dispatch + execution to complete
        # If client disconnects, cancel_event is set by disconnect monitor
        try:
            return await self._wait_for_result(request, future)
        except asyncio.CancelledError:
            return JSONResponse(
                status_code=499,
                content={"error": "cancelled", "message": "Request cancelled"},
            )

    async def _wait_for_result(
        self, request: TrackedRequest, future: asyncio.Future,
    ) -> Response:
        """Wait for future result, respecting cancel_event."""
        cancel_task = asyncio.create_task(request.cancel_event.wait())
        future_task = asyncio.ensure_future(future)

        done, pending = await asyncio.wait(
            [cancel_task, future_task],
            return_when=asyncio.FIRST_COMPLETED,
        )
        for t in pending:
            t.cancel()
            try:
                await t
            except (asyncio.CancelledError, Exception):
                pass

        if cancel_task in done:
            # Client disconnected while in queue
            logger.warning(
                "QUEUE_CANCEL: id=%s — client disconnected while queued",
                request.request_id,
            )
            return JSONResponse(
                status_code=499,
                content={"error": "cancelled", "message": "Client disconnected"},
            )

        return future.result()

    def notify_slot_freed(self) -> None:
        """Called when a backend finishes a request — wake dispatcher."""
        self._dispatch_event.set()

    @property
    def queue_depth(self) -> dict[str, int]:
        return {
            "critical": self._critical.qsize(),
            "normal": self._normal.qsize(),
        }

    # ── Dispatcher loop ──────────────────────────────────────────────────

    async def _dispatch_loop(self) -> None:
        """Background task: assign queued requests to available backend slots."""
        logger.info("Dispatcher loop started")
        while True:
            try:
                await self._dispatch_event.wait()
                self._dispatch_event.clear()

                # Process queued requests until no more slots or no more requests
                dispatched_any = True
                while dispatched_any:
                    dispatched_any = False

                    # Always CRITICAL first
                    while not self._critical.empty():
                        entry = self._critical.get_nowait()
                        if entry.request.cancel_event.is_set():
                            # Skip cancelled requests
                            if not entry.future.done():
                                entry.future.set_result(JSONResponse(
                                    status_code=499,
                                    content={"error": "cancelled"},
                                ))
                            continue

                        backend = self._find_slot(entry.request)
                        if backend:
                            asyncio.create_task(
                                self._execute_and_resolve(backend, entry)
                            )
                            dispatched_any = True
                        else:
                            # No slot — preempt NORMAL and re-check
                            self._preempt_normal_for_critical()
                            # Put back and wait for preemption to free a slot
                            self._critical.put_nowait(entry)
                            break

                    # Then NORMAL on remaining capacity
                    while not self._normal.empty():
                        entry = self._normal.get_nowait()
                        if entry.request.cancel_event.is_set():
                            if not entry.future.done():
                                entry.future.set_result(JSONResponse(
                                    status_code=499,
                                    content={"error": "cancelled"},
                                ))
                            continue

                        backend = self._find_slot(entry.request)
                        if backend:
                            asyncio.create_task(
                                self._execute_and_resolve(backend, entry)
                            )
                            dispatched_any = True
                        else:
                            # No slot — put back and wait
                            self._normal.put_nowait(entry)
                            break

            except asyncio.CancelledError:
                return
            except Exception as e:
                logger.error("Dispatcher error: %s", e)
                await asyncio.sleep(1)

    # ── Slot finding ─────────────────────────────────────────────────────

    def _find_slot(self, request: TrackedRequest) -> str | None:
        """Find an available backend for this request.

        Returns backend identifier: "gpu:<name>" or "cpu", or None if no slot.

        CRITICAL: only GPU slots (never CPU).
        NORMAL: GPU preferred, CPU fallback for small models.
        """
        model = request.model
        is_critical = request.priority <= Priority.CRITICAL

        # Try GPU backends (prefer one with model already loaded, least busy)
        gpu_candidates = []
        for b in self.gpu_pool.healthy_backends:
            if b.active_request_count() >= self._max_per_backend:
                continue
            if b.loading_in_progress:
                continue
            # For NORMAL, skip reserved GPUs
            if not is_critical and b.reserved_by:
                continue
            gpu_candidates.append(b)

        # Prefer GPU that already has the model
        with_model = [b for b in gpu_candidates if b.has_model(model)]
        if with_model:
            best = min(with_model, key=lambda b: b.active_request_count())
            return f"gpu:{best.name}"

        # GPU without model but available (will need load)
        if gpu_candidates:
            best = min(gpu_candidates, key=lambda b: b.active_request_count())
            return f"gpu:{best.name}"

        # CRITICAL never goes to CPU
        if is_critical:
            return None

        # CPU for NORMAL (small models only)
        if estimate_vram(model) < 20.0 and self._cpu_active_count() < self._max_per_backend:
            return "cpu"

        return None

    def _cpu_active_count(self) -> int:
        # Clean up finished requests
        self._cpu_active = {
            k: v for k, v in self._cpu_active.items()
            if v.state in (RequestState.RUNNING_CPU, RequestState.QUEUED)
        }
        return len(self._cpu_active)

    # ── Execution ────────────────────────────────────────────────────────

    async def _execute(self, backend: str, request: TrackedRequest) -> Response:
        """Execute request on backend directly (fast path, no future)."""
        try:
            return await self._run_on_backend(backend, request)
        finally:
            self._cleanup_backend(backend, request)
            self.notify_slot_freed()

    async def _execute_and_resolve(self, backend: str, entry: QueueEntry) -> None:
        """Execute request and resolve the future (queued path)."""
        wait_time = time.monotonic() - entry.queued_at
        logger.info(
            "QUEUE_DISPATCH: id=%s waited=%.2fs → %s",
            entry.request.request_id, wait_time, backend,
        )
        try:
            response = await self._run_on_backend(backend, entry.request)
            if not entry.future.done():
                entry.future.set_result(response)
        except Exception as e:
            if not entry.future.done():
                entry.future.set_result(JSONResponse(
                    status_code=500,
                    content={"error": "execution_failed", "message": str(e)},
                ))
        finally:
            self._cleanup_backend(backend, entry.request)
            self.notify_slot_freed()

    async def _run_on_backend(self, backend: str, request: TrackedRequest) -> Response:
        """Actually proxy the request to a backend."""
        if backend == "cpu":
            return await self._send_to_cpu(request)
        elif backend.startswith("gpu:"):
            gpu_name = backend[4:]
            gpu = self.gpu_pool.backends.get(gpu_name)
            if not gpu:
                return JSONResponse(
                    status_code=503,
                    content={"error": "gpu_unavailable", "message": f"GPU {gpu_name} not found"},
                )
            # Ensure model is loaded
            if not gpu.has_model(request.model):
                loaded = await self.gpu_pool.load_model(
                    gpu, request.model, self._router._mgmt_client,
                )
                if not loaded:
                    return JSONResponse(
                        status_code=503,
                        content={"error": "model_load_failed", "message": f"Failed to load {request.model}"},
                    )
            return await self._send_to_gpu(gpu, request)
        else:
            return JSONResponse(status_code=500, content={"error": "unknown_backend"})

    def _cleanup_backend(self, backend: str, request: TrackedRequest) -> None:
        """Remove request from backend tracking."""
        if backend == "cpu":
            self._cpu_active.pop(request.request_id, None)
        elif backend.startswith("gpu:"):
            gpu_name = backend[4:]
            gpu = self.gpu_pool.backends.get(gpu_name)
            if gpu:
                gpu.active_requests.pop(request.request_id, None)

    # ── Send helpers ─────────────────────────────────────────────────────

    async def _send_to_gpu(self, gpu: GpuBackend, request: TrackedRequest) -> Response:
        request.state = RequestState.RUNNING_GPU
        request.target_gpu = gpu.name
        gpu.active_requests[request.request_id] = request
        gpu.last_activity = time.monotonic()

        # Notify router of CRITICAL activity for reservation management
        if request.priority <= Priority.CRITICAL:
            self._router.notify_critical_activity(gpu.name)

        logger.info(
            "REQUEST_PROXY: id=%s → GPU %s (%s) model=%s priority=%s",
            request.request_id, gpu.name, gpu.url, request.model, request.priority.name,
        )

        if request.api_path in EMBEDDING_PATHS or not is_streaming_request(request.body):
            return await proxy_non_streaming(gpu.url, request)
        return await proxy_streaming(gpu.url, request)

    async def _send_to_cpu(self, request: TrackedRequest) -> Response:
        request.state = RequestState.RUNNING_CPU
        self._cpu_active[request.request_id] = request

        logger.info(
            "REQUEST_PROXY: id=%s → CPU (%s) model=%s priority=%s",
            request.request_id, self.cpu_url, request.model, request.priority.name,
        )

        if request.api_path in EMBEDDING_PATHS or not is_streaming_request(request.body):
            return await proxy_non_streaming(self.cpu_url, request)
        return await proxy_streaming(self.cpu_url, request)

    # ── CRITICAL preemption ──────────────────────────────────────────────

    def _preempt_normal_for_critical(self) -> None:
        """If CRITICAL is queued and all GPU slots occupied by NORMAL, preempt one."""
        if self._critical.empty():
            return

        # Find a GPU running NORMAL that we can preempt
        for gpu in self.gpu_pool.healthy_backends:
            for req_id, req in list(gpu.active_requests.items()):
                if req.priority >= Priority.NORMAL:
                    if req.model in EMBEDDING_MODELS and not settings.preempt_embeddings:
                        continue
                    logger.warning(
                        "PREEMPT_FOR_QUEUE: id=%s model=%s on GPU %s — preempted for queued CRITICAL",
                        req_id, req.model, gpu.name,
                    )
                    req.cancel_event.set()
                    req.state = RequestState.PREEMPTED
                    # After preemption completes (grace period handled by proxy),
                    # cleanup will call notify_slot_freed() which wakes dispatcher
                    return  # One preemption at a time
