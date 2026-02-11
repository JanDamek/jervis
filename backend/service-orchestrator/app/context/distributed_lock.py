"""MongoDB distributed lock for multi-pod orchestrator concurrency.

Replaces asyncio.Semaphore for multi-pod deployments.
Only ONE orchestration can run across ALL pods at a time
(LLM/Ollama can't handle concurrent requests).

Uses MongoDB findOneAndUpdate with atomic conditions for lock acquisition.
Heartbeat mechanism prevents stale locks from blocking new orchestrations.

Collection: orchestrator_locks
Document: single document with _id="orchestration_slot"
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from contextlib import asynccontextmanager

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase

from app.config import settings

logger = logging.getLogger(__name__)

# Unique pod identifier
_POD_ID = os.environ.get("HOSTNAME", f"local-{os.getpid()}")

# Lock configuration
HEARTBEAT_INTERVAL_S = 10      # Update lock heartbeat every 10s
STALE_LOCK_TIMEOUT_S = 300     # Consider lock stale after 5 min without heartbeat
LOCK_COLLECTION = "orchestrator_locks"
LOCK_DOC_ID = "orchestration_slot"


class DistributedLock:
    """MongoDB-based distributed lock for single-orchestration concurrency.

    Usage:
        lock = DistributedLock()
        await lock.init()

        async with lock.acquire(thread_id="thread-123"):
            # Only one pod can be here at a time
            await run_orchestration(...)

        await lock.close()
    """

    def __init__(self):
        self._client: AsyncIOMotorClient | None = None
        self._db: AsyncIOMotorDatabase | None = None
        self._heartbeat_task: asyncio.Task | None = None
        self._current_thread_id: str | None = None

    async def init(self):
        """Initialize MongoDB connection and ensure lock document exists."""
        self._client = AsyncIOMotorClient(settings.mongodb_url)
        self._db = self._client.jervis_orchestrator

        coll = self._db[LOCK_COLLECTION]

        # Ensure the lock document exists (upsert)
        await coll.update_one(
            {"_id": LOCK_DOC_ID},
            {"$setOnInsert": {
                "locked_by": None,
                "thread_id": None,
                "locked_at": None,
            }},
            upsert=True,
        )

        # Clean up any stale locks on startup
        await self._recover_stale_locks()

        logger.info("Distributed lock initialized (pod=%s)", _POD_ID)

    async def close(self):
        """Release any held lock and close connection."""
        if self._heartbeat_task and not self._heartbeat_task.done():
            self._heartbeat_task.cancel()
        await self._release()
        if self._client:
            self._client.close()
        logger.info("Distributed lock closed")

    @asynccontextmanager
    async def acquire(self, thread_id: str):
        """Context manager: acquire lock, run body, release lock.

        Raises RuntimeError if lock cannot be acquired (another pod holds it).
        """
        acquired = await self._try_acquire(thread_id)
        if not acquired:
            raise RuntimeError("Orchestrator busy — another pod holds the lock")

        self._current_thread_id = thread_id
        self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())

        try:
            yield
        finally:
            if self._heartbeat_task and not self._heartbeat_task.done():
                self._heartbeat_task.cancel()
            self._heartbeat_task = None
            self._current_thread_id = None
            await self._release()

    async def is_locked(self) -> bool:
        """Check if the orchestration slot is currently locked."""
        if self._db is None:
            return False
        coll = self._db[LOCK_COLLECTION]
        doc = await coll.find_one({"_id": LOCK_DOC_ID})
        if doc is None:
            return False
        return doc.get("locked_by") is not None

    async def get_lock_info(self) -> dict | None:
        """Get current lock holder info (for /health endpoint)."""
        if self._db is None:
            return None
        coll = self._db[LOCK_COLLECTION]
        doc = await coll.find_one({"_id": LOCK_DOC_ID})
        if doc is None or doc.get("locked_by") is None:
            return None
        return {
            "locked_by": doc["locked_by"],
            "thread_id": doc.get("thread_id"),
            "locked_at": doc.get("locked_at"),
        }

    async def _try_acquire(self, thread_id: str) -> bool:
        """Atomically try to acquire the lock."""
        coll = self._db[LOCK_COLLECTION]
        now = time.time()

        # Try to acquire: only succeeds if locked_by is None
        result = await coll.find_one_and_update(
            {"_id": LOCK_DOC_ID, "locked_by": None},
            {"$set": {
                "locked_by": _POD_ID,
                "thread_id": thread_id,
                "locked_at": now,
            }},
        )

        if result is not None:
            logger.info("Lock acquired: pod=%s thread=%s", _POD_ID, thread_id)
            return True

        # Lock is held — check if stale
        doc = await coll.find_one({"_id": LOCK_DOC_ID})
        if doc and doc.get("locked_at"):
            age = now - doc["locked_at"]
            if age > STALE_LOCK_TIMEOUT_S:
                # Stale lock — force acquire
                result = await coll.find_one_and_update(
                    {"_id": LOCK_DOC_ID, "locked_by": doc["locked_by"]},
                    {"$set": {
                        "locked_by": _POD_ID,
                        "thread_id": thread_id,
                        "locked_at": now,
                    }},
                )
                if result is not None:
                    logger.warning(
                        "Stale lock recovered: was held by %s for %.0fs, now %s",
                        doc["locked_by"], age, _POD_ID,
                    )
                    return True

        logger.info(
            "Lock not acquired: held by %s (thread=%s)",
            doc.get("locked_by") if doc else "unknown",
            doc.get("thread_id") if doc else "unknown",
        )
        return False

    async def _release(self):
        """Release the lock (only if we hold it)."""
        if self._db is None:
            return
        coll = self._db[LOCK_COLLECTION]
        result = await coll.find_one_and_update(
            {"_id": LOCK_DOC_ID, "locked_by": _POD_ID},
            {"$set": {
                "locked_by": None,
                "thread_id": None,
                "locked_at": None,
            }},
        )
        if result is not None:
            logger.info("Lock released: pod=%s", _POD_ID)

    async def _heartbeat_loop(self):
        """Periodically update locked_at to prevent stale lock recovery."""
        try:
            while True:
                await asyncio.sleep(HEARTBEAT_INTERVAL_S)
                if self._db is None:
                    break
                coll = self._db[LOCK_COLLECTION]
                await coll.update_one(
                    {"_id": LOCK_DOC_ID, "locked_by": _POD_ID},
                    {"$set": {"locked_at": time.time()}},
                )
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.warning("Heartbeat loop error: %s", e)

    async def _recover_stale_locks(self):
        """Clean up stale locks on startup (from crashed pods)."""
        if self._db is None:
            return
        coll = self._db[LOCK_COLLECTION]
        doc = await coll.find_one({"_id": LOCK_DOC_ID})
        if doc and doc.get("locked_at"):
            age = time.time() - doc["locked_at"]
            if age > STALE_LOCK_TIMEOUT_S:
                await coll.update_one(
                    {"_id": LOCK_DOC_ID},
                    {"$set": {
                        "locked_by": None,
                        "thread_id": None,
                        "locked_at": None,
                    }},
                )
                logger.warning(
                    "Recovered stale lock on startup: was held by %s for %.0fs",
                    doc.get("locked_by"), age,
                )


# Singleton instance
distributed_lock = DistributedLock()
