"""Background watcher for async coding agent K8s Jobs.

Polls MongoDB for tasks in WAITING_FOR_AGENT state, checks their K8s Job
status, and resumes the orchestrator graph when a job completes.

Survives pod restarts — all state is in MongoDB (TaskDocument + LangGraph checkpoints).
"""

from __future__ import annotations

import asyncio
import logging

from app.agents.job_runner import job_runner
from app.config import settings
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)


class AgentTaskWatcher:
    """Monitors dispatched coding agent jobs and resumes orchestration on completion."""

    def __init__(self):
        self._running = False
        self._task: asyncio.Task | None = None

    async def start(self):
        if self._running:
            return
        self._running = True
        self._task = asyncio.create_task(self._watch_loop())
        logger.info("AgentTaskWatcher started (poll interval=%ds)", settings.agent_watcher_poll_interval)

    async def stop(self):
        if not self._running:
            return
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("AgentTaskWatcher stopped")

    async def _watch_loop(self):
        """Main loop — poll for WAITING_FOR_AGENT tasks and check their jobs."""
        while self._running:
            try:
                await self._poll_once()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("AgentTaskWatcher error: %s", e, exc_info=True)

            await asyncio.sleep(settings.agent_watcher_poll_interval)

    async def _poll_once(self):
        """Single poll iteration — find waiting tasks, check jobs, resume if done."""
        # Query Kotlin server for tasks in WAITING_FOR_AGENT state
        import httpx

        try:
            async with httpx.AsyncClient(timeout=15) as client:
                resp = await client.get(
                    f"{settings.kotlin_server_url}/internal/tasks/by-state",
                    params={"state": "WAITING_FOR_AGENT"},
                )
                if resp.status_code != 200:
                    return
                tasks = resp.json()
        except Exception as e:
            logger.debug("Failed to fetch WAITING_FOR_AGENT tasks: %s", e)
            return

        if not tasks:
            return

        logger.debug("Checking %d WAITING_FOR_AGENT tasks", len(tasks))

        for task_data in tasks:
            task_id = task_data.get("id", "")
            job_name = task_data.get("agentJobName")
            thread_id = task_data.get("orchestratorThreadId")

            if not job_name or not thread_id:
                logger.warning(
                    "Task %s in WAITING_FOR_AGENT but missing job_name=%s or thread_id=%s",
                    task_id, job_name, thread_id,
                )
                continue

            # Check K8s Job status
            status = job_runner.get_job_status(job_name)
            job_status = status.get("status", "unknown")

            if job_status == "running":
                # Still running — check for heartbeat timeout
                continue

            # Job completed (succeeded or failed) — collect result and resume
            workspace_path = task_data.get("agentJobWorkspacePath", "")
            agent_type = task_data.get("agentJobAgentType", "unknown")

            if job_status == "succeeded":
                result = job_runner.collect_result(
                    workspace_path=workspace_path,
                    job_name=job_name,
                    task_id=task_id,
                    agent_type=agent_type,
                )
                logger.info(
                    "Agent job completed: task=%s job=%s success=%s",
                    task_id, job_name, result.get("success", False),
                )
            else:
                result = {
                    "taskId": task_id,
                    "success": False,
                    "summary": f"Agent job {job_name} failed: {job_status}",
                    "agentType": agent_type,
                }
                logger.warning(
                    "Agent job failed: task=%s job=%s status=%s",
                    task_id, job_name, job_status,
                )

            # Update task state back to PYTHON_ORCHESTRATING and clear agent fields
            try:
                async with httpx.AsyncClient(timeout=15) as client:
                    await client.post(
                        f"{settings.kotlin_server_url}/internal/tasks/{task_id}/agent-completed",
                        json={
                            "agentJobState": job_status,
                            "result": result,
                        },
                    )
            except Exception as e:
                logger.error("Failed to update task %s after agent completion: %s", task_id, e)
                continue

            # Resume orchestration graph with agent result
            try:
                from app.graph.orchestrator import resume_orchestration_streaming

                logger.info("Resuming orchestration: thread=%s task=%s", thread_id, task_id)
                async for _event in resume_orchestration_streaming(
                    thread_id, resume_value=result,
                ):
                    pass  # Consume events — the graph handles the rest
                logger.info("Orchestration resumed successfully: task=%s", task_id)
            except Exception as e:
                logger.error(
                    "Failed to resume orchestration for task %s thread=%s: %s",
                    task_id, thread_id, e, exc_info=True,
                )


# Singleton
agent_task_watcher = AgentTaskWatcher()
