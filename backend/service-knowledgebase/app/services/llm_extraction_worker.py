"""Background worker for processing LLM extraction queue.

Continuously polls the queue and processes extraction tasks asynchronously.
Survives restarts by loading pending tasks from PVC on startup.

PRODUCTION-GRADE with:
- Crash recovery (stale tasks reset on startup)
- Retry logic with exponential backoff
- Proper task state transitions
- No task loss on worker crash
"""

import asyncio
import logging
from pathlib import Path
import time
import uuid
import socket

from app.services.llm_extraction_queue import LLMExtractionQueue, ExtractionTask
from app.services.graph_service import GraphService
from app.services.rag_service import RagService
from app import metrics

logger = logging.getLogger(__name__)


class LLMExtractionWorker:
    """Background worker that processes extraction tasks from persistent queue."""

    def __init__(self, queue: LLMExtractionQueue, graph_service: GraphService, rag_service: RagService):
        self.queue = queue
        self.graph_service = graph_service
        self.rag_service = rag_service
        self.running = False
        self._task = None
        # Worker ID for tracking which pod processes which task
        self.worker_id = f"{socket.gethostname()}-{uuid.uuid4().hex[:8]}"

    async def start(self):
        """Start the background worker with crash recovery."""
        if self.running:
            logger.warning("Worker already running")
            return

        # On startup: reset ALL IN_PROGRESS tasks from dead workers (previous pod).
        # Since we run a single worker pod, any IN_PROGRESS task with a different
        # worker_id is guaranteed stale — reset immediately, no time threshold needed.
        recovered = await self.queue.recover_foreign_worker_tasks(self.worker_id)
        if recovered > 0:
            logger.info("Worker %s recovered %d tasks from dead workers on startup", self.worker_id, recovered)

        self.running = True
        self._task = asyncio.create_task(self._worker_loop())
        logger.info("LLM extraction worker %s started", self.worker_id)

    async def stop(self):
        """Stop the background worker gracefully."""
        if not self.running:
            return

        self.running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("LLM extraction worker stopped")

    async def _worker_loop(self):
        """Main worker loop - poll queue and process tasks."""
        logger.info("Worker %s loop started, checking for pending tasks...", self.worker_id)

        # On startup, log queue stats and update gauge
        stats = await self.queue.stats()
        metrics.extraction_queue_depth.set(stats.get("pending", 0))
        if stats["total"] > 0:
            logger.info(
                "Queue stats on startup: total=%d pending=%d in_progress=%d failed=%d",
                stats["total"], stats["pending"], stats["in_progress"], stats["failed"]
            )

        last_stats_log = asyncio.get_event_loop().time()
        last_stale_check = last_stats_log

        while self.running:
            try:
                # Periodic stale recovery — runs always, not just when idle
                now = asyncio.get_event_loop().time()
                if now - last_stale_check > 300:  # every 5 minutes
                    recovered = await self.queue.recover_stale_tasks(stale_threshold_minutes=10)
                    if recovered > 0:
                        logger.info("Periodic recovery: %d stale tasks reset to PENDING", recovered)
                    # Clean up permanently failed tasks
                    await self.queue.delete_failed()
                    last_stale_check = now

                # Claim next PENDING task (marks as IN_PROGRESS)
                task = await self.queue.dequeue(worker_id=self.worker_id, max_attempts=3)

                if task is None:
                    # No PENDING tasks, wait before checking again
                    # Log stats every 5 minutes when idle
                    if now - last_stats_log > 300:
                        stats = await self.queue.stats()
                        metrics.extraction_queue_depth.set(stats.get("pending", 0))
                        if stats["total"] > 0:
                            logger.info(
                                "Queue stats: total=%d pending=%d in_progress=%d failed=%d",
                                stats["total"], stats["pending"], stats["in_progress"], stats["failed"]
                            )
                        last_stats_log = now

                    await asyncio.sleep(5)
                    continue

                # Process the task
                logger.info(
                    "Worker %s processing task %s: source=%s kind=%s priority=%d (attempt %d/3)",
                    self.worker_id,
                    task.task_id,
                    task.source_urn,
                    task.kind,
                    task.priority,
                    task.attempts,
                )

                metrics.extraction_workers_active.inc()
                start_time = time.monotonic()
                try:
                    await self._process_task(task)

                    # Mark as COMPLETED (removes from queue)
                    await self.queue.mark_completed(task.task_id)
                    metrics.extraction_task_total.labels(status="success").inc()
                    logger.info("Worker %s completed task %s", self.worker_id, task.task_id)

                except Exception as e:
                    error_msg = f"{type(e).__name__}: {str(e)}"
                    metrics.extraction_task_total.labels(status="error").inc()
                    logger.error(
                        "Worker %s failed task %s (attempt %d/3): %s",
                        self.worker_id,
                        task.task_id,
                        task.attempts,
                        error_msg,
                        exc_info=True,
                    )

                    # Mark as FAILED (resets to PENDING if attempts < 3, else marks FAILED)
                    await self.queue.mark_failed(task.task_id, error_msg, max_attempts=3)
                finally:
                    metrics.extraction_task_duration.observe(time.monotonic() - start_time)
                    metrics.extraction_workers_active.dec()
                    # Update queue depth after each task
                    stats = await self.queue.stats()
                    metrics.extraction_queue_depth.set(stats.get("pending", 0))

            except asyncio.CancelledError:
                logger.info("Worker %s loop cancelled", self.worker_id)
                break
            except Exception as e:
                logger.error("Error in worker %s loop: %s", self.worker_id, e, exc_info=True)
                await asyncio.sleep(10)  # Back off on error

    async def _process_task(self, task: ExtractionTask):
        """Process a single extraction task by calling graph service LLM extraction."""
        # Build IngestRequest-like object for graph service
        from app.api.models import IngestRequest

        request = IngestRequest(
            sourceUrn=task.source_urn,
            content=task.content,
            kind=task.kind,
            clientId=task.client_id,
            projectId=task.project_id,
        )

        # Progress callback — updates SQLite so UI can show chunk X/Y
        async def on_progress(current: int, total: int):
            await self.queue.update_progress(task.task_id, current, total)

        # Call graph service to do LLM extraction
        # This will take minutes, but we're async so it doesn't block
        nodes, edges, entity_keys = await self.graph_service.ingest(
            request=request,
            chunk_ids=task.chunk_ids,
            embedding_priority=task.priority,
            on_progress=on_progress,
        )

        logger.info(
            "LLM extraction complete for %s: nodes=%d edges=%d entities=%d",
            task.source_urn,
            nodes,
            edges,
            len(entity_keys),
        )

        # Update RAG chunks with discovered entity keys
        if entity_keys and task.chunk_ids:
            for chunk_id in task.chunk_ids:
                try:
                    await self.rag_service.update_chunk_graph_refs(chunk_id, entity_keys)
                except Exception as e:
                    logger.warning("Failed to update chunk %s with entity keys: %s", chunk_id, e)
