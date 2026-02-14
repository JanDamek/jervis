"""Agent Pool — manages concurrent agent slots, priority queue, and metrics.

Provides:
- Per-type configurable concurrent limits (from config.py)
- Priority queue for tasks waiting for a free slot (foreground > background)
- In-memory slot tracking (faster than K8s API polling)
- Prometheus metrics for utilization, job duration, queue depth
- Stuck job detection (timeout watchdog)
- MongoDB persistence for pod restart recovery
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import defaultdict
from dataclasses import dataclass, field
from enum import IntEnum

from prometheus_client import Counter, Gauge, Histogram, Info

from app.config import settings

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Prometheus metrics
# ---------------------------------------------------------------------------

AGENT_SLOTS_ACTIVE = Gauge(
    "jervis_agent_slots_active",
    "Active agent slots by type",
    ["agent_type"],
)
AGENT_SLOTS_LIMIT = Gauge(
    "jervis_agent_slots_limit",
    "Maximum agent slots by type",
    ["agent_type"],
)
AGENT_QUEUE_DEPTH = Gauge(
    "jervis_agent_queue_depth",
    "Tasks waiting for an agent slot by type",
    ["agent_type"],
)
AGENT_JOB_DURATION = Histogram(
    "jervis_agent_job_duration_seconds",
    "Duration of agent K8s Jobs",
    ["agent_type", "status"],
    buckets=[60, 120, 300, 600, 900, 1200, 1800, 2700, 3600],
)
AGENT_JOBS_TOTAL = Counter(
    "jervis_agent_jobs_total",
    "Total agent jobs created",
    ["agent_type"],
)
AGENT_JOBS_COMPLETED = Counter(
    "jervis_agent_jobs_completed_total",
    "Total agent jobs completed",
    ["agent_type", "status"],
)
AGENT_STUCK_DETECTED = Counter(
    "jervis_agent_stuck_detected_total",
    "Stuck agent jobs detected and cleaned up",
    ["agent_type"],
)
AGENT_QUEUE_WAIT = Histogram(
    "jervis_agent_queue_wait_seconds",
    "Time spent waiting in the agent queue",
    ["agent_type"],
    buckets=[5, 15, 30, 60, 120, 300, 600],
)
AGENT_POOL_INFO = Info(
    "jervis_agent_pool",
    "Agent pool configuration info",
)


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

class TaskPriority(IntEnum):
    """Task priority. Lower value = higher priority."""
    FOREGROUND = 0       # User-initiated, interactive
    BACKGROUND = 10      # Automated, batch


class AgentPoolFullError(Exception):
    """Raised when an agent slot cannot be acquired within timeout."""
    pass


@dataclass
class ActiveJob:
    """Tracked active job with timing info for stuck detection."""
    job_name: str
    agent_type: str
    task_id: str
    thread_id: str
    started_at: float = field(default_factory=time.time)
    timeout_seconds: int = 1800


@dataclass
class QueuedWaiter:
    """A graph waiting for an agent slot via asyncio.Event."""
    priority: TaskPriority
    queued_at: float
    agent_type: str
    event: asyncio.Event = field(default_factory=asyncio.Event)


# ---------------------------------------------------------------------------
# Agent Pool
# ---------------------------------------------------------------------------

class AgentPool:
    """Manages per-type concurrent agent limits with priority queue.

    Slot tracking is in-memory (fast, no K8s API calls).
    Provides acquire/release semantics with priority-aware waiting.
    """

    def __init__(self):
        self._limits: dict[str, int] = {
            "aider": settings.max_concurrent_aider,
            "openhands": settings.max_concurrent_openhands,
            "claude": settings.max_concurrent_claude,
            "junie": settings.max_concurrent_junie,
        }
        self._active: dict[str, int] = defaultdict(int)
        self._active_jobs: dict[str, ActiveJob] = {}  # job_name → ActiveJob
        self._waiters: dict[str, list[QueuedWaiter]] = defaultdict(list)

        # Initialize Prometheus gauges
        for agent_type, limit in self._limits.items():
            AGENT_SLOTS_LIMIT.labels(agent_type=agent_type).set(limit)
            AGENT_SLOTS_ACTIVE.labels(agent_type=agent_type).set(0)
            AGENT_QUEUE_DEPTH.labels(agent_type=agent_type).set(0)

        AGENT_POOL_INFO.info({
            "aider_limit": str(self._limits["aider"]),
            "openhands_limit": str(self._limits["openhands"]),
            "claude_limit": str(self._limits["claude"]),
            "junie_limit": str(self._limits["junie"]),
        })

    def can_start(self, agent_type: str) -> bool:
        """Check if a slot is available for this agent type."""
        limit = self._limits.get(agent_type, 1)
        return self._active[agent_type] < limit

    async def acquire(
        self,
        agent_type: str,
        priority: TaskPriority = TaskPriority.FOREGROUND,
        timeout: float | None = None,
    ) -> None:
        """Acquire an agent slot. Blocks until available or timeout.

        Uses priority ordering: FOREGROUND tasks are woken up before BACKGROUND.

        Raises:
            AgentPoolFullError: If slot not available within timeout.
        """
        if timeout is None:
            timeout = settings.pool_wait_timeout

        limit = self._limits.get(agent_type, 1)

        if self._active[agent_type] < limit:
            # Slot available immediately
            self._active[agent_type] += 1
            AGENT_SLOTS_ACTIVE.labels(agent_type=agent_type).set(self._active[agent_type])
            return

        # Need to wait for a slot
        waiter = QueuedWaiter(
            priority=priority,
            queued_at=time.time(),
            agent_type=agent_type,
        )
        self._waiters[agent_type].append(waiter)
        self._waiters[agent_type].sort(key=lambda w: (w.priority, w.queued_at))
        self._update_queue_gauge(agent_type)

        logger.info(
            "AgentPool: waiting for slot (%s: %d/%d, queue depth: %d, priority: %s)",
            agent_type, self._active[agent_type], limit,
            len(self._waiters[agent_type]), priority.name,
        )

        try:
            await asyncio.wait_for(waiter.event.wait(), timeout=timeout)
            # Slot acquired by release() — count already incremented
            AGENT_QUEUE_WAIT.labels(agent_type=agent_type).observe(
                time.time() - waiter.queued_at
            )
        except asyncio.TimeoutError:
            # Remove from waiters list
            try:
                self._waiters[agent_type].remove(waiter)
            except ValueError:
                pass
            self._update_queue_gauge(agent_type)
            raise AgentPoolFullError(
                f"No slot available for {agent_type} within {timeout}s "
                f"(active: {self._active[agent_type]}/{limit}, "
                f"queue: {len(self._waiters.get(agent_type, []))})"
            )

    def release(self, agent_type: str) -> None:
        """Release an agent slot and wake up highest-priority waiter."""
        waiters = self._waiters.get(agent_type, [])
        if waiters:
            # Transfer slot to highest-priority waiter (already sorted)
            waiter = waiters.pop(0)
            waiter.event.set()
            self._update_queue_gauge(agent_type)
            logger.info(
                "AgentPool: slot transferred to waiting task (%s, priority: %s)",
                agent_type, waiter.priority.name,
            )
        else:
            # No waiters — just decrement count
            self._active[agent_type] = max(0, self._active[agent_type] - 1)
            AGENT_SLOTS_ACTIVE.labels(agent_type=agent_type).set(self._active[agent_type])

    def mark_started(
        self,
        job_name: str,
        agent_type: str,
        task_id: str = "",
        thread_id: str = "",
        timeout_seconds: int = 1800,
    ) -> None:
        """Track a started job for stuck detection and metrics."""
        self._active_jobs[job_name] = ActiveJob(
            job_name=job_name,
            agent_type=agent_type,
            task_id=task_id,
            thread_id=thread_id,
            timeout_seconds=timeout_seconds,
        )
        AGENT_JOBS_TOTAL.labels(agent_type=agent_type).inc()
        logger.info(
            "AgentPool: job started %s (%s: %d/%d)",
            job_name, agent_type,
            self._active[agent_type], self._limits.get(agent_type, 1),
        )

    def mark_completed(self, job_name: str, status: str = "succeeded") -> str | None:
        """Mark a job as completed. Returns agent_type for slot release.

        Call release(agent_type) separately after this.
        """
        active_job = self._active_jobs.pop(job_name, None)
        if not active_job:
            return None

        agent_type = active_job.agent_type
        duration = time.time() - active_job.started_at
        AGENT_JOB_DURATION.labels(agent_type=agent_type, status=status).observe(duration)
        AGENT_JOBS_COMPLETED.labels(agent_type=agent_type, status=status).inc()

        logger.info(
            "AgentPool: job completed %s (%s, duration=%.0fs, status=%s)",
            job_name, agent_type, duration, status,
        )
        return agent_type

    def get_stuck_jobs(self) -> list[ActiveJob]:
        """Return jobs that have exceeded their timeout (stuck detection).

        Uses stuck_job_timeout_multiplier from config to determine threshold.
        """
        now = time.time()
        multiplier = settings.stuck_job_timeout_multiplier
        stuck = []
        for job in self._active_jobs.values():
            if now - job.started_at > job.timeout_seconds * multiplier:
                stuck.append(job)
        return stuck

    def _update_queue_gauge(self, agent_type: str) -> None:
        """Update Prometheus queue depth gauge."""
        AGENT_QUEUE_DEPTH.labels(agent_type=agent_type).set(
            len(self._waiters.get(agent_type, []))
        )

    @property
    def queue_depth(self) -> int:
        """Total number of tasks waiting across all agent types."""
        return sum(len(w) for w in self._waiters.values())

    @property
    def total_active(self) -> int:
        """Total number of active jobs across all agent types."""
        return sum(self._active.values())

    def active_count(self, agent_type: str) -> int:
        """Active job count for a specific agent type."""
        return self._active[agent_type]

    def status_summary(self) -> dict:
        """Return pool status for health/debug endpoints."""
        return {
            "limits": dict(self._limits),
            "active": dict(self._active),
            "queue_depth": {
                agent_type: len(waiters)
                for agent_type, waiters in self._waiters.items()
                if waiters
            },
            "active_jobs": [
                {
                    "job_name": j.job_name,
                    "agent_type": j.agent_type,
                    "running_seconds": int(time.time() - j.started_at),
                    "timeout_seconds": j.timeout_seconds,
                }
                for j in self._active_jobs.values()
            ],
        }


# Singleton
agent_pool = AgentPool()
