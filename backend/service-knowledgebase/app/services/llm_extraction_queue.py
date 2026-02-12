"""Persistent queue for LLM entity extraction tasks.

Stores pending extraction tasks on PVC so they survive pod restarts.
Background worker processes tasks asynchronously without blocking ingest.
"""

import asyncio
import json
import logging
from pathlib import Path
from typing import Optional
from datetime import datetime
import filelock

logger = logging.getLogger(__name__)


class ExtractionTask:
    """Single LLM extraction task."""

    def __init__(
        self,
        task_id: str,
        source_urn: str,
        content: str,
        client_id: str,
        project_id: Optional[str],
        kind: Optional[str],
        chunk_ids: list[str],
        created_at: str,
    ):
        self.task_id = task_id
        self.source_urn = source_urn
        self.content = content
        self.client_id = client_id
        self.project_id = project_id
        self.kind = kind
        self.chunk_ids = chunk_ids
        self.created_at = created_at

    def to_dict(self) -> dict:
        return {
            "task_id": self.task_id,
            "source_urn": self.source_urn,
            "content": self.content,
            "client_id": self.client_id,
            "project_id": self.project_id,
            "kind": self.kind,
            "chunk_ids": self.chunk_ids,
            "created_at": self.created_at,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "ExtractionTask":
        return cls(**data)


class LLMExtractionQueue:
    """Persistent queue for LLM extraction tasks using PVC-backed JSON file."""

    def __init__(self, queue_file: Path):
        self.queue_file = queue_file
        self.lock_file = queue_file.with_suffix(".lock")

        # Ensure directory exists
        queue_file.parent.mkdir(parents=True, exist_ok=True)

        # Initialize empty queue if file doesn't exist
        if not queue_file.exists():
            self._write_queue([])
            logger.info("Initialized empty extraction queue at %s", queue_file)

    def _write_queue(self, tasks: list[dict]) -> None:
        """Write queue to disk with atomic operation."""
        temp_file = self.queue_file.with_suffix(".tmp")
        with open(temp_file, "w") as f:
            json.dump(tasks, f, indent=2)
        temp_file.replace(self.queue_file)

    def _read_queue(self) -> list[dict]:
        """Read queue from disk."""
        try:
            with open(self.queue_file, "r") as f:
                return json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            return []

    async def enqueue(self, task: ExtractionTask) -> None:
        """Add task to queue (async-safe with file lock)."""
        lock = filelock.FileLock(self.lock_file, timeout=10)

        try:
            with lock:
                tasks = self._read_queue()
                tasks.append(task.to_dict())
                self._write_queue(tasks)
                logger.info("Enqueued extraction task: %s (queue size: %d)", task.task_id, len(tasks))
        except filelock.Timeout:
            logger.error("Failed to acquire lock for enqueue after 10s timeout")
            raise

    async def dequeue(self) -> Optional[ExtractionTask]:
        """Remove and return next task from queue."""
        lock = filelock.FileLock(self.lock_file, timeout=10)

        try:
            with lock:
                tasks = self._read_queue()
                if not tasks:
                    return None

                task_dict = tasks.pop(0)
                self._write_queue(tasks)

                task = ExtractionTask.from_dict(task_dict)
                logger.info("Dequeued extraction task: %s (remaining: %d)", task.task_id, len(tasks))
                return task
        except filelock.Timeout:
            logger.error("Failed to acquire lock for dequeue after 10s timeout")
            return None

    async def peek(self) -> Optional[ExtractionTask]:
        """Return next task without removing it."""
        tasks = self._read_queue()
        if not tasks:
            return None
        return ExtractionTask.from_dict(tasks[0])

    async def size(self) -> int:
        """Get current queue size."""
        tasks = self._read_queue()
        return len(tasks)

    async def remove_task(self, task_id: str) -> bool:
        """Remove specific task by ID (e.g., after successful processing)."""
        lock = filelock.FileLock(self.lock_file, timeout=10)

        try:
            with lock:
                tasks = self._read_queue()
                original_size = len(tasks)
                tasks = [t for t in tasks if t["task_id"] != task_id]

                if len(tasks) < original_size:
                    self._write_queue(tasks)
                    logger.info("Removed task %s from queue (remaining: %d)", task_id, len(tasks))
                    return True
                return False
        except filelock.Timeout:
            logger.error("Failed to acquire lock for remove_task after 10s timeout")
            return False
