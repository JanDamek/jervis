"""AgentJobWatcher — background service for non-blocking coding agent execution.

Polls K8s Jobs every POLL_INTERVAL_SECONDS, detects completed/failed jobs,
and resumes the paused LangGraph orchestration via the existing resume mechanism.

Also pushes heartbeats to Kotlin to keep liveness detection working while
the coding agent is running (BackgroundEngine uses 10-min heartbeat threshold).

Phase 2 enhancements:
- Integrates with AgentPool for slot management and priority queue
- Stuck job detection (timeout watchdog) — kills jobs exceeding N*timeout
- MongoDB persistence for pod restart recovery
- Prometheus metrics via AgentPool
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass

from app.agents.job_runner import job_runner
from app.config import settings
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)

POLL_INTERVAL_SECONDS = 10


@dataclass
class WatchedJob:
    """A K8s Job being monitored by the watcher."""

    job_name: str
    thread_id: str
    task_id: str           # step-level task_id (e.g. "abc-step-0")
    kotlin_task_id: str    # top-level task_id for Kotlin progress/status
    client_id: str
    workspace_path: str
    agent_type: str


class AgentJobWatcher:
    """Background watcher for coding agent K8s Jobs.

    Polls K8s every POLL_INTERVAL_SECONDS, detects completed jobs,
    resumes the paused LangGraph orchestration via _resume_in_background().

    Phase 2: Integrates with AgentPool for slot release, stuck detection,
    and MongoDB-based recovery on pod restart.

    Lifecycle:
    - start() called from main.py lifespan startup
    - recover() called after start() to re-register persisted jobs
    - stop() called from main.py lifespan shutdown
    - register() called when an agent_wait interrupt is detected
    """

    def __init__(self):
        self._watched_jobs: dict[str, WatchedJob] = {}  # job_name → WatchedJob
        self._task: asyncio.Task | None = None
        self._db = None  # Motor database for persistence

    @property
    def watched_count(self) -> int:
        """Number of jobs currently being watched."""
        return len(self._watched_jobs)

    async def start(self):
        """Start background polling loop."""
        # Initialize MongoDB for persistence
        try:
            from motor.motor_asyncio import AsyncIOMotorClient
            client = AsyncIOMotorClient(settings.mongodb_url)
            self._db = client["jervis_agent_watcher"]
            # Ensure index
            await self._db["watched_jobs"].create_index("job_name", unique=True)
            logger.info("AgentJobWatcher: MongoDB persistence ready")
        except Exception as e:
            logger.warning("AgentJobWatcher: MongoDB persistence unavailable: %s", e)
            self._db = None

        self._task = asyncio.create_task(self._run_forever())
        logger.info("AgentJobWatcher started (poll interval: %ds)", POLL_INTERVAL_SECONDS)

    async def stop(self):
        """Stop background polling loop."""
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("AgentJobWatcher stopped (%d jobs abandoned)", len(self._watched_jobs))

    def register(
        self,
        job_name: str,
        thread_id: str,
        task_id: str,
        kotlin_task_id: str,
        client_id: str,
        workspace_path: str,
        agent_type: str,
    ):
        """Register a K8s Job for watching.

        Called from main.py when an agent_wait interrupt is detected
        after graph execution pauses.
        """
        self._watched_jobs[job_name] = WatchedJob(
            job_name=job_name,
            thread_id=thread_id,
            task_id=task_id,
            kotlin_task_id=kotlin_task_id,
            client_id=client_id,
            workspace_path=workspace_path,
            agent_type=agent_type,
        )
        logger.info(
            "AgentJobWatcher: registered job=%s thread=%s agent=%s (total watched: %d)",
            job_name, thread_id, agent_type, len(self._watched_jobs),
        )
        # Persist to MongoDB (fire-and-forget)
        asyncio.create_task(self._persist_job(job_name))

    async def recover(self):
        """Recover watched jobs from MongoDB on pod restart.

        Called from main.py lifespan startup after start().
        Re-registers any persisted watched jobs, then verifies each
        against K8s to check if the job is still running.
        """
        if not self._db:
            logger.info("AgentJobWatcher: recovery skipped (no MongoDB)")
            return

        try:
            cursor = self._db["watched_jobs"].find()
            recovered = 0
            async for doc in cursor:
                job_name = doc["job_name"]
                watched = WatchedJob(
                    job_name=job_name,
                    thread_id=doc["thread_id"],
                    task_id=doc["task_id"],
                    kotlin_task_id=doc["kotlin_task_id"],
                    client_id=doc["client_id"],
                    workspace_path=doc["workspace_path"],
                    agent_type=doc["agent_type"],
                )

                # Verify the job still exists in K8s
                try:
                    status = job_runner.check_job_status(job_name)
                    job_status = status.get("status", "unknown")

                    if job_status == "running":
                        # Job still running — re-register for watching
                        self._watched_jobs[job_name] = watched
                        # Re-register in pool (slot was tracked before restart)
                        from app.agents.agent_pool import agent_pool
                        if not agent_pool.can_start(watched.agent_type):
                            # Pool already has the right count from K8s reality
                            pass
                        else:
                            # Re-occupy slot in pool
                            from app.agents.agent_pool import AGENT_SLOTS_ACTIVE
                            agent_pool._active[watched.agent_type] += 1
                            AGENT_SLOTS_ACTIVE.labels(
                                agent_type=watched.agent_type
                            ).set(agent_pool._active[watched.agent_type])
                        recovered += 1
                        logger.info(
                            "AgentJobWatcher: recovered running job %s (thread=%s)",
                            job_name, watched.thread_id,
                        )
                    elif job_status in ("succeeded", "failed"):
                        # Job already completed — resume graph immediately
                        result = job_runner.read_job_result(
                            task_id=watched.task_id,
                            workspace_path=watched.workspace_path,
                            job_name=job_name,
                        )
                        await self._resume_graph(watched, result)
                        await self._remove_persisted_job(job_name)
                        recovered += 1
                        logger.info(
                            "AgentJobWatcher: recovered completed job %s (status=%s)",
                            job_name, job_status,
                        )
                    else:
                        # Job not found — resume with error
                        result = {
                            "taskId": watched.task_id,
                            "success": False,
                            "summary": f"K8s Job {job_name} not found after pod restart",
                            "agentType": watched.agent_type,
                        }
                        await self._resume_graph(watched, result)
                        await self._remove_persisted_job(job_name)
                        logger.warning(
                            "AgentJobWatcher: recovered lost job %s (not found in K8s)",
                            job_name,
                        )
                except Exception as e:
                    logger.error(
                        "AgentJobWatcher: recovery failed for job %s: %s", job_name, e,
                    )

            logger.info(
                "AgentJobWatcher: recovery complete — %d jobs recovered", recovered,
            )
        except Exception as e:
            logger.error("AgentJobWatcher: recovery scan failed: %s", e)

    async def _run_forever(self):
        """Main poll loop: check K8s Jobs, detect stuck, resume completed."""
        while True:
            try:
                await self._poll_once()
                await self._check_stuck_jobs()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.error("AgentJobWatcher poll error: %s", e)

            await asyncio.sleep(POLL_INTERVAL_SECONDS)

    async def _poll_once(self):
        """Single poll iteration: check all watched jobs."""
        if not self._watched_jobs:
            return

        from app.agents.agent_pool import agent_pool

        for job_name, watched in list(self._watched_jobs.items()):
            try:
                status = job_runner.check_job_status(job_name)
            except Exception as e:
                logger.warning(
                    "AgentJobWatcher: failed to check job %s: %s", job_name, e,
                )
                continue

            # Push heartbeat to Kotlin (keeps liveness detection happy)
            try:
                await kotlin_client.report_progress(
                    task_id=watched.kotlin_task_id,
                    client_id=watched.client_id,
                    node="execute_step",
                    message=f"Agent {watched.agent_type} working... (job: {job_name})",
                )
            except Exception:
                pass  # Non-critical

            job_status = status.get("status", "unknown")

            if job_status in ("succeeded", "failed"):
                logger.info(
                    "AgentJobWatcher: job %s finished (status=%s) — resuming graph thread=%s",
                    job_name, job_status, watched.thread_id,
                )

                # Read result from workspace
                result = job_runner.read_job_result(
                    task_id=watched.task_id,
                    workspace_path=watched.workspace_path,
                    job_name=job_name,
                )

                # Release pool slot and record metrics
                agent_type = agent_pool.mark_completed(job_name, job_status)
                if agent_type:
                    agent_pool.release(agent_type)

                # Resume the paused graph with the result
                await self._resume_graph(watched, result)

                # Remove from watch list and persistence
                del self._watched_jobs[job_name]
                asyncio.create_task(self._remove_persisted_job(job_name))

            elif job_status == "not_found":
                logger.warning(
                    "AgentJobWatcher: job %s not found (deleted?) — resuming with error",
                    job_name,
                )

                result = {
                    "taskId": watched.task_id,
                    "success": False,
                    "summary": f"K8s Job {job_name} not found (may have been deleted)",
                    "agentType": watched.agent_type,
                }

                # Release pool slot
                agent_type = agent_pool.mark_completed(job_name, "not_found")
                if agent_type:
                    agent_pool.release(agent_type)

                await self._resume_graph(watched, result)
                del self._watched_jobs[job_name]
                asyncio.create_task(self._remove_persisted_job(job_name))

    async def _check_stuck_jobs(self):
        """Detect and handle jobs that have exceeded their timeout.

        Stuck jobs are cleaned up by deleting the K8s Job and resuming
        the graph with a timeout error. Uses stuck_job_timeout_multiplier
        from config to determine the threshold.
        """
        from app.agents.agent_pool import agent_pool, AGENT_STUCK_DETECTED

        stuck_jobs = agent_pool.get_stuck_jobs()
        for stuck in stuck_jobs:
            if stuck.job_name not in self._watched_jobs:
                continue

            watched = self._watched_jobs[stuck.job_name]
            elapsed = time.time() - stuck.started_at

            logger.warning(
                "AgentJobWatcher: STUCK JOB DETECTED — %s (agent=%s, elapsed=%.0fs, timeout=%ds)",
                stuck.job_name, stuck.agent_type, elapsed, stuck.timeout_seconds,
            )
            AGENT_STUCK_DETECTED.labels(agent_type=stuck.agent_type).inc()

            # Try to delete the K8s Job
            try:
                from kubernetes import client as k8s_client
                job_runner.batch_v1.delete_namespaced_job(
                    name=stuck.job_name,
                    namespace=settings.k8s_namespace,
                    body=k8s_client.V1DeleteOptions(propagation_policy="Background"),
                )
                logger.info("AgentJobWatcher: deleted stuck job %s", stuck.job_name)
            except Exception as e:
                logger.warning(
                    "AgentJobWatcher: failed to delete stuck job %s: %s",
                    stuck.job_name, e,
                )

            # Resume graph with timeout error
            result = {
                "taskId": watched.task_id,
                "success": False,
                "summary": (
                    f"K8s Job {stuck.job_name} timed out after {int(elapsed)}s "
                    f"(limit: {stuck.timeout_seconds}s × "
                    f"{settings.stuck_job_timeout_multiplier})"
                ),
                "agentType": stuck.agent_type,
            }

            # Release pool slot
            agent_type = agent_pool.mark_completed(stuck.job_name, "stuck")
            if agent_type:
                agent_pool.release(agent_type)

            await self._resume_graph(watched, result)
            del self._watched_jobs[stuck.job_name]
            asyncio.create_task(self._remove_persisted_job(stuck.job_name))

    async def _resume_graph(self, watched: WatchedJob, result: dict):
        """Resume the paused LangGraph orchestration with the job result.

        Uses the same _resume_in_background() mechanism as the approval flow.
        """
        # Import here to avoid circular imports (main.py imports this module)
        from app.main import _resume_in_background, _active_tasks

        try:
            task = asyncio.create_task(
                _resume_in_background(watched.thread_id, result)
            )
            _active_tasks[watched.thread_id] = task
            logger.info(
                "AgentJobWatcher: resumed graph thread=%s with result (success=%s)",
                watched.thread_id, result.get("success"),
            )
        except Exception as e:
            logger.error(
                "AgentJobWatcher: failed to resume graph thread=%s: %s",
                watched.thread_id, e,
            )

            # Report error to Kotlin so task doesn't hang
            try:
                await kotlin_client.report_status_change(
                    task_id=watched.kotlin_task_id,
                    thread_id=watched.thread_id,
                    status="error",
                    error=f"AgentJobWatcher resume failed: {e}",
                )
            except Exception:
                pass

    # --- MongoDB persistence ---

    async def _persist_job(self, job_name: str):
        """Persist watched job to MongoDB for pod restart recovery."""
        if not self._db:
            return

        watched = self._watched_jobs.get(job_name)
        if not watched:
            return

        try:
            await self._db["watched_jobs"].update_one(
                {"job_name": job_name},
                {"$set": {
                    "job_name": watched.job_name,
                    "thread_id": watched.thread_id,
                    "task_id": watched.task_id,
                    "kotlin_task_id": watched.kotlin_task_id,
                    "client_id": watched.client_id,
                    "workspace_path": watched.workspace_path,
                    "agent_type": watched.agent_type,
                }},
                upsert=True,
            )
        except Exception as e:
            logger.warning("AgentJobWatcher: persist failed for %s: %s", job_name, e)

    async def _remove_persisted_job(self, job_name: str):
        """Remove persisted watched job from MongoDB."""
        if not self._db:
            return

        try:
            await self._db["watched_jobs"].delete_one({"job_name": job_name})
        except Exception as e:
            logger.warning("AgentJobWatcher: remove persist failed for %s: %s", job_name, e)


# Singleton
agent_job_watcher = AgentJobWatcher()
