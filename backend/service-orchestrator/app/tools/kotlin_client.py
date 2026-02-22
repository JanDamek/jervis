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
        # --- Multi-agent delegation fields (backward compatible) ---
        delegation_id: str | None = None,
        delegation_agent: str | None = None,
        delegation_depth: int | None = None,
        thinking_about: str | None = None,
    ) -> bool:
        """Push orchestrator progress to Kotlin server.

        Called during graph execution on node_start/node_end events.
        Kotlin broadcasts as OrchestratorTaskProgress event to UI.

        New optional fields for delegation system (Kotlin ignores if unsupported):
        - delegation_id: ID of the active delegation
        - delegation_agent: Name of the agent being executed
        - delegation_depth: Recursion depth (0-4)
        - thinking_about: What the orchestrator is currently reasoning about
        """
        try:
            client = await self._get_client()
            payload = {
                "taskId": task_id,
                "clientId": client_id,
                "node": node,
                "message": message,
                "percent": percent,
                "goalIndex": goal_index,
                "totalGoals": total_goals,
                "stepIndex": step_index,
                "totalSteps": total_steps,
            }

            # Add delegation fields only if set (backward compatible)
            if delegation_id is not None:
                payload["delegationId"] = delegation_id
            if delegation_agent is not None:
                payload["delegationAgent"] = delegation_agent
            if delegation_depth is not None:
                payload["delegationDepth"] = delegation_depth
            if thinking_about is not None:
                payload["thinkingAbout"] = thinking_about

            await client.post("/internal/orchestrator-progress", json=payload)
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

    async def notify_agent_dispatched(self, task_id: str, job_name: str) -> bool:
        """Notify Kotlin server that a coding agent K8s Job was dispatched.

        Sets task state to WAITING_FOR_AGENT with agentJobName.
        """
        try:
            client = await self._get_client()
            resp = await client.post(
                f"/internal/tasks/{task_id}/agent-dispatched",
                json={"jobName": job_name},
            )
            return resp.status_code == 200
        except Exception as e:
            logger.warning("Failed to notify agent dispatched for task %s: %s", task_id, e)
            return False

    async def emit_streaming_token(
        self,
        task_id: str,
        client_id: str,
        project_id: str | None,
        token: str,
        message_id: str,
        is_final: bool = False,
    ) -> bool:
        """Push a single streaming token to Kotlin server for real-time UI display.

        Kotlin emits as STREAMING_TOKEN ChatResponseDto to UI via SharedFlow.
        """
        try:
            client = await self._get_client()
            await client.post(
                "/internal/streaming-token",
                json={
                    "taskId": task_id,
                    "clientId": client_id,
                    "projectId": project_id or "",
                    "token": token,
                    "messageId": message_id,
                    "isFinal": is_final,
                },
            )
            return True
        except Exception as e:
            logger.debug("Failed to emit streaming token: %s", e)
            return False

    # ------------------------------------------------------------------
    # Chat foreground preemption
    # ------------------------------------------------------------------

    async def register_foreground_start(self) -> bool:
        """Register foreground chat start — preempts background tasks."""
        try:
            client = await self._get_client()
            await client.post("/internal/foreground-start")
            return True
        except Exception as e:
            logger.warning("Failed to register foreground start: %s", e)
            return False

    async def register_foreground_end(self) -> bool:
        """Register foreground chat end — allows background tasks to resume."""
        try:
            client = await self._get_client()
            await client.post("/internal/foreground-end")
            return True
        except Exception as e:
            logger.warning("Failed to register foreground end: %s", e)
            return False

    # ------------------------------------------------------------------
    # Chat-specific task tools
    # ------------------------------------------------------------------

    async def create_background_task(
        self,
        title: str,
        description: str,
        client_id: str,
        project_id: str | None = None,
        priority: str = "medium",
    ) -> str:
        """Create a background task via Kotlin internal API."""
        try:
            client = await self._get_client()
            resp = await client.post(
                "/internal/create-background-task",
                json={
                    "title": title,
                    "description": description,
                    "clientId": client_id,
                    "projectId": project_id,
                    "priority": priority,
                },
            )
            return resp.json() if resp.status_code == 200 else f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to create background task: %s", e)
            return f"Error: {e}"

    async def dispatch_coding_agent(
        self,
        task_description: str,
        client_id: str,
        project_id: str,
    ) -> str:
        """Dispatch coding agent via Kotlin internal API."""
        try:
            client = await self._get_client()
            resp = await client.post(
                "/internal/dispatch-coding-agent",
                json={
                    "taskDescription": task_description,
                    "clientId": client_id,
                    "projectId": project_id,
                },
            )
            return resp.json() if resp.status_code == 200 else f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to dispatch coding agent: %s", e)
            return f"Error: {e}"

    async def search_user_tasks(
        self,
        query: str,
        max_results: int = 5,
    ) -> str:
        """Search user_tasks via Kotlin internal API."""
        try:
            client = await self._get_client()
            resp = await client.get(
                "/internal/user-tasks",
                params={"query": query, "maxResults": max_results},
            )
            if resp.status_code == 200:
                import json
                return json.dumps(resp.json(), ensure_ascii=False, indent=2)
            return f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to search user tasks: %s", e)
            return f"Error: {e}"

    async def respond_to_user_task(
        self,
        task_id: str,
        response: str,
    ) -> str:
        """Respond to user_task via Kotlin internal API."""
        try:
            client = await self._get_client()
            resp = await client.post(
                "/internal/respond-to-user-task",
                json={
                    "taskId": task_id,
                    "response": response,
                },
            )
            return resp.json() if resp.status_code == 200 else f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to respond to user task: %s", e)
            return f"Error: {e}"

    async def get_user_task(self, task_id: str) -> dict | None:
        """Get a user_task by ID for context loading."""
        try:
            client = await self._get_client()
            resp = await client.get(f"/internal/user-tasks/{task_id}")
            if resp.status_code == 200:
                return resp.json()
            return None
        except Exception as e:
            logger.warning("Failed to get user task %s: %s", task_id, e)
            return None

    async def classify_meeting(
        self,
        meeting_id: str,
        client_id: str,
        project_id: str | None = None,
        title: str | None = None,
    ) -> str:
        """Classify meeting via Kotlin internal API."""
        try:
            client = await self._get_client()
            resp = await client.post(
                "/internal/classify-meeting",
                json={
                    "meetingId": meeting_id,
                    "clientId": client_id,
                    "projectId": project_id,
                    "title": title,
                },
            )
            return resp.json() if resp.status_code == 200 else f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to classify meeting: %s", e)
            return f"Error: {e}"

    async def list_unclassified_meetings(self) -> str:
        """List unclassified meetings via Kotlin internal API."""
        try:
            client = await self._get_client()
            resp = await client.get("/internal/unclassified-meetings")
            if resp.status_code == 200:
                import json
                return json.dumps(resp.json(), ensure_ascii=False, indent=2)
            return f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to list unclassified meetings: %s", e)
            return f"Error: {e}"

    # ------------------------------------------------------------------
    # Chat runtime context (for system prompt enrichment)
    # ------------------------------------------------------------------

    async def get_clients_projects(self) -> list[dict]:
        """Get all clients with their projects (id, name) for LLM scope resolution."""
        try:
            client = await self._get_client()
            resp = await client.get("/internal/clients-projects")
            if resp.status_code == 200:
                return resp.json()
            return []
        except Exception as e:
            logger.warning("Failed to get clients-projects: %s", e)
            return []

    async def get_pending_user_tasks_summary(self, limit: int = 3) -> dict:
        """Get pending user tasks count + top N for proactive mentions."""
        try:
            client = await self._get_client()
            resp = await client.get(
                "/internal/pending-user-tasks/summary",
                params={"limit": limit},
            )
            if resp.status_code == 200:
                return resp.json()
            return {"count": 0, "tasks": []}
        except Exception as e:
            logger.warning("Failed to get pending user tasks summary: %s", e)
            return {"count": 0, "tasks": []}

    async def count_unclassified_meetings(self) -> int:
        """Get count of unclassified meetings."""
        try:
            client = await self._get_client()
            resp = await client.get("/internal/unclassified-meetings/count")
            if resp.status_code == 200:
                return resp.json().get("count", 0)
            return 0
        except Exception as e:
            logger.warning("Failed to count unclassified meetings: %s", e)
            return 0

    # ------------------------------------------------------------------
    # Task tools (for chat agent)
    # ------------------------------------------------------------------

    async def get_task_status(self, task_id: str) -> str:
        """Get task status by ID."""
        try:
            client = await self._get_client()
            resp = await client.get(f"/internal/tasks/{task_id}/status")
            if resp.status_code == 200:
                import json
                return json.dumps(resp.json(), ensure_ascii=False, indent=2)
            return f"Task not found: {task_id}"
        except Exception as e:
            logger.warning("Failed to get task status %s: %s", task_id, e)
            return f"Error: {e}"

    async def search_tasks(
        self,
        query: str,
        state: str | None = None,
        max_results: int = 5,
    ) -> str:
        """Search all tasks (not just user_tasks)."""
        try:
            client = await self._get_client()
            params: dict = {"q": query, "limit": max_results}
            if state and state != "all":
                params["state"] = state
            resp = await client.get("/internal/tasks/search", params=params)
            if resp.status_code == 200:
                import json
                return json.dumps(resp.json(), ensure_ascii=False, indent=2)
            return f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to search tasks: %s", e)
            return f"Error: {e}"

    async def list_recent_tasks(
        self,
        limit: int = 10,
        state: str | None = None,
        since: str = "today",
        client_id: str | None = None,
    ) -> str:
        """List recent tasks with optional filters."""
        try:
            client = await self._get_client()
            params: dict = {"limit": limit, "since": since}
            if state and state != "all":
                params["state"] = state
            if client_id:
                params["clientId"] = client_id
            resp = await client.get("/internal/tasks/recent", params=params)
            if resp.status_code == 200:
                import json
                return json.dumps(resp.json(), ensure_ascii=False, indent=2)
            return f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to list recent tasks: %s", e)
            return f"Error: {e}"

    async def close(self):
        if self._client and not self._client.is_closed:
            await self._client.aclose()


# Singleton
kotlin_client = KotlinServerClient()
