"""Persistent queue for LLM entity extraction tasks.

Stores pending extraction tasks on PVC so they survive pod restarts.
Background worker processes tasks asynchronously without blocking ingest.

PRODUCTION-GRADE with:
- Task states (PENDING, IN_PROGRESS, COMPLETED, FAILED)
- Crash recovery (stale IN_PROGRESS tasks reset to PENDING)
- Retry logic with max attempts
- No task loss on worker crash
"""

import asyncio
import json
import logging
from pathlib import Path
from typing import Optional
from datetime import datetime, timedelta
from enum import Enum
import filelock

logger = logging.getLogger(__name__)


class TaskStatus(str, Enum):
    """Task lifecycle states."""
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"


class ExtractionTask:
    """Single LLM extraction task with state tracking."""

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
        status: str = TaskStatus.PENDING,
        attempts: int = 0,
        last_attempt_at: Optional[str] = None,
        worker_id: Optional[str] = None,
        error: Optional[str] = None,
    ):
        self.task_id = task_id
        self.source_urn = source_urn
        self.content = content
        self.client_id = client_id
        self.project_id = project_id
        self.kind = kind
        self.chunk_ids = chunk_ids
        self.created_at = created_at
        self.status = status
        self.attempts = attempts
        self.last_attempt_at = last_attempt_at
        self.worker_id = worker_id
        self.error = error

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
            "status": self.status,
            "attempts": self.attempts,
            "last_attempt_at": self.last_attempt_at,
            "worker_id": self.worker_id,
            "error": self.error,
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
            try:
                self._write_queue([])
                logger.info("Initialized empty extraction queue at %s", queue_file)
            except FileExistsError:
                # Another pod created it simultaneously - that's fine
                logger.info("Queue file already exists (created by another pod)")
            except Exception as e:
                logger.warning("Failed to initialize queue file (may already exist): %s", e)

    def _write_queue(self, tasks: list[dict]) -> None:
        """Write queue to disk with atomic operation."""
        import tempfile
        import os

        # Use atomic write with rename (works across processes/pods)
        fd, temp_path = tempfile.mkstemp(
            dir=self.queue_file.parent,
            prefix=".queue-",
            suffix=".tmp",
        )
        try:
            with os.fdopen(fd, "w") as f:
                json.dump(tasks, f, indent=2)
            os.replace(temp_path, self.queue_file)
        except Exception:
            # Clean up temp file on error
            try:
                os.unlink(temp_path)
            except Exception:
                pass
            raise

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

    async def dequeue(self, worker_id: str, max_attempts: int = 3) -> Optional[ExtractionTask]:
        """Claim next PENDING task and mark as IN_PROGRESS (no removal yet).

        Production-safe: task stays in queue until marked as COMPLETED.
        If worker crashes, task remains IN_PROGRESS and will be recovered.
        """
        lock = filelock.FileLock(self.lock_file, timeout=10)

        try:
            with lock:
                tasks = self._read_queue()

                # Find first PENDING task with attempts < max
                task_dict = None
                for t in tasks:
                    if t.get("status") == TaskStatus.PENDING and t.get("attempts", 0) < max_attempts:
                        task_dict = t
                        break

                if not task_dict:
                    return None

                # Mark as IN_PROGRESS (don't remove - stays in queue)
                task_dict["status"] = TaskStatus.IN_PROGRESS
                task_dict["attempts"] = task_dict.get("attempts", 0) + 1
                task_dict["last_attempt_at"] = datetime.utcnow().isoformat()
                task_dict["worker_id"] = worker_id

                self._write_queue(tasks)

                task = ExtractionTask.from_dict(task_dict)
                pending_count = sum(1 for t in tasks if t.get("status") == TaskStatus.PENDING)
                logger.info(
                    "Claimed task %s (attempt %d/%d, %d pending remaining)",
                    task.task_id, task.attempts, max_attempts, pending_count
                )
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
        """Get current queue size (all tasks)."""
        tasks = self._read_queue()
        return len(tasks)

    async def stats(self) -> dict:
        """Get queue statistics for monitoring."""
        tasks = self._read_queue()

        stats = {
            "total": len(tasks),
            "pending": 0,
            "in_progress": 0,
            "failed": 0,
        }

        for task in tasks:
            status = task.get("status", TaskStatus.PENDING)
            if status == TaskStatus.PENDING:
                stats["pending"] += 1
            elif status == TaskStatus.IN_PROGRESS:
                stats["in_progress"] += 1
            elif status == TaskStatus.FAILED:
                stats["failed"] += 1

        return stats

    async def mark_completed(self, task_id: str) -> bool:
        """Mark task as COMPLETED and remove from queue (success case)."""
        lock = filelock.FileLock(self.lock_file, timeout=10)

        try:
            with lock:
                tasks = self._read_queue()
                original_size = len(tasks)
                # Remove completed tasks from queue
                tasks = [t for t in tasks if t["task_id"] != task_id]

                if len(tasks) < original_size:
                    self._write_queue(tasks)
                    logger.info("Task %s completed and removed from queue (remaining: %d)", task_id, len(tasks))
                    return True
                return False
        except filelock.Timeout:
            logger.error("Failed to acquire lock for mark_completed after 10s timeout")
            return False

    async def mark_failed(self, task_id: str, error: str, max_attempts: int = 3) -> bool:
        """Mark task as FAILED if max attempts reached, or reset to PENDING for retry."""
        lock = filelock.FileLock(self.lock_file, timeout=10)

        try:
            with lock:
                tasks = self._read_queue()
                task_dict = None

                for t in tasks:
                    if t["task_id"] == task_id:
                        task_dict = t
                        break

                if not task_dict:
                    return False

                attempts = task_dict.get("attempts", 0)

                if attempts >= max_attempts:
                    # Max attempts reached - mark as FAILED and keep in queue for visibility
                    task_dict["status"] = TaskStatus.FAILED
                    task_dict["error"] = error
                    logger.error(
                        "Task %s FAILED after %d attempts: %s",
                        task_id, attempts, error
                    )
                else:
                    # Reset to PENDING for retry
                    task_dict["status"] = TaskStatus.PENDING
                    task_dict["worker_id"] = None
                    task_dict["error"] = error
                    logger.warning(
                        "Task %s failed (attempt %d/%d), resetting to PENDING: %s",
                        task_id, attempts, max_attempts, error
                    )

                self._write_queue(tasks)
                return True
        except filelock.Timeout:
            logger.error("Failed to acquire lock for mark_failed after 10s timeout")
            return False

    async def recover_stale_tasks(self, stale_threshold_minutes: int = 30) -> int:
        """Reset stale IN_PROGRESS tasks to PENDING (crash recovery).

        Called on worker startup. Any task IN_PROGRESS for > threshold is considered stale
        (worker crashed) and reset to PENDING for retry.

        Returns: number of tasks recovered.
        """
        lock = filelock.FileLock(self.lock_file, timeout=10)

        try:
            with lock:
                tasks = self._read_queue()
                recovered = 0
                now = datetime.utcnow()
                threshold = timedelta(minutes=stale_threshold_minutes)

                for task_dict in tasks:
                    if task_dict.get("status") != TaskStatus.IN_PROGRESS:
                        continue

                    last_attempt = task_dict.get("last_attempt_at")
                    if not last_attempt:
                        # No timestamp - reset it
                        task_dict["status"] = TaskStatus.PENDING
                        task_dict["worker_id"] = None
                        recovered += 1
                        continue

                    try:
                        last_attempt_dt = datetime.fromisoformat(last_attempt)
                        age = now - last_attempt_dt

                        if age > threshold:
                            # Stale - reset to PENDING
                            task_dict["status"] = TaskStatus.PENDING
                            task_dict["worker_id"] = None
                            recovered += 1
                            logger.warning(
                                "Recovered stale task %s (worker %s, age: %s)",
                                task_dict["task_id"],
                                task_dict.get("worker_id", "?"),
                                age,
                            )
                    except (ValueError, TypeError):
                        # Invalid timestamp - reset
                        task_dict["status"] = TaskStatus.PENDING
                        task_dict["worker_id"] = None
                        recovered += 1

                if recovered > 0:
                    self._write_queue(tasks)
                    logger.info("Recovered %d stale IN_PROGRESS tasks to PENDING", recovered)

                return recovered
        except filelock.Timeout:
            logger.error("Failed to acquire lock for recover_stale_tasks after 10s timeout")
            return 0
