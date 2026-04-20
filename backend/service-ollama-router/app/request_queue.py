"""Capability-based request queue — GPU-only dispatch.

Three independent queues run in parallel (embed, llm, vlm). Each is
priority-ordered: CASCADE (-1) > CRITICAL (0) > NORMAL (1). Within the
same priority, FIFO by submission time. CRITICAL preempts NORMAL
within the same queue group.

The queue's public `submit()` returns either an `AsyncIterator[dict]`
(for streaming paths — /api/chat, /api/generate with stream=true) or a
single parsed `dict` (non-streaming — embeddings). The gRPC inference
servicer consumes the result and translates to proto chunks.

Router ALWAYS accepts. Requests that cannot run immediately wait in
the queue; no 429/503 returned. If the caller cancels (gRPC context
done / preemption), `request.cancel_event` is set and the pending
request is dropped from the queue.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import AsyncIterator, TYPE_CHECKING, Union

from .config import settings
from .gpu_state import GpuBackend
from .models import (
    EMBEDDING_MODELS,
    EMBEDDING_PATHS,
    GPU_MODEL_SETS,
    LOCAL_MODEL_SIZE,
    MODEL_EQUIVALENTS,
    Priority,
    QueueGroup,
    RequestState,
    TrackedRequest,
    VLM_GPU,
)
from .proxy import ProxyError, is_streaming_request, proxy_non_streaming, proxy_streaming

if TYPE_CHECKING:
    from .gpu_state import GpuPool

logger = logging.getLogger("ollama-router.queue")


SubmitResult = Union[AsyncIterator[dict], dict]


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


class QueueCancelled(RuntimeError):
    """Raised when a queued request is cancelled (client disconnect / preemption)."""


class RequestQueue:
    """Per-group priority queue with GPU-only dispatch.

    Three independent queues: embed, llm, vlm (see QueueGroup). CRITICAL
    can preempt NORMAL within the same group. Max 1 concurrent LLM/VLM
    request per GPU; embeddings allow up to `max_concurrent_embeddings`.
    """

    _GROUPS: tuple[QueueGroup, ...] = (QueueGroup.EMBED, QueueGroup.LLM, QueueGroup.VLM)

    def __init__(
        self,
        gpu_pool: GpuPool,
        router,   # OllamaRouter — back-reference for model loading etc.
    ) -> None:
        self.gpu_pool = gpu_pool
        self._router = router

        self._queues: dict[QueueGroup, asyncio.PriorityQueue] = {
            g: asyncio.PriorityQueue() for g in self._GROUPS
        }
        self._dispatch_events: dict[QueueGroup, asyncio.Event] = {
            g: asyncio.Event() for g in self._GROUPS
        }
        self._dispatcher_tasks: dict[QueueGroup, asyncio.Task] = {}
        self._seq_counter: int = 0

        self._max_concurrent_llm = settings.max_concurrent_llm
        self._max_concurrent_embeddings = settings.max_concurrent_embeddings
        self._rr_counter: int = 0

    async def start(self) -> None:
        for group in self._GROUPS:
            self._dispatcher_tasks[group] = asyncio.create_task(
                self._group_dispatch_loop(group)
            )
        logger.info(
            "RequestQueue started (groups=%s, llm_concurrent=%d, embedding_concurrent=%d)",
            [g.value for g in self._GROUPS],
            self._max_concurrent_llm, self._max_concurrent_embeddings,
        )

    async def stop(self) -> None:
        for task in self._dispatcher_tasks.values():
            task.cancel()
            try:
                await task
            except (asyncio.CancelledError, Exception):
                pass
        self._dispatcher_tasks.clear()

    # ── Public API ───────────────────────────────────────────────────────

    async def submit(self, request: TrackedRequest) -> SubmitResult:
        """Submit a request. Returns an async iterator (streaming) or a dict
        (unary). Blocks until a GPU slot is available.

        Raises QueueCancelled if the caller cancels while queued, or
        ProxyError if the upstream GPU returns an error before any chunk.
        """

        # Fast path — a slot is free right now.
        backend = self._find_slot(request)
        if backend:
            return await self._dispatch_to_backend(backend, request)

        # Slow path — queue and wait for the dispatcher.
        group = request.queue_group
        future: asyncio.Future[SubmitResult] = asyncio.get_event_loop().create_future()
        entry = QueueEntry(request=request, future=future)

        self._seq_counter += 1
        priority_value = int(request.priority)
        self._queues[group].put_nowait((priority_value, self._seq_counter, entry))
        logger.info(
            "QUEUE_IN: id=%s group=%s priority=%s model=%s min_size=%d (depth=%d)",
            request.request_id, group.value, request.priority.name, request.model,
            request.min_model_size, self._queues[group].qsize(),
        )

        if request.priority <= Priority.CRITICAL:
            self._preempt_normal_for_critical(group)

        self._dispatch_events[group].set()
        return await self._wait_for_result(request, future)

    async def _wait_for_result(
        self, request: TrackedRequest, future: asyncio.Future,
    ) -> SubmitResult:
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
            logger.warning(
                "QUEUE_CANCEL: id=%s — client disconnected while queued",
                request.request_id,
            )
            raise QueueCancelled("cancelled while queued")

        return future.result()

    def notify_slot_freed(self, group: QueueGroup | None = None) -> None:
        if group is not None:
            self._dispatch_events[group].set()
            return
        for event in self._dispatch_events.values():
            event.set()

    @property
    def queue_depth(self) -> dict[str, int]:
        return {g.value: self._queues[g].qsize() for g in self._GROUPS}

    # ── Dispatcher loop ──────────────────────────────────────────────────

    async def _group_dispatch_loop(self, group: QueueGroup) -> None:
        event = self._dispatch_events[group]
        pq = self._queues[group]
        logger.info("Dispatcher loop started for group=%s", group.value)
        while True:
            try:
                await event.wait()
                event.clear()

                while not pq.empty():
                    priority_value, seq, entry = pq.get_nowait()
                    if entry.request.cancel_event.is_set():
                        if not entry.future.done():
                            entry.future.set_exception(QueueCancelled("cancelled before dispatch"))
                        continue

                    backend = self._find_slot(entry.request)
                    if backend:
                        asyncio.create_task(self._resolve_queued(backend, entry))
                        continue

                    pq.put_nowait((priority_value, seq, entry))
                    if entry.request.priority <= Priority.CRITICAL:
                        self._preempt_normal_for_critical(group)
                    break

            except asyncio.CancelledError:
                return
            except Exception as e:
                logger.error("Dispatcher group=%s error: %s", group.value, e)
                await asyncio.sleep(1)

    async def _resolve_queued(self, backend: str, entry: QueueEntry) -> None:
        wait_time = time.monotonic() - entry.queued_at
        logger.info(
            "QUEUE_DISPATCH: id=%s waited=%.2fs → %s",
            entry.request.request_id, wait_time, backend,
        )
        try:
            result = await self._dispatch_to_backend(backend, entry.request)
            if not entry.future.done():
                entry.future.set_result(result)
        except BaseException as e:
            if not entry.future.done():
                entry.future.set_exception(e)

    # ── Slot finding ─────────────────────────────────────────────────────

    def _find_slot(self, request: TrackedRequest) -> str | None:
        """Find an available GPU backend. Returns "gpu:<name>" or None."""
        is_critical = request.priority <= Priority.CRITICAL
        is_embedding = request.model in EMBEDDING_MODELS

        max_concurrent = self._max_concurrent_embeddings if is_embedding else self._max_concurrent_llm
        min_size = request.min_model_size

        gpu_candidates: list[tuple[GpuBackend, str | None]] = []
        for b in self.gpu_pool.healthy_backends:
            if is_embedding:
                active = sum(1 for r in b.active_requests.values() if r.model in EMBEDDING_MODELS)
            else:
                active = sum(1 for r in b.active_requests.values() if r.model not in EMBEDDING_MODELS)
            if active >= max_concurrent:
                continue
            if b.loading_in_progress:
                continue
            if not is_critical and b.reserved_by:
                continue
            gpu_set = GPU_MODEL_SETS.get(b.name, [])
            if request.model not in gpu_set:
                equiv_model = _find_equivalent_in_set(request.model, gpu_set)
                if equiv_model is None:
                    continue
                if LOCAL_MODEL_SIZE.get(equiv_model, 0) < min_size:
                    continue
                gpu_candidates.append((b, equiv_model))
                continue
            if LOCAL_MODEL_SIZE.get(request.model, 0) < min_size:
                continue
            gpu_candidates.append((b, None))

        if not gpu_candidates:
            return None

        exact = [(b, eq) for b, eq in gpu_candidates if eq is None and b.has_model(request.model)]
        if exact:
            best, _ = self._pick_least_busy_rr_pair(exact)
            return f"gpu:{best.name}"

        equiv = [(b, eq) for b, eq in gpu_candidates if eq is not None and b.has_model(eq)]
        if equiv:
            best, equiv_model = self._pick_least_busy_rr_pair(equiv)
            request.body["model"] = equiv_model
            request.model = equiv_model
            logger.info(
                "MODEL_REDIRECT: %s → %s on GPU %s",
                request.original_model or "?", equiv_model, best.name,
            )
            return f"gpu:{best.name}"

        if gpu_candidates:
            best, _ = self._pick_least_busy_rr_pair(gpu_candidates)
            return f"gpu:{best.name}"

        return None

    def _pick_least_busy_rr_pair(
        self, candidates: list[tuple[GpuBackend, str | None]],
    ) -> tuple[GpuBackend, str | None]:
        min_count = min(b.active_request_count() for b, _ in candidates)
        tied = [(b, eq) for b, eq in candidates if b.active_request_count() == min_count]
        pick = tied[self._rr_counter % len(tied)]
        self._rr_counter += 1
        return pick

    # ── Dispatch ─────────────────────────────────────────────────────────

    async def _dispatch_to_backend(
        self, backend: str, request: TrackedRequest,
    ) -> SubmitResult:
        """Prepare the GPU (ensure model loaded, with retries) and return
        either a streaming generator or a unary dict response.

        For streaming we preserve slot accounting across the whole life of
        the generator — cleanup runs in the generator's `finally`.
        """
        if not backend.startswith("gpu:"):
            raise ProxyError("unknown_backend", message=backend)

        gpu = await self._prepare_gpu(backend, request)

        # At this point the GPU is ready for `request`. Claim the slot.
        request.state = RequestState.RUNNING_GPU
        request.target_gpu = gpu.name
        gpu.active_requests[request.request_id] = request
        gpu.last_activity = time.monotonic()

        if request.priority <= Priority.CRITICAL:
            self._router.notify_critical_activity(gpu.name)

        logger.info(
            "REQUEST_PROXY: id=%s → GPU %s (%s) model=%s priority=%s",
            request.request_id, gpu.name, gpu.url, request.model, request.priority.name,
        )

        streaming = (
            request.api_path not in EMBEDDING_PATHS
            and is_streaming_request(request.body)
        )

        on_err = self._connect_error_handler(gpu)
        if streaming:
            return self._stream_with_cleanup(gpu, backend, request, on_err)
        try:
            result = await proxy_non_streaming(gpu.url, request, on_connect_error=on_err)
            return result
        finally:
            self._cleanup_backend(backend, request)
            self.notify_slot_freed()

    async def _stream_with_cleanup(
        self,
        gpu: GpuBackend,
        backend: str,
        request: TrackedRequest,
        on_err,
    ) -> AsyncIterator[dict]:
        try:
            async for chunk in proxy_streaming(
                gpu.url, request, on_connect_error=on_err,
            ):
                yield chunk
        finally:
            self._cleanup_backend(backend, request)
            self.notify_slot_freed()

    async def _prepare_gpu(self, backend: str, request: TrackedRequest) -> GpuBackend:
        """Ensure the target GPU exists & healthy and the requested model is
        loaded. Retries on transient GPU / load failures.
        """
        gpu_name = backend[4:]
        retry_delays = [5, 15, 30, 60]

        for attempt in range(len(retry_delays) + 1):
            gpu = self.gpu_pool.backends.get(gpu_name)
            if not gpu:
                if attempt < len(retry_delays):
                    logger.warning(
                        "GPU_RETRY: GPU %s not found, retry %d/%d in %ds",
                        gpu_name, attempt + 1, len(retry_delays), retry_delays[attempt],
                    )
                    await asyncio.sleep(retry_delays[attempt])
                    continue
                for fallback in self.gpu_pool.backends.values():
                    if fallback.healthy:
                        gpu = fallback
                        logger.warning(
                            "GPU_FALLBACK: %s not found, using %s", gpu_name, gpu.name,
                        )
                        break
                if not gpu:
                    logger.error(
                        "GPU_EXHAUSTED: no healthy GPU for request %s",
                        request.request_id,
                    )
                    raise ProxyError("all_gpus_exhausted")

            if gpu.has_model(request.model):
                return gpu

            # Ensure VRAM coordination on the VLM GPU.
            if gpu.name == VLM_GPU and request.model not in EMBEDDING_MODELS:
                if self._router.check_whisper_busy():
                    logger.info(
                        "VLM_WAIT_WHISPER: whisper active, waiting before loading %s",
                        request.model,
                    )
                    await self._router.wait_for_whisper_done(
                        timeout=settings.whisper_gpu_acquire_timeout_s,
                    )
                await self._release_whisper_gpu()

            loaded = await self.gpu_pool.load_model(
                gpu, request.model, self._router._mgmt_client,
            )
            if loaded:
                return gpu

            if attempt < len(retry_delays):
                logger.warning(
                    "GPU_RETRY: model load failed on %s, retry %d/%d in %ds",
                    gpu.name, attempt + 1, len(retry_delays), retry_delays[attempt],
                )
                gpu.healthy = False
                self._router._start_gpu_recovery()
                await asyncio.sleep(retry_delays[attempt])
                continue

            logger.error(
                "GPU_EXHAUSTED: model %s failed to load after retries", request.model,
            )
            raise ProxyError(
                "model_load_failed",
                message=f"Failed to load {request.model} after retries",
            )

        raise ProxyError("prepare_exhausted")

    def _connect_error_handler(self, gpu: GpuBackend):
        def on_connect_error(error_msg: str) -> None:
            if gpu.healthy:
                logger.warning(
                    "GPU %s unreachable during request: %s — marking unhealthy, starting recovery",
                    gpu.name, error_msg,
                )
                gpu.healthy = False
                self._router._start_gpu_recovery()
        return on_connect_error

    async def _release_whisper_gpu(self) -> None:
        """Ask whisper REST service to release GPU VRAM (outbound to VD)."""
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
            logger.debug("WHISPER_GPU_RELEASE: not reachable (%s)", e)

    def _cleanup_backend(self, backend: str, request: TrackedRequest) -> None:
        if backend.startswith("gpu:"):
            gpu_name = backend[4:]
            gpu = self.gpu_pool.backends.get(gpu_name)
            if gpu:
                gpu.active_requests.pop(request.request_id, None)

    # ── CRITICAL preemption ──────────────────────────────────────────────

    def _preempt_normal_for_critical(self, group: QueueGroup | None = None) -> bool:
        if group is not None and self._queues[group].empty():
            return False

        for gpu in self.gpu_pool.healthy_backends:
            for req_id, req in list(gpu.active_requests.items()):
                if req.state == RequestState.PREEMPTED:
                    continue
                if req.priority < Priority.NORMAL:
                    continue
                if group is not None and req.queue_group != group:
                    continue
                if req.model in EMBEDDING_MODELS and not settings.preempt_embeddings:
                    continue
                logger.warning(
                    "PREEMPT_FOR_QUEUE: id=%s group=%s model=%s on GPU %s — preempted for queued CRITICAL",
                    req_id, req.queue_group.value, req.model, gpu.name,
                )
                req.cancel_event.set()
                req.state = RequestState.PREEMPTED
                return True
        return False
