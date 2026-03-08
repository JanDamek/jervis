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

import json
import logging

import httpx
from motor.motor_asyncio import AsyncIOMotorClient

from app.config import settings

logger = logging.getLogger(__name__)

# ── Async MongoDB handle (motor) ────────────────────────────────────────

_motor_client: AsyncIOMotorClient | None = None


async def get_mongo_db():
    """Return async MongoDB database handle for orchestrator collections.

    Uses motor (async) since callers are async (topic_tracker, consolidation).
    Reuses a single motor client across calls.
    """
    global _motor_client
    if _motor_client is None:
        _motor_client = AsyncIOMotorClient(settings.mongodb_url)
    return _motor_client.get_default_database()


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
        keep_environment_running: bool = False,
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
                    "keepEnvironmentRunning": keep_environment_running,
                },
            )
            return True
        except Exception as e:
            logger.warning("Failed to report status to Kotlin: %s", e)
            return False

    async def report_qualification_done(
        self,
        task_id: str,
        client_id: str,
        decision: str,
        priority_score: int = 5,
        reason: str = "",
        alert_message: str | None = None,
        context_summary: str = "",
        suggested_approach: str = "",
        action_type: str = "",
        estimated_complexity: str = "",
    ) -> bool:
        """Push qualification agent result to Kotlin server.

        Called after qualification LLM agent finishes analyzing KB results.
        Kotlin updates task state based on decision (DONE, QUEUED, URGENT_ALERT).
        """
        try:
            client = await self._get_client()
            resp = await client.post(
                "/internal/qualification-done",
                json={
                    "task_id": task_id,
                    "client_id": client_id,
                    "decision": decision,
                    "priority_score": priority_score,
                    "reason": reason,
                    "context_summary": context_summary,
                    "suggested_approach": suggested_approach,
                    "action_type": action_type,
                    "estimated_complexity": estimated_complexity,
                },
            )
            return resp.status_code == 200
        except Exception as e:
            logger.warning("Failed to report qualification done for task %s: %s", task_id, e)
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

    async def notify_agent_dispatched(
        self,
        task_id: str,
        job_name: str,
        workspace_path: str = "",
        agent_type: str = "claude",
    ) -> bool:
        """Notify Kotlin server that a coding agent K8s Job was dispatched.

        Sets task state to CODING with agentJobName, workspace path, and agent type.
        """
        try:
            client = await self._get_client()
            resp = await client.post(
                f"/internal/tasks/{task_id}/agent-dispatched",
                json={
                    "jobName": job_name,
                    "workspacePath": workspace_path,
                    "agentType": agent_type,
                },
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
    # Memory map change notification
    # ------------------------------------------------------------------

    async def notify_memory_map_changed(self) -> bool:
        """Notify Kotlin server that the memory map changed — triggers UI refresh.

        Called after vertex status changes during graph execution.
        Kotlin broadcasts MemoryMapChanged event to all connected UI clients.
        """
        try:
            client = await self._get_client()
            await client.post("/internal/memory-map-changed")
            return True
        except Exception as e:
            logger.debug("Failed to notify memory map changed: %s", e)
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

    async def create_work_plan(
        self,
        title: str,
        phases: list[dict],
        client_id: str,
        project_id: str | None = None,
    ) -> str:
        """Create a hierarchical work plan via Kotlin internal API.

        Creates a root task (BLOCKED) with child tasks organized in phases.
        Children are BLOCKED until their dependencies complete, then auto-unblocked
        by WorkPlanExecutor.
        """
        try:
            client = await self._get_client()
            resp = await client.post(
                "/internal/tasks/create-work-plan",
                json={
                    "title": title,
                    "phases": phases,
                    "clientId": client_id,
                    "projectId": project_id,
                },
            )
            if resp.status_code == 200:
                data = resp.json()
                root_id = data.get("rootTaskId", "?")
                child_count = data.get("childCount", 0)
                phase_count = data.get("phaseCount", 0)
                return (
                    f"Work plan vytvořen: {title}\n"
                    f"Root task: {root_id}\n"
                    f"Fáze: {phase_count}, Dílčích úkolů: {child_count}\n"
                    f"Úkoly se automaticky zpracují v pořadí dle závislostí."
                )
            return f"Error: {resp.status_code} — {resp.text[:200]}"
        except Exception as e:
            logger.warning("Failed to create work plan: %s", e)
            return f"Error: {e}"

    async def dispatch_coding_agent(
        self,
        task_description: str,
        client_id: str,
        project_id: str,
        plan: dict | None = None,
        guidelines_text: str | None = None,
        review_checklist: list[str] | None = None,
        agent_preference: str = "auto",
    ) -> str:
        """Dispatch coding agent via Kotlin internal API.

        EPIC 2-S5: Enhanced with guidelines, plan, and review checklist injection.
        These are appended to the task description so the coding agent sees them
        in its workspace instructions.

        agent_preference: "auto" (= Claude CLI), "claude", "kilo"
        """
        try:
            # Build enhanced workspace instructions
            workspace_instructions = task_description
            if guidelines_text:
                workspace_instructions += f"\n\n{guidelines_text}"
            if plan:
                from app.background.handler import _format_plan_for_context
                plan_text = _format_plan_for_context(plan)
                workspace_instructions += f"\n\n## Plan\n{plan_text}"
            if review_checklist:
                workspace_instructions += "\n\n## Review Checklist\n"
                workspace_instructions += "\n".join(f"- [ ] {item}" for item in review_checklist)

            payload = {
                "taskDescription": workspace_instructions,
                "clientId": client_id,
                "projectId": project_id,
            }
            if agent_preference and agent_preference != "auto":
                payload["agentPreference"] = agent_preference

            client = await self._get_client()
            resp = await client.post(
                "/internal/dispatch-coding-agent",
                json=payload,
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

                return json.dumps(resp.json(), ensure_ascii=False, indent=2)
            return f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to list recent tasks: %s", e)
            return f"Error: {e}"

    # -------------------------------------------------------------------
    # Guidelines API
    # -------------------------------------------------------------------

    async def get_merged_guidelines(
        self,
        client_id: str | None = None,
        project_id: str | None = None,
    ) -> dict:
        """Get merged guidelines for a client+project context (GLOBAL → CLIENT → PROJECT)."""
        try:
            client = await self._get_client()
            params: dict = {}
            if client_id:
                params["clientId"] = client_id
            if project_id:
                params["projectId"] = project_id
            resp = await client.get("/internal/guidelines/merged", params=params)
            if resp.status_code == 200:
                return resp.json()
            logger.warning("Failed to get merged guidelines: %s", resp.status_code)
            return {}
        except Exception as e:
            logger.warning("Failed to get merged guidelines: %s", e)
            return {}

    async def get_guidelines(
        self,
        scope: str = "GLOBAL",
        client_id: str | None = None,
        project_id: str | None = None,
    ) -> str:
        """Get guidelines for a specific scope (raw, unmerged)."""
        try:
            client = await self._get_client()
            params: dict = {"scope": scope}
            if client_id:
                params["clientId"] = client_id
            if project_id:
                params["projectId"] = project_id
            resp = await client.get("/internal/guidelines", params=params)
            if resp.status_code == 200:
                return json.dumps(resp.json(), ensure_ascii=False, indent=2)
            return f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to get guidelines: %s", e)
            return f"Error: {e}"

    async def update_guideline(
        self,
        scope: str,
        category: str,
        rules: dict,
        client_id: str | None = None,
        project_id: str | None = None,
    ) -> str:
        """Update a single category of guidelines for a given scope."""
        try:
            client = await self._get_client()
            payload = {
                "scope": scope,
                "clientId": client_id,
                "projectId": project_id,
                category: rules,
            }
            resp = await client.post(
                "/internal/guidelines",
                json=payload,
            )
            if resp.status_code == 200:
                return json.dumps(resp.json(), ensure_ascii=False, indent=2)
            return f"Error: {resp.status_code} - {resp.text}"
        except Exception as e:
            logger.warning("Failed to update guideline: %s", e)
            return f"Error: {e}"

    # -------------------------------------------------------------------
    # Filtering Rules API (EPIC 10)
    # -------------------------------------------------------------------

    async def set_filter_rule(
        self,
        source_type: str,
        condition_type: str,
        condition_value: str,
        action: str = "IGNORE",
        description: str | None = None,
        client_id: str | None = None,
        project_id: str | None = None,
    ) -> str:
        """Create a filtering rule via Kotlin internal API."""
        try:
            client = await self._get_client()
            payload = {
                "sourceType": source_type,
                "conditionType": condition_type,
                "conditionValue": condition_value,
                "action": action,
                "description": description,
                "clientId": client_id,
                "projectId": project_id,
            }
            resp = await client.post("/internal/filter-rules", json=payload)
            if resp.status_code == 200:
                return json.dumps(resp.json(), ensure_ascii=False, indent=2)
            return f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to create filter rule: %s", e)
            return f"Error: {e}"

    async def list_filter_rules(
        self,
        client_id: str | None = None,
        project_id: str | None = None,
    ) -> str:
        """List active filtering rules via Kotlin internal API."""
        try:
            client = await self._get_client()
            params: dict = {}
            if client_id:
                params["clientId"] = client_id
            if project_id:
                params["projectId"] = project_id
            resp = await client.get("/internal/filter-rules", params=params)
            if resp.status_code == 200:
                rules = resp.json()
                if not rules:
                    return "Žádná aktivní filtrační pravidla."
                return json.dumps(rules, ensure_ascii=False, indent=2)
            return f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to list filter rules: %s", e)
            return f"Error: {e}"

    async def remove_filter_rule(self, rule_id: str) -> str:
        """Remove a filtering rule by ID via Kotlin internal API."""
        try:
            client = await self._get_client()
            resp = await client.delete(f"/internal/filter-rules/{rule_id}")
            if resp.status_code == 200:
                return f"Pravidlo {rule_id} odstraněno."
            return f"Error: {resp.status_code}"
        except Exception as e:
            logger.warning("Failed to remove filter rule %s: %s", rule_id, e)
            return f"Error: {e}"

    async def invalidate_cache(self, collection: str) -> None:
        """Invalidate Kotlin in-memory cache for a collection after MongoDB write."""
        try:
            client = await self._get_client()
            resp = await client.post(
                "/internal/cache/invalidate",
                json={"collection": collection},
            )
            if resp.status_code != 200:
                logger.warning("Cache invalidation returned %d for %s", resp.status_code, collection)
        except Exception as e:
            logger.warning("Cache invalidation failed for %s: %s", collection, e)

    async def close(self):
        if self._client and not self._client.is_closed:
            await self._client.aclose()


# Singleton
kotlin_client = KotlinServerClient()
