"""AgentJobWatcher — background service for non-blocking coding agent execution.

Polls K8s Jobs every POLL_INTERVAL_SECONDS, detects completed/failed jobs,
and resumes the paused LangGraph orchestration via the existing resume mechanism.

Also pushes heartbeats to Kotlin to keep liveness detection working while
the coding agent is running (BackgroundEngine uses 10-min heartbeat threshold).

Recovery: On pod restart, the watcher is re-populated from interrupt data
detected in _run_and_stream() and _resume_in_background() (main.py).
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass

from app.agents.job_runner import job_runner
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

    Lifecycle:
    - start() called from main.py lifespan startup
    - stop() called from main.py lifespan shutdown
    - register() called when an agent_wait interrupt is detected

    The watcher pushes heartbeats to Kotlin on every poll cycle
    so BackgroundEngine doesn't mark the task as dead.
    """

    def __init__(self):
        self._watched_jobs: dict[str, WatchedJob] = {}  # job_name → WatchedJob
        self._task: asyncio.Task | None = None

    @property
    def watched_count(self) -> int:
        """Number of jobs currently being watched."""
        return len(self._watched_jobs)

    async def start(self):
        """Start background polling loop."""
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

    async def _run_forever(self):
        """Main poll loop: check K8s Jobs and resume completed orchestrations."""
        while True:
            try:
                await self._poll_once()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.error("AgentJobWatcher poll error: %s", e)

            await asyncio.sleep(POLL_INTERVAL_SECONDS)

    async def _poll_once(self):
        """Single poll iteration: check all watched jobs."""
        if not self._watched_jobs:
            return

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

                # Resume the paused graph with the result
                await self._resume_graph(watched, result)

                # Remove from watch list
                del self._watched_jobs[job_name]

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

                await self._resume_graph(watched, result)
                del self._watched_jobs[job_name]

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


# Singleton
agent_job_watcher = AgentJobWatcher()
