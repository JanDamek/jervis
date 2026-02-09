"""REST client for Kotlin server internal API.

Push-based communication model:
- Python → Kotlin: POST /internal/orchestrator-progress (node progress during execution)
- Python → Kotlin: POST /internal/orchestrator-status (completion/error/interrupt)
- Python → Kotlin: POST /internal/correction-progress (correction agent progress)
- Kotlin → Python: POST /orchestrate/stream (fire-and-forget dispatch)
- Kotlin → Python: POST /approve/{thread_id} (resume graph)
- Kotlin → Python: GET /status/{thread_id} (fallback safety-net polling, 60s)

This client pushes real-time progress and status changes to Kotlin,
which broadcasts them to UI clients via Flow-based event subscriptions.
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class KotlinServerClient:
    """HTTP client for Kotlin server — push-based communication."""

    def __init__(self, base_url: str | None = None):
        self.base_url = base_url or settings.kotlin_server_url
        self._client: httpx.AsyncClient | None = None

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=self.base_url,
                timeout=5.0,
            )
        return self._client

    async def report_progress(
        self,
        task_id: str,
        client_id: str,
        node: str,
        message: str,
        percent: float = 0.0,
        goal_index: int = 0,
        total_goals: int = 0,
        step_index: int = 0,
        total_steps: int = 0,
    ) -> bool:
        """Push orchestrator progress to Kotlin server.

        Called during graph execution on node_start/node_end events.
        Kotlin broadcasts as OrchestratorTaskProgress event to UI.
        """
        try:
            client = await self._get_client()
            await client.post(
                "/internal/orchestrator-progress",
                json={
                    "taskId": task_id,
                    "clientId": client_id,
                    "node": node,
                    "message": message,
                    "percent": percent,
                    "goalIndex": goal_index,
                    "totalGoals": total_goals,
                    "stepIndex": step_index,
                    "totalSteps": total_steps,
                },
            )
            return True
        except Exception as e:
            logger.debug("Failed to report progress to Kotlin: %s", e)
            return False

    async def report_status_change(
        self,
        task_id: str,
        thread_id: str,
        status: str,
        summary: str | None = None,
        error: str | None = None,
        interrupt_action: str | None = None,
        interrupt_description: str | None = None,
        branch: str | None = None,
        artifacts: list[str] | None = None,
    ) -> bool:
        """Push orchestrator status change to Kotlin server.

        Called when orchestration completes, errors, or gets interrupted.
        Kotlin handles state changes immediately (no polling needed).

        Status values: "done", "error", "interrupted"
        """
        try:
            client = await self._get_client()
            await client.post(
                "/internal/orchestrator-status",
                json={
                    "taskId": task_id,
                    "threadId": thread_id,
                    "status": status,
                    "summary": summary,
                    "error": error,
                    "interruptAction": interrupt_action,
                    "interruptDescription": interrupt_description,
                    "branch": branch,
                    "artifacts": artifacts or [],
                },
            )
            return True
        except Exception as e:
            logger.warning("Failed to report status to Kotlin: %s", e)
            return False

    async def report_task_error(self, task_id: str, error: str) -> bool:
        """Report a critical task error to Kotlin server.

        Only used for errors that callbacks might not detect
        (e.g., graph construction failure before any state is saved).
        """
        try:
            client = await self._get_client()
            resp = await client.post(
                "/api/internal/orchestrator/error",
                json={"taskId": task_id, "error": error},
            )
            return resp.status_code == 200
        except Exception as e:
            logger.warning("Failed to report error to Kotlin: %s", e)
            return False

    async def close(self):
        if self._client and not self._client.is_closed:
            await self._client.aclose()


# Singleton
kotlin_client = KotlinServerClient()
