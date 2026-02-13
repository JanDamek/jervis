"""Persistent queue for LLM entity extraction tasks.

Stores pending extraction tasks on PVC using SQLite (crash-safe, ACID transactions).
Background worker processes tasks asynchronously without blocking ingest.

PRODUCTION-GRADE with:
- SQLite WAL mode (Write-Ahead Logging) - crash safe, no data loss
- ACID transactions - atomic operations
- Task states (PENDING, IN_PROGRESS, COMPLETED, FAILED)
- Crash recovery (stale IN_PROGRESS tasks reset to PENDING)
- Retry logic with max attempts
- Handles millions of tasks without performance degradation
"""

import asyncio
import json
import logging
import sqlite3
from pathlib import Path
from typing import Optional
from datetime import datetime, timedelta
from enum import Enum

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
    """Persistent queue using SQLite with WAL mode for crash safety.

    Uses SQLite database with:
    - WAL (Write-Ahead Logging) mode - changes written to log first, then DB
    - ACID transactions - atomic operations, rollback on crash
    - Indexes on status + attempts for fast dequeue
    - Automatic recovery of stale IN_PROGRESS tasks
    """

    def __init__(self, queue_dir: Path):
        """Initialize SQLite-backed queue.

        Args:
            queue_dir: Directory where SQLite DB will be stored (on PVC)
        """
        self.queue_dir = Path(queue_dir)
        self.queue_dir.mkdir(parents=True, exist_ok=True)

        self.db_path = self.queue_dir / "extraction_queue.db"
        self.old_json_path = self.queue_dir / "extraction-queue.json"

        self._init_db()
        self._migrate_from_json_if_needed()

        logger.info("Initialized SQLite extraction queue at %s", self.db_path)

    def _init_db(self) -> None:
        """Initialize SQLite database with WAL mode and schema."""
        conn = sqlite3.connect(self.db_path, timeout=30.0)
        try:
            # Enable WAL mode for crash safety and better concurrency
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")  # Balance safety/performance
            conn.execute("PRAGMA busy_timeout=30000")  # 30s timeout for locks

            # Create tasks table
            conn.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    task_id TEXT PRIMARY KEY,
                    source_urn TEXT NOT NULL,
                    content TEXT NOT NULL,
                    client_id TEXT NOT NULL,
                    project_id TEXT,
                    kind TEXT,
                    chunk_ids TEXT NOT NULL,  -- JSON array
                    created_at TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending',
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_attempt_at TEXT,
                    worker_id TEXT,
                    error TEXT
                )
            """)

            # Indexes for fast dequeue and stats
            conn.execute("""
                CREATE INDEX IF NOT EXISTS idx_status_attempts_created
                ON tasks(status, attempts, created_at)
            """)

            conn.execute("""
                CREATE INDEX IF NOT EXISTS idx_last_attempt
                ON tasks(last_attempt_at)
                WHERE status = 'in_progress'
            """)

            conn.commit()
            logger.info("SQLite queue schema initialized with WAL mode")
        finally:
            conn.close()

    def _get_conn(self) -> sqlite3.Connection:
        """Get connection with proper settings."""
        conn = sqlite3.connect(self.db_path, timeout=30.0)
        conn.row_factory = sqlite3.Row
        return conn

    async def enqueue(self, task: ExtractionTask) -> None:
        """Add task to queue (atomic, crash-safe)."""
        conn = self._get_conn()
        try:
            conn.execute("""
                INSERT INTO tasks (
                    task_id, source_urn, content, client_id, project_id, kind,
                    chunk_ids, created_at, status, attempts
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                task.task_id,
                task.source_urn,
                task.content,
                task.client_id,
                task.project_id,
                task.kind,
                json.dumps(task.chunk_ids),
                task.created_at,
                TaskStatus.PENDING,
                0
            ))
            conn.commit()

            # Get queue size for logging
            size = conn.execute("SELECT COUNT(*) FROM tasks").fetchone()[0]
            logger.info("Enqueued extraction task: %s (queue size: %d)", task.task_id, size)
        finally:
            conn.close()

    async def dequeue(self, worker_id: str, max_attempts: int = 3) -> Optional[ExtractionTask]:
        """Claim next PENDING task and mark as IN_PROGRESS (atomic).

        Production-safe: task stays in DB until marked as COMPLETED.
        If worker crashes, task remains IN_PROGRESS and will be recovered.
        """
        conn = self._get_conn()
        try:
            # Atomic claim: SELECT + UPDATE in single transaction
            conn.execute("BEGIN IMMEDIATE")  # Write lock

            # Find first PENDING task with attempts < max
            row = conn.execute("""
                SELECT * FROM tasks
                WHERE status = ? AND attempts < ?
                ORDER BY created_at ASC
                LIMIT 1
            """, (TaskStatus.PENDING, max_attempts)).fetchone()

            if not row:
                conn.rollback()
                return None

            task_id = row["task_id"]
            attempts = row["attempts"] + 1
            now = datetime.utcnow().isoformat()

            # Mark as IN_PROGRESS (atomic)
            conn.execute("""
                UPDATE tasks
                SET status = ?, attempts = ?, last_attempt_at = ?, worker_id = ?
                WHERE task_id = ?
            """, (TaskStatus.IN_PROGRESS, attempts, now, worker_id, task_id))

            conn.commit()

            # Build task object
            task = ExtractionTask(
                task_id=row["task_id"],
                source_urn=row["source_urn"],
                content=row["content"],
                client_id=row["client_id"],
                project_id=row["project_id"],
                kind=row["kind"],
                chunk_ids=json.loads(row["chunk_ids"]),
                created_at=row["created_at"],
                status=TaskStatus.IN_PROGRESS,
                attempts=attempts,
                last_attempt_at=now,
                worker_id=worker_id,
            )

            # Log stats
            pending_count = conn.execute(
                "SELECT COUNT(*) FROM tasks WHERE status = ?", (TaskStatus.PENDING,)
            ).fetchone()[0]

            logger.info(
                "Claimed task %s (attempt %d/%d, %d pending remaining)",
                task_id, attempts, max_attempts, pending_count
            )

            return task
        except sqlite3.Error as e:
            conn.rollback()
            logger.error("Failed to dequeue task: %s", e)
            return None
        finally:
            conn.close()

    async def peek(self) -> Optional[ExtractionTask]:
        """Return next task without removing it."""
        conn = self._get_conn()
        try:
            row = conn.execute("""
                SELECT * FROM tasks
                WHERE status = ?
                ORDER BY created_at ASC
                LIMIT 1
            """, (TaskStatus.PENDING,)).fetchone()

            if not row:
                return None

            return ExtractionTask(
                task_id=row["task_id"],
                source_urn=row["source_urn"],
                content=row["content"],
                client_id=row["client_id"],
                project_id=row["project_id"],
                kind=row["kind"],
                chunk_ids=json.loads(row["chunk_ids"]),
                created_at=row["created_at"],
                status=row["status"],
                attempts=row["attempts"],
                last_attempt_at=row["last_attempt_at"],
                worker_id=row["worker_id"],
                error=row["error"],
            )
        finally:
            conn.close()

    async def size(self) -> int:
        """Get current queue size (all tasks)."""
        conn = self._get_conn()
        try:
            return conn.execute("SELECT COUNT(*) FROM tasks").fetchone()[0]
        finally:
            conn.close()

    async def stats(self) -> dict:
        """Get queue statistics for monitoring."""
        conn = self._get_conn()
        try:
            rows = conn.execute("""
                SELECT status, COUNT(*) as count
                FROM tasks
                GROUP BY status
            """).fetchall()

            stats = {
                "total": 0,
                "pending": 0,
                "in_progress": 0,
                "failed": 0,
            }

            for row in rows:
                status = row["status"]
                count = row["count"]
                stats["total"] += count
                if status == TaskStatus.PENDING:
                    stats["pending"] = count
                elif status == TaskStatus.IN_PROGRESS:
                    stats["in_progress"] = count
                elif status == TaskStatus.FAILED:
                    stats["failed"] = count

            return stats
        finally:
            conn.close()

    async def mark_completed(self, task_id: str) -> bool:
        """Mark task as COMPLETED and remove from queue (success case)."""
        conn = self._get_conn()
        try:
            cursor = conn.execute("DELETE FROM tasks WHERE task_id = ?", (task_id,))
            conn.commit()

            if cursor.rowcount > 0:
                remaining = conn.execute("SELECT COUNT(*) FROM tasks").fetchone()[0]
                logger.info("Task %s completed and removed from queue (remaining: %d)", task_id, remaining)
                return True
            return False
        finally:
            conn.close()

    async def mark_failed(self, task_id: str, error: str, max_attempts: int = 3) -> bool:
        """Mark task as FAILED if max attempts reached, or reset to PENDING for retry."""
        conn = self._get_conn()
        try:
            # Get current attempts
            row = conn.execute(
                "SELECT attempts FROM tasks WHERE task_id = ?", (task_id,)
            ).fetchone()

            if not row:
                return False

            attempts = row["attempts"]

            if attempts >= max_attempts:
                # Max attempts reached - mark as FAILED
                conn.execute("""
                    UPDATE tasks
                    SET status = ?, error = ?
                    WHERE task_id = ?
                """, (TaskStatus.FAILED, error, task_id))
                conn.commit()
                logger.error("Task %s FAILED after %d attempts: %s", task_id, attempts, error)
            else:
                # Reset to PENDING for retry
                conn.execute("""
                    UPDATE tasks
                    SET status = ?, worker_id = NULL, error = ?
                    WHERE task_id = ?
                """, (TaskStatus.PENDING, error, task_id))
                conn.commit()
                logger.warning(
                    "Task %s failed (attempt %d/%d), resetting to PENDING: %s",
                    task_id, attempts, max_attempts, error
                )

            return True
        finally:
            conn.close()

    async def recover_stale_tasks(self, stale_threshold_minutes: int = 30) -> int:
        """Reset stale IN_PROGRESS tasks to PENDING (crash recovery).

        Called on worker startup. Any task IN_PROGRESS for > threshold is considered stale
        (worker crashed) and reset to PENDING for retry.

        Returns: number of tasks recovered.
        """
        conn = self._get_conn()
        try:
            threshold_time = (
                datetime.utcnow() - timedelta(minutes=stale_threshold_minutes)
            ).isoformat()

            # Find stale tasks
            stale_rows = conn.execute("""
                SELECT task_id, worker_id, last_attempt_at
                FROM tasks
                WHERE status = ? AND (
                    last_attempt_at IS NULL OR last_attempt_at < ?
                )
            """, (TaskStatus.IN_PROGRESS, threshold_time)).fetchall()

            if not stale_rows:
                return 0

            # Reset to PENDING
            conn.execute("""
                UPDATE tasks
                SET status = ?, worker_id = NULL
                WHERE status = ? AND (
                    last_attempt_at IS NULL OR last_attempt_at < ?
                )
            """, (TaskStatus.PENDING, TaskStatus.IN_PROGRESS, threshold_time))

            conn.commit()

            recovered = len(stale_rows)
            for row in stale_rows:
                logger.warning(
                    "Recovered stale task %s (worker %s, last attempt: %s)",
                    row["task_id"],
                    row["worker_id"] or "?",
                    row["last_attempt_at"] or "never",
                )

            logger.info("Recovered %d stale IN_PROGRESS tasks to PENDING", recovered)
            return recovered
        finally:
            conn.close()

    def _migrate_from_json_if_needed(self) -> None:
        """Migrate tasks from old JSON file to SQLite (one-time migration).

        TEMPORARY: This migration code will be removed after successful deployment.
        """
        if not self.old_json_path.exists():
            logger.info("No old JSON queue found - skipping migration")
            return

        try:
            # Read old JSON queue
            with open(self.old_json_path, "r") as f:
                old_tasks = json.load(f)

            if not old_tasks:
                logger.info("Old JSON queue is empty - skipping migration")
                self._backup_and_delete_json()
                return

            # Import into SQLite
            conn = self._get_conn()
            try:
                migrated = 0
                skipped = 0

                for task_dict in old_tasks:
                    task_id = task_dict.get("task_id")
                    if not task_id:
                        skipped += 1
                        continue

                    # Check if already exists (migration was partially done)
                    exists = conn.execute(
                        "SELECT 1 FROM tasks WHERE task_id = ?", (task_id,)
                    ).fetchone()

                    if exists:
                        skipped += 1
                        continue

                    # Insert task
                    try:
                        conn.execute("""
                            INSERT INTO tasks (
                                task_id, source_urn, content, client_id, project_id, kind,
                                chunk_ids, created_at, status, attempts, last_attempt_at,
                                worker_id, error
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, (
                            task_dict.get("task_id"),
                            task_dict.get("source_urn"),
                            task_dict.get("content"),
                            task_dict.get("client_id"),
                            task_dict.get("project_id"),
                            task_dict.get("kind"),
                            json.dumps(task_dict.get("chunk_ids", [])),
                            task_dict.get("created_at"),
                            task_dict.get("status", TaskStatus.PENDING),
                            task_dict.get("attempts", 0),
                            task_dict.get("last_attempt_at"),
                            task_dict.get("worker_id"),
                            task_dict.get("error"),
                        ))
                        migrated += 1
                    except sqlite3.IntegrityError:
                        # Duplicate task_id
                        skipped += 1
                        continue

                conn.commit()
                logger.info(
                    "Migration complete: %d tasks migrated, %d skipped from old JSON queue",
                    migrated, skipped
                )

                # Backup and delete old JSON file
                self._backup_and_delete_json()

            finally:
                conn.close()

        except Exception as e:
            logger.error("Failed to migrate from JSON queue: %s", e)
            # Don't fail startup - continue with empty SQLite queue

    def _backup_and_delete_json(self) -> None:
        """Backup old JSON file and delete it."""
        try:
            backup_path = self.old_json_path.with_suffix(".json.backup")
            self.old_json_path.rename(backup_path)
            logger.info("Old JSON queue backed up to %s", backup_path)
        except Exception as e:
            logger.warning("Failed to backup old JSON queue: %s", e)
