"""Proactive rate limiter for OpenRouter API calls.

OpenRouter enforces account-level rate limits:
- Free models: 20 RPM (requests per minute), account-wide (NOT per model)
- Paid models: RPS = account balance in USD (min 1, max 500)

Instead of hitting the limit and reacting to 429s, the router proactively
throttles requests using a sliding window. When at capacity, decide_route()
waits (asyncio.sleep) until a slot opens — requests are delayed, not rejected.

This eliminates:
- Unnecessary 429 errors from OpenRouter
- Exponential backoff disabling models for hours
- Wasted retries on rate-limited requests
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import deque

logger = logging.getLogger("ollama-router.rate-limiter")


class SlidingWindowRateLimiter:
    """Sliding window rate limiter with async wait support.

    Tracks request timestamps in a deque. When limit is reached,
    callers wait until the oldest request falls outside the window.
    """

    def __init__(self, max_requests: int, window_seconds: float = 60.0, name: str = ""):
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self.name = name
        self._timestamps: deque[float] = deque()
        self._lock = asyncio.Lock()
        # Stats
        self.total_requests = 0
        self.total_waits = 0
        self.total_wait_time = 0.0

    def _cleanup(self, now: float) -> None:
        """Remove timestamps outside the sliding window."""
        cutoff = now - self.window_seconds
        while self._timestamps and self._timestamps[0] < cutoff:
            self._timestamps.popleft()

    async def acquire(self, timeout: float = 65.0) -> bool:
        """Wait until a rate limit slot is available.

        Args:
            timeout: Maximum time to wait in seconds. Default 65s (window + 5s margin).

        Returns:
            True if slot acquired, False if timeout exceeded.
        """
        deadline = time.monotonic() + timeout
        waited = False
        wait_start = time.monotonic()

        while True:
            async with self._lock:
                now = time.monotonic()
                self._cleanup(now)

                if len(self._timestamps) < self.max_requests:
                    # Slot available — record and proceed
                    self._timestamps.append(now)
                    self.total_requests += 1
                    if waited:
                        wait_duration = time.monotonic() - wait_start
                        self.total_waits += 1
                        self.total_wait_time += wait_duration
                        logger.info(
                            "RATE_LIMIT %s | waited %.1fs for slot (%d/%d used)",
                            self.name, wait_duration, len(self._timestamps),
                            self.max_requests,
                        )
                    return True

                # At capacity — calculate how long until oldest request expires
                wait_time = self._timestamps[0] + self.window_seconds - now + 0.05
                remaining_budget = deadline - now

                if wait_time > remaining_budget:
                    logger.warning(
                        "RATE_LIMIT %s | timeout — need to wait %.1fs but only %.1fs budget left",
                        self.name, wait_time, remaining_budget,
                    )
                    return False

            # Sleep outside the lock so other coroutines can check
            if not waited:
                waited = True
                wait_start = time.monotonic()
                logger.info(
                    "RATE_LIMIT %s | at capacity (%d/%d), waiting %.1fs for next slot",
                    self.name, self.max_requests, self.max_requests, wait_time,
                )

            # Sleep in smaller chunks to be responsive
            await asyncio.sleep(min(wait_time, 3.0))

    @property
    def current_usage(self) -> int:
        """Current number of requests in the sliding window."""
        now = time.monotonic()
        cutoff = now - self.window_seconds
        # Count without lock (approximate, for monitoring only)
        return sum(1 for ts in self._timestamps if ts >= cutoff)

    @property
    def available_slots(self) -> int:
        """Number of available slots right now."""
        return max(0, self.max_requests - self.current_usage)

    def status(self) -> dict:
        """Return current rate limiter status for monitoring."""
        now = time.monotonic()
        self._cleanup(now)
        used = len(self._timestamps)
        next_slot_in = 0.0
        if used >= self.max_requests and self._timestamps:
            next_slot_in = max(0, self._timestamps[0] + self.window_seconds - now)
        return {
            "name": self.name,
            "limit": self.max_requests,
            "window_seconds": self.window_seconds,
            "used": used,
            "available": max(0, self.max_requests - used),
            "next_slot_in_seconds": round(next_slot_in, 1),
            "stats": {
                "total_requests": self.total_requests,
                "total_waits": self.total_waits,
                "total_wait_time_seconds": round(self.total_wait_time, 1),
                "avg_wait_seconds": round(self.total_wait_time / self.total_waits, 1) if self.total_waits > 0 else 0,
            },
        }


# ── Global rate limiter instances ──────────────────────────────────────

# OpenRouter account-level limits (configurable via update_limits())
_free_limiter = SlidingWindowRateLimiter(max_requests=20, window_seconds=60.0, name="free-models")
_paid_limiter = SlidingWindowRateLimiter(max_requests=200, window_seconds=60.0, name="paid-models")


async def acquire_openrouter_slot(queue_name: str, timeout: float = 65.0) -> bool:
    """Acquire a rate limit slot for an OpenRouter request.

    Args:
        queue_name: "FREE", "PAID", or "PREMIUM"
        timeout: Max wait time in seconds

    Returns:
        True if slot acquired (may have waited), False if timeout.
    """
    if queue_name == "FREE":
        return await _free_limiter.acquire(timeout=timeout)
    else:
        return await _paid_limiter.acquire(timeout=timeout)


def get_rate_limit_status() -> dict:
    """Get current rate limit status for all limiters."""
    return {
        "free": _free_limiter.status(),
        "paid": _paid_limiter.status(),
    }


def update_limits(free_rpm: int | None = None, paid_rpm: int | None = None) -> None:
    """Update rate limits (e.g., from API response headers)."""
    global _free_limiter, _paid_limiter
    if free_rpm is not None and free_rpm != _free_limiter.max_requests:
        logger.info("Updating FREE rate limit: %d → %d RPM", _free_limiter.max_requests, free_rpm)
        _free_limiter = SlidingWindowRateLimiter(max_requests=free_rpm, window_seconds=60.0, name="free-models")
    if paid_rpm is not None and paid_rpm != _paid_limiter.max_requests:
        logger.info("Updating PAID rate limit: %d → %d RPM", _paid_limiter.max_requests, paid_rpm)
        _paid_limiter = SlidingWindowRateLimiter(max_requests=paid_rpm, window_seconds=60.0, name="paid-models")
