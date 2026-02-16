"""Local Quick Memory (LQM) — in-process RAM cache with write-through to KB.

Three layers:
  Layer 1 (Hot):   dict[str, Affair]     — active + parked affairs
  Layer 2 (Buffer): asyncio.Queue         — pending KB writes
  Layer 3 (Warm):  LRUCache              — recent KB search results, TTL-based

Layer 4 (Cold, mmap) is deferred.

Not thread-safe — designed for single-threaded asyncio event loop.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import OrderedDict
from typing import Any

from app.memory.models import Affair, AffairStatus, PendingWrite

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# LRU Cache with TTL
# ---------------------------------------------------------------------------


class TTLEntry:
    """Value wrapper with monotonic expiration timestamp."""

    __slots__ = ("value", "expires_at")

    def __init__(self, value: Any, ttl_seconds: float) -> None:
        self.value = value
        self.expires_at = time.monotonic() + ttl_seconds


class LRUCache:
    """Simple LRU cache backed by OrderedDict with per-entry TTL.

    No external dependencies — uses stdlib only.
    """

    def __init__(self, max_size: int = 1000, default_ttl: float = 300.0) -> None:
        self._data: OrderedDict[str, TTLEntry] = OrderedDict()
        self._max_size = max_size
        self._default_ttl = default_ttl

    def get(self, key: str) -> Any | None:
        entry = self._data.get(key)
        if entry is None:
            return None
        if time.monotonic() > entry.expires_at:
            del self._data[key]
            return None
        # Move to end (most recently used)
        self._data.move_to_end(key)
        return entry.value

    def put(self, key: str, value: Any, ttl_seconds: float | None = None) -> None:
        ttl = ttl_seconds if ttl_seconds is not None else self._default_ttl
        if key in self._data:
            self._data.move_to_end(key)
            self._data[key] = TTLEntry(value, ttl)
        else:
            if len(self._data) >= self._max_size:
                # Evict least recently used
                self._data.popitem(last=False)
            self._data[key] = TTLEntry(value, ttl)

    def invalidate(self, key: str) -> None:
        self._data.pop(key, None)

    def clear(self) -> None:
        self._data.clear()

    def __len__(self) -> int:
        return len(self._data)

    def __contains__(self, key: str) -> bool:
        entry = self._data.get(key)
        if entry is None:
            return False
        if time.monotonic() > entry.expires_at:
            del self._data[key]
            return False
        return True


# ---------------------------------------------------------------------------
# Local Quick Memory
# ---------------------------------------------------------------------------


class LocalQuickMemory:
    """In-process memory with write-through to KB.

    Layers:
      1. Hot cache (dict)       — active + parked affairs, keyed by affair.id
      2. Write buffer (Queue)   — pending KB writes, drained on flush
      3. Warm cache (LRUCache)  — recent KB search results, auto-TTL eviction
    """

    def __init__(
        self,
        max_warm_entries: int = 1000,
        warm_ttl: float = 300.0,
        write_buffer_max: int = 500,
    ) -> None:
        # Layer 1: Hot affairs
        self._affairs: dict[str, Affair] = {}

        # Layer 2: Write buffer
        self._write_buffer: asyncio.Queue[PendingWrite] = asyncio.Queue(
            maxsize=write_buffer_max,
        )
        self._kb_synced: set[str] = set()  # source_urns confirmed written to KB

        # Layer 3: Warm search cache
        self._search_cache = LRUCache(
            max_size=max_warm_entries, default_ttl=warm_ttl,
        )

        # Metrics
        self._stats = {
            "affair_stores": 0,
            "affair_loads": 0,
            "cache_hits": 0,
            "cache_misses": 0,
            "buffer_writes": 0,
            "buffer_flushes": 0,
        }

    # ----- Layer 1: Affair operations (Hot cache) -----

    def store_affair(self, affair: Affair) -> None:
        """Store or update an affair in the hot cache."""
        self._affairs[affair.id] = affair
        self._stats["affair_stores"] += 1

    def get_affair(self, affair_id: str) -> Affair | None:
        self._stats["affair_loads"] += 1
        return self._affairs.get(affair_id)

    def get_active_affair(self, client_id: str) -> Affair | None:
        """Return the ACTIVE affair for a given client, if any."""
        for affair in self._affairs.values():
            if affair.client_id == client_id and affair.status == AffairStatus.ACTIVE:
                return affair
        return None

    def get_parked_affairs(self, client_id: str) -> list[Affair]:
        """Return all PARKED affairs for a given client."""
        return [
            a for a in self._affairs.values()
            if a.client_id == client_id and a.status == AffairStatus.PARKED
        ]

    def get_all_affairs(self, client_id: str) -> list[Affair]:
        """Return all affairs (any status) for a given client."""
        return [
            a for a in self._affairs.values()
            if a.client_id == client_id
        ]

    def remove_affair(self, affair_id: str) -> None:
        self._affairs.pop(affair_id, None)

    # ----- Layer 2: Write buffer -----

    async def buffer_write(self, write: PendingWrite) -> None:
        """Add a pending KB write to the buffer.

        If buffer is full, drops the oldest entry (logs warning).
        """
        if self._write_buffer.full():
            # Drop oldest to make room
            try:
                dropped = self._write_buffer.get_nowait()
                logger.warning(
                    "LQM write buffer full, dropping oldest: %s",
                    dropped.source_urn,
                )
            except asyncio.QueueEmpty:
                pass
        await self._write_buffer.put(write)
        self._stats["buffer_writes"] += 1

    def search_write_buffer(self, query: str) -> list[dict]:
        """Keyword match on buffered writes not yet flushed to KB.

        Simple case-insensitive substring match — not semantic,
        but catches exact matches on recently stored data.
        """
        results = []
        query_lower = query.lower()
        # Peek at queue items without consuming them
        items = list(self._write_buffer._queue)
        for item in items:
            searchable = f"{item.source_urn} {item.content}".lower()
            if query_lower in searchable:
                results.append({
                    "content": item.content[:1000],
                    "source_urn": item.source_urn,
                    "kind": item.kind,
                    "source": "write_buffer",
                    "confidence": 0.95,
                })
        return results

    async def drain_write_buffer(self) -> list[PendingWrite]:
        """Drain all pending writes from the buffer."""
        writes: list[PendingWrite] = []
        while not self._write_buffer.empty():
            try:
                writes.append(self._write_buffer.get_nowait())
            except asyncio.QueueEmpty:
                break
        if writes:
            self._stats["buffer_flushes"] += 1
        return writes

    def mark_synced(self, source_urn: str) -> None:
        """Mark a source_urn as confirmed written to KB."""
        self._kb_synced.add(source_urn)

    def is_synced(self, source_urn: str) -> bool:
        return source_urn in self._kb_synced

    # ----- Layer 3: Search cache (Warm) -----

    def cache_search(self, query: str, results: list[dict]) -> None:
        """Cache KB search results for a query."""
        self._search_cache.put(query, results)

    def get_cached_search(self, query: str) -> list[dict] | None:
        """Return cached search results, or None on miss."""
        result = self._search_cache.get(query)
        if result is not None:
            self._stats["cache_hits"] += 1
        else:
            self._stats["cache_misses"] += 1
        return result

    def invalidate_search(self, query: str) -> None:
        self._search_cache.invalidate(query)

    # ----- Lifecycle -----

    def get_stats(self) -> dict:
        return {
            **self._stats,
            "affairs_count": len(self._affairs),
            "buffer_size": self._write_buffer.qsize(),
            "cache_size": len(self._search_cache),
        }

    def clear(self) -> None:
        """Clear all layers. Used on shutdown."""
        self._affairs.clear()
        # Drain buffer
        while not self._write_buffer.empty():
            try:
                self._write_buffer.get_nowait()
            except asyncio.QueueEmpty:
                break
        self._kb_synced.clear()
        self._search_cache.clear()
        self._stats = {k: 0 for k in self._stats}
