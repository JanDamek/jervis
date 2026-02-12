"""Background worker for processing LLM extraction queue.

Continuously polls the queue and processes extraction tasks asynchronously.
Survives restarts by loading pending tasks from PVC on startup.
"""

import asyncio
import logging
from pathlib import Path

from app.services.llm_extraction_queue import LLMExtractionQueue, ExtractionTask
from app.services.graph_service import GraphService
from app.services.rag_service import RagService

logger = logging.getLogger(__name__)


class LLMExtractionWorker:
    """Background worker that processes extraction tasks from persistent queue."""

    def __init__(self, queue: LLMExtractionQueue, graph_service: GraphService, rag_service: RagService):
        self.queue = queue
        self.graph_service = graph_service
        self.rag_service = rag_service
        self.running = False
        self._task = None

    async def start(self):
        """Start the background worker."""
        if self.running:
            logger.warning("Worker already running")
            return

        self.running = True
        self._task = asyncio.create_task(self._worker_loop())
        logger.info("LLM extraction worker started")

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
        logger.info("Worker loop started, checking for pending tasks...")

        # On startup, log queue size
        initial_size = await self.queue.size()
        if initial_size > 0:
            logger.info("Found %d pending extraction tasks from previous session", initial_size)

        while self.running:
            try:
                # Check for next task
                task = await self.queue.dequeue()

                if task is None:
                    # Queue empty, wait before checking again
                    await asyncio.sleep(5)
                    continue

                # Process the task
                logger.info(
                    "Processing extraction task: source=%s kind=%s content_len=%d chunks=%d",
                    task.source_urn,
                    task.kind,
                    len(task.content),
                    len(task.chunk_ids),
                )

                try:
                    await self._process_task(task)
                    logger.info("Successfully processed extraction task: %s", task.task_id)
                except Exception as e:
                    logger.error(
                        "Failed to process extraction task %s: %s",
                        task.task_id,
                        e,
                        exc_info=True,
                    )
                    # Re-queue failed task? For now, just log and continue
                    # TODO: Implement retry logic with max attempts

            except asyncio.CancelledError:
                logger.info("Worker loop cancelled")
                break
            except Exception as e:
                logger.error("Error in worker loop: %s", e, exc_info=True)
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

        # Call graph service to do LLM extraction
        # This will take minutes, but we're async so it doesn't block
        nodes, edges, entity_keys = await self.graph_service.ingest(
            request=request,
            chunk_ids=task.chunk_ids,
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
