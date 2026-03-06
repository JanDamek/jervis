"""Background watcher for async coding agent K8s Jobs.

Polls MongoDB for tasks in CODING state, checks their K8s Job
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
        # Track processed jobs to avoid infinite reprocessing if Kotlin call fails
        self._processed_jobs: set[str] = set()

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
        """Main loop — poll for CODING tasks and check their jobs."""
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
        # Query Kotlin server for tasks in CODING state
        import httpx

        try:
            async with httpx.AsyncClient(timeout=15) as client:
                resp = await client.get(
                    f"{settings.kotlin_server_url}/internal/tasks/by-state",
                    params={"state": "CODING"},
                )
                if resp.status_code != 200:
                    return
                tasks = resp.json()
        except Exception as e:
            logger.debug("Failed to fetch CODING tasks: %s", e)
            return

        if not tasks:
            return

        logger.debug("Checking %d CODING tasks", len(tasks))

        for task_data in tasks:
            task_id = task_data.get("id", "")
            job_name = task_data.get("agentJobName")
            thread_id = task_data.get("orchestratorThreadId")

            if not job_name:
                logger.warning("Task %s in CODING but missing job_name", task_id)
                continue

            # thread_id is required for graph-based tasks but optional for direct coding
            source_urn = task_data.get("sourceUrn", "")
            if not thread_id and source_urn != "chat:coding-agent":
                logger.warning("Task %s in CODING but missing thread_id (non-direct)", task_id)
                continue

            # Skip already-processed jobs (prevents infinite loop if Kotlin update fails)
            if job_name in self._processed_jobs:
                logger.debug("Skipping already-processed job: %s", job_name)
                continue

            # Check K8s Job status
            status = job_runner.get_job_status(job_name)
            job_status = status.get("status", "unknown")

            if job_status == "running":
                continue

            # Job completed (succeeded or failed) — collect result and resume
            workspace_path = task_data.get("agentJobWorkspacePath", "")
            agent_type = task_data.get("agentJobAgentType", "unknown")

            # Always try to collect result.json first — entrypoint writes detailed errors
            result = job_runner.collect_result(
                workspace_path=workspace_path,
                job_name=job_name,
                task_id=task_id,
                agent_type=agent_type,
            )

            if job_status == "succeeded":
                logger.info(
                    "Agent job completed: task=%s job=%s success=%s",
                    task_id, job_name, result.get("success", False),
                )
            else:
                # Enrich with job status if result.json had no detailed summary
                if result.get("summary", "").startswith("Job "):
                    result["summary"] = f"Agent job {job_name} failed: {job_status}"
                result["success"] = False
                logger.warning(
                    "Agent job failed: task=%s job=%s status=%s summary=%s",
                    task_id, job_name, job_status, result.get("summary", "")[:200],
                )

            # Mark as processed immediately to prevent reprocessing
            self._processed_jobs.add(job_name)

            # Check if this is a direct coding task (no graph to resume)
            source_urn = task_data.get("sourceUrn", "")
            is_direct_coding = source_urn == "chat:coding-agent"

            if is_direct_coding:
                # Direct coding task — two-step: CODING→PROCESSING→DONE
                # Step 1: agent-completed moves CODING→PROCESSING
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
                    logger.error("Failed to mark agent-completed for direct task %s: %s", task_id, e)

                # Step 2: report_status_change moves PROCESSING→DONE
                try:
                    await kotlin_client.report_status_change(
                        task_id=task_id,
                        thread_id=thread_id or "",
                        status="done" if result.get("success") else "error",
                        summary=result.get("summary", "Coding agent completed"),
                        error=None if result.get("success") else result.get("summary"),
                    )
                    logger.info("Direct coding task completed: task=%s job=%s success=%s", task_id, job_name, result.get("success"))
                except Exception as e:
                    logger.error("Failed to report coding task done %s: %s", task_id, e)

                # Update master map TASK_REF vertex → completed
                try:
                    from app.graph_agent.persistence import task_graph_store
                    job_success = result.get("success", False)
                    await task_graph_store.link_task_subgraph(
                        task_id=task_id,
                        sub_graph_id="",
                        title=f"Coding: {task_id[:12]}",
                        completed=job_success,
                        failed=not job_success,
                        result_summary=result.get("summary", "")[:500],
                    )
                except Exception:
                    pass  # Non-fatal
            else:
                # Graph-based task — update state and resume orchestration graph
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

            # Cap processed set size (prevent unbounded memory growth)
            if len(self._processed_jobs) > 1000:
                self._processed_jobs.clear()


# Singleton
agent_task_watcher = AgentTaskWatcher()
