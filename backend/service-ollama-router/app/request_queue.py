"""Two-tier request queue: unlimited CRITICAL + unlimited NORMAL.

Router ALWAYS accepts requests. Never returns 429/503/reject.
Each request is queued and dispatched when a GPU slot becomes available.
Callers wait as long as needed (minutes) — only 500 on internal error.

CRITICAL requests preempt NORMAL when all GPU slots are busy.
GPU only — no CPU backend.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from starlette.responses import Response, JSONResponse, StreamingResponse

from .config import settings
from .gpu_state import GpuBackend
from .models import (
    EMBEDDING_MODELS,
    EMBEDDING_PATHS,
    GPU_MODEL_SETS,
    MODEL_EQUIVALENTS,
    Priority,
    RequestState,
    TrackedRequest,
    VLM_GPU,
)
from .proxy import is_streaming_request, proxy_non_streaming, proxy_streaming

if TYPE_CHECKING:
    from .gpu_state import GpuPool

logger = logging.getLogger("ollama-router.queue")


def _find_equivalent_in_set(model: str, gpu_set: list[str]) -> str | None:
    """Find an equivalent model that exists in the GPU's model set."""
    for equiv in MODEL_EQUIVALENTS.get(model, []):
        if equiv in gpu_set:
            return equiv
    return None


@dataclass
class QueueEntry:
    request: TrackedRequest
    future: asyncio.Future
    queued_at: float = field(default_factory=time.monotonic)


class RequestQueue:
    """Two-tier request queue with GPU-only dispatch.

    CRITICAL queue: unlimited — chat, foreground, interactive.
    NORMAL queue: unlimited — background, embedding, indexing (waits as long as needed).

    Dispatch rules:
    - CRITICAL preempts NORMAL if needed.
    - Max 1 concurrent request per GPU (serial is faster than parallel when VRAM spills).
    - GPU only — no CPU backend.
    - Never returns 429 — all requests are queued and wait for a slot.
    """

    def __init__(
        self,
        gpu_pool: GpuPool,
        router,   # OllamaRouter — back-reference for model loading etc.
    ) -> None:
        self.gpu_pool = gpu_pool
        self._router = router

        self._critical: asyncio.Queue[QueueEntry] = asyncio.Queue()
        self._normal: asyncio.Queue[QueueEntry] = asyncio.Queue()  # unlimited — never reject
        self._dispatch_event = asyncio.Event()
        self._dispatcher_task: asyncio.Task | None = None

        self._max_concurrent_llm = settings.max_concurrent_llm
        self._max_concurrent_embeddings = settings.max_concurrent_embeddings

        # Round-robin counter for tie-breaking when multiple GPUs have same load
        self._rr_counter: int = 0

    async def start(self) -> None:
        self._dispatcher_task = asyncio.create_task(self._dispatch_loop())
        logger.info(
            "RequestQueue started (unlimited queues, llm_concurrent=%d, embedding_concurrent=%d)",
            self._max_concurrent_llm, self._max_concurrent_embeddings,
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
                "QUEUE_IN: id=%s priority=%s model=%s (queue_depth=%d)",
                request.request_id, request.priority.name, request.model, self._critical.qsize(),
            )
            # CRITICAL/CASCADE waiting behind NORMAL → preempt NORMAL queue entries
            self._preempt_normal_for_critical()
        else:
            self._normal.put_nowait(entry)  # unlimited queue — never fails
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
        """Find an available GPU backend for this request.

        Returns backend identifier "gpu:<name>", or None if no slot.
        GPU only — no CPU backend.

        Concurrency per type (from benchmark):
        - Embedding: up to max_concurrent_embeddings (sweet spot 4-5)
        - LLM/VLM: serial (1 at a time, VRAM spill makes parallel slower)
        """
        is_critical = request.priority <= Priority.CRITICAL
        is_embedding = request.model in EMBEDDING_MODELS

        max_concurrent = self._max_concurrent_embeddings if is_embedding else self._max_concurrent_llm

        # Try GPU backends (prefer one with model already loaded, least busy)
        gpu_candidates = []
        for b in self.gpu_pool.healthy_backends:
            # Count active requests of same type on this backend
            if is_embedding:
                active = sum(1 for r in b.active_requests.values() if r.model in EMBEDDING_MODELS)
            else:
                active = sum(1 for r in b.active_requests.values() if r.model not in EMBEDDING_MODELS)
            if active >= max_concurrent:
                continue
            if b.loading_in_progress:
                continue
            # For NORMAL, skip reserved GPUs
            if not is_critical and b.reserved_by:
                continue
            # Only route to GPUs that have this model (or an equivalent) in their model set.
            gpu_set = GPU_MODEL_SETS.get(b.name, [])
            if request.model not in gpu_set:
                # Check model equivalents — e.g. qwen3:14b can run on 30b GPU
                equiv_model = _find_equivalent_in_set(request.model, gpu_set)
                if equiv_model is None:
                    continue
                # Tag the backend with the equivalent model for proxy to use
                gpu_candidates.append((b, equiv_model))
                continue
            gpu_candidates.append((b, None))

        if not gpu_candidates:
            return None

        # Prefer GPU that has the exact requested model
        exact = [(b, eq) for b, eq in gpu_candidates if eq is None and b.has_model(request.model)]
        if exact:
            best, _ = self._pick_least_busy_rr_pair(exact)
            return f"gpu:{best.name}"

        # Next: GPU with equivalent model (model redirect)
        equiv = [(b, eq) for b, eq in gpu_candidates if eq is not None and b.has_model(eq)]
        if equiv:
            best, equiv_model = self._pick_least_busy_rr_pair(equiv)
            # Redirect: swap model in request body so it runs on the equivalent
            request.body["model"] = equiv_model
            request.model = equiv_model
            logger.info("MODEL_REDIRECT: %s → %s on GPU %s", request.original_model or "?", equiv_model, best.name)
            return f"gpu:{best.name}"

        # GPU available but model needs loading
        if gpu_candidates:
            best, _ = self._pick_least_busy_rr_pair(gpu_candidates)
            return f"gpu:{best.name}"

        return None

    def _pick_least_busy_rr(self, candidates: list[GpuBackend]) -> GpuBackend:
        """Pick least busy GPU. Round-robin tie-break when equal load."""
        min_count = min(b.active_request_count() for b in candidates)
        tied = [b for b in candidates if b.active_request_count() == min_count]
        pick = tied[self._rr_counter % len(tied)]
        self._rr_counter += 1
        return pick

    def _pick_least_busy_rr_pair(self, candidates: list[tuple[GpuBackend, str | None]]) -> tuple[GpuBackend, str | None]:
        """Pick least busy GPU from (backend, equiv_model) pairs."""
        min_count = min(b.active_request_count() for b, _ in candidates)
        tied = [(b, eq) for b, eq in candidates if b.active_request_count() == min_count]
        pick = tied[self._rr_counter % len(tied)]
        self._rr_counter += 1
        return pick

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

    async def _release_whisper_gpu(self) -> None:
        """Ask whisper REST service to release GPU VRAM (before loading VLM on p40-2)."""
        try:
            import httpx
            async with httpx.AsyncClient(timeout=10) as client:
                resp = await client.post(f"{settings.whisper_gpu_url}/gpu/release")
                if resp.status_code == 200:
                    data = resp.json()
                    logger.info("WHISPER_GPU_RELEASE: %s", data.get("status", "ok"))
                elif resp.status_code == 409:
                    logger.info("WHISPER_GPU_RELEASE: busy (transcription in progress)")
                else:
                    logger.warning("WHISPER_GPU_RELEASE: HTTP %d", resp.status_code)
        except Exception as e:
            logger.debug("WHISPER_GPU_RELEASE: not reachable (%s) — OK if whisper not running", e)

    async def _run_on_backend(self, backend: str, request: TrackedRequest) -> Response:
        """Actually proxy the request to a GPU backend."""
        if not backend.startswith("gpu:"):
            return JSONResponse(status_code=500, content={"error": "unknown_backend"})

        gpu_name = backend[4:]
        gpu = self.gpu_pool.backends.get(gpu_name)
        if not gpu:
            return JSONResponse(
                status_code=503,
                content={"error": "gpu_unavailable", "message": f"GPU {gpu_name} not found"},
            )
        # Ensure model is loaded
        if not gpu.has_model(request.model):
            # If loading non-embedding on p40-2, coordinate with whisper first
            if gpu.name == VLM_GPU and request.model not in EMBEDDING_MODELS:
                # Check if whisper is actively transcribing (flag-based)
                if self._router.check_whisper_busy():
                    logger.info("VLM_WAIT_WHISPER: whisper active, waiting for done before loading %s", request.model)
                    ok = await self._router.wait_for_whisper_done(timeout=settings.whisper_gpu_acquire_timeout_s)
                    if not ok:
                        logger.warning("VLM_WAIT_WHISPER: timeout waiting for whisper done")
                # Ask whisper server to unload model from VRAM
                await self._release_whisper_gpu()
            loaded = await self.gpu_pool.load_model(
                gpu, request.model, self._router._mgmt_client,
            )
            if not loaded:
                return JSONResponse(
                    status_code=503,
                    content={"error": "model_load_failed", "message": f"Failed to load {request.model}"},
                )
        return await self._send_to_gpu(gpu, request)

    def _cleanup_backend(self, backend: str, request: TrackedRequest) -> None:
        """Remove request from GPU backend tracking."""
        if backend.startswith("gpu:"):
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

        def on_gpu_connect_error(error_msg: str) -> None:
            """Mark GPU unhealthy and trigger recovery on connection failure."""
            if gpu.healthy:
                logger.warning(
                    "GPU %s unreachable during request: %s — marking unhealthy, starting recovery",
                    gpu.name, error_msg,
                )
                gpu.healthy = False
                self._router._start_gpu_recovery()

        if request.api_path in EMBEDDING_PATHS or not is_streaming_request(request.body):
            return await proxy_non_streaming(gpu.url, request, on_connect_error=on_gpu_connect_error)
        return await proxy_streaming(gpu.url, request, on_connect_error=on_gpu_connect_error)

    # ── CRITICAL preemption ──────────────────────────────────────────────

    def _preempt_normal_for_critical(self) -> bool:
        """If CRITICAL is queued and all GPU slots occupied by NORMAL, preempt one.

        Returns True if a preemption was initiated, False if nothing to preempt.
        Skips already-preempted requests to avoid duplicate preemption attempts.
        """
        if self._critical.empty():
            return False

        # Find a GPU running NORMAL that we can preempt (skip already preempted)
        for gpu in self.gpu_pool.healthy_backends:
            for req_id, req in list(gpu.active_requests.items()):
                if req.state == RequestState.PREEMPTED:
                    continue  # Already preempted — don't re-signal
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
                    return True  # One preemption at a time
        return False
