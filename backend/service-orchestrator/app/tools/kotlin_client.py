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
            from app.grpc_server_client import server_orchestrator_progress_stub
            from jervis.common import types_pb2
            from jervis.server import orchestrator_progress_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            await server_orchestrator_progress_stub().OrchestratorProgress(
                orchestrator_progress_pb2.OrchestratorProgressRequest(
                    ctx=ctx,
                    task_id=task_id,
                    client_id=client_id,
                    node=node,
                    message=message,
                    percent=percent,
                    goal_index=goal_index,
                    total_goals=total_goals,
                    step_index=step_index,
                    total_steps=total_steps,
                ),
                timeout=5.0,
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
        keep_environment_running: bool = False,
    ) -> bool:
        """Push orchestrator status change to Kotlin server.

        Called when orchestration completes, errors, or gets interrupted.
        Kotlin handles state changes immediately (no polling needed).

        Status values: "done", "error", "interrupted"
        """
        try:
            from app.grpc_server_client import server_orchestrator_progress_stub
            from jervis.common import types_pb2
            from jervis.server import orchestrator_progress_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            await server_orchestrator_progress_stub().OrchestratorStatus(
                orchestrator_progress_pb2.OrchestratorStatusRequest(
                    ctx=ctx,
                    task_id=task_id,
                    thread_id=thread_id,
                    status=status,
                    summary=summary or "",
                    error=error or "",
                    interrupt_action=interrupt_action or "",
                    interrupt_description=interrupt_description or "",
                    branch=branch or "",
                    artifacts=artifacts or [],
                    keep_environment_running=keep_environment_running,
                ),
                timeout=5.0,
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
        target_task_id: str | None = None,
        context_summary: str = "",
        suggested_approach: str = "",
        action_type: str = "",
        estimated_complexity: str = "",
        # Phase 3 — re-entrant qualifier extensions
        pending_user_question: str | None = None,
        user_question_context: str | None = None,
        sub_tasks: list[dict] | None = None,
    ) -> bool:
        """Push qualification agent result to Kotlin server.

        Called after qualification LLM agent finishes analyzing KB results.
        Kotlin updates task state based on decision:
          - DONE / QUEUED / URGENT_ALERT / CONSOLIDATE (legacy)
          - ESCALATE  (Phase 3) — task → state=USER_TASK with question/context
          - DECOMPOSE (Phase 3) — break into sub_tasks, parent → BLOCKED
        """
        try:
            from app.grpc_server_client import server_orchestrator_progress_stub
            from jervis.common import types_pb2
            from jervis.server import orchestrator_progress_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            proto_subs = [
                orchestrator_progress_pb2.SubTaskSpec(
                    task_name=st.get("task_name", ""),
                    content=st.get("content", ""),
                    phase=st.get("phase") or "",
                    order_in_phase=int(st.get("order_in_phase") or 0),
                )
                for st in (sub_tasks or [])
            ]
            resp = await server_orchestrator_progress_stub().QualificationDone(
                orchestrator_progress_pb2.QualificationDoneRequest(
                    ctx=ctx,
                    task_id=task_id,
                    client_id=client_id,
                    decision=decision,
                    priority_score=priority_score,
                    reason=reason,
                    alert_message=alert_message or "",
                    target_task_id=target_task_id or "",
                    context_summary=context_summary,
                    suggested_approach=suggested_approach,
                    action_type=action_type,
                    estimated_complexity=estimated_complexity,
                    pending_user_question=pending_user_question or "",
                    user_question_context=user_question_context or "",
                    sub_tasks=proto_subs,
                ),
                timeout=10.0,
            )
            return resp.ok
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
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().AgentDispatched(
                task_api_pb2.AgentDispatchedRequest(
                    ctx=ctx,
                    task_id=task_id,
                    job_name=job_name,
                    workspace_path=workspace_path or "",
                    agent_type=agent_type or "",
                ),
                timeout=10.0,
            )
            return resp.ok
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
    # Memory graph change notification
    # ------------------------------------------------------------------

    async def notify_memory_graph_changed(self) -> bool:
        """Notify Kotlin server that the memory graph changed — triggers UI refresh."""
        try:
            from app.grpc_server_client import server_orchestrator_progress_stub
            from jervis.common import types_pb2
            from jervis.server import orchestrator_progress_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            await server_orchestrator_progress_stub().MemoryGraphChanged(
                orchestrator_progress_pb2.MemoryGraphChangedRequest(ctx=ctx),
                timeout=5.0,
            )
            return True
        except Exception as e:
            logger.debug("Failed to notify memory graph changed: %s", e)
            return False

    # ------------------------------------------------------------------
    # Chat foreground preemption
    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # Chat approval — remote notification broadcast
    # ------------------------------------------------------------------

    async def broadcast_chat_approval_request(
        self,
        approval_id: str,
        action: str,
        tool: str,
        preview: str,
        client_id: str | None,
        project_id: str | None = None,
        session_id: str | None = None,
    ) -> bool:
        """Broadcast a pending chat approval via Kotlin NotificationRpcImpl.

        Fire-and-forget. Kotlin emits JervisEvent.UserTaskCreated(isApproval=true)
        on the single remote notification channel, so desktop, iOS, watch — any
        device subscribed to clientId — will show ApprovalNotificationDialog.

        FCM/APNs fallback kicks in automatically when no active WebSocket
        subscribers (existing NotificationRpcImpl path).
        """
        if not client_id:
            logger.debug("broadcast_chat_approval_request: no client_id, skipping")
            return False
        try:
            from app.grpc_server_client import server_chat_approval_stub
            from jervis.server import chat_approval_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            await server_chat_approval_stub().Broadcast(
                chat_approval_pb2.ApprovalBroadcastRequest(
                    ctx=ctx,
                    approval_id=approval_id,
                    action=action,
                    tool=tool,
                    preview=preview,
                    client_id=client_id,
                    project_id=project_id or "",
                    session_id=session_id or "",
                ),
                timeout=5.0,
            )
            return True
        except Exception as e:
            logger.warning("broadcast_chat_approval_request failed: %s", e)
            return False

    async def broadcast_chat_approval_resolved(
        self,
        approval_id: str,
        approved: bool,
        action: str,
        client_id: str | None,
    ) -> bool:
        """Tell Kotlin that an approval was resolved → dismiss stale dialogs elsewhere."""
        if not client_id:
            return False
        try:
            from app.grpc_server_client import server_chat_approval_stub
            from jervis.server import chat_approval_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            await server_chat_approval_stub().Resolved(
                chat_approval_pb2.ApprovalResolvedRequest(
                    ctx=ctx,
                    approval_id=approval_id,
                    approved=approved,
                    action=action,
                    client_id=client_id,
                ),
                timeout=5.0,
            )
            return True
        except Exception as e:
            logger.debug("broadcast_chat_approval_resolved failed: %s", e)
            return False

    async def register_foreground_start(self) -> bool:
        """Register foreground chat start — preempts background tasks."""
        try:
            from app.grpc_server_client import server_foreground_stub
            from jervis.common import types_pb2
            from jervis.server import foreground_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            await server_foreground_stub().ForegroundStart(
                foreground_pb2.ForegroundStartRequest(ctx=ctx), timeout=5.0,
            )
            return True
        except Exception as e:
            logger.warning("Failed to register foreground start: %s", e)
            return False

    async def register_foreground_end(self) -> bool:
        """Register foreground chat end — allows background tasks to resume."""
        try:
            from app.grpc_server_client import server_foreground_stub
            from jervis.common import types_pb2
            from jervis.server import foreground_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            await server_foreground_stub().ForegroundEnd(
                foreground_pb2.ForegroundEndRequest(ctx=ctx), timeout=5.0,
            )
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
        """Create a background task via gRPC."""
        try:
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().CreateBackgroundTask(
                task_api_pb2.CreateBackgroundTaskRequest(
                    ctx=ctx,
                    title=title,
                    description=description,
                    client_id=client_id,
                    project_id=project_id or "",
                    priority=priority,
                ),
                timeout=30.0,
            )
            return json.dumps({"taskId": resp.task_id, "title": resp.title})
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
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            proto_phases = [
                task_api_pb2.WorkPlanPhase(
                    name=p.get("name", ""),
                    tasks=[
                        task_api_pb2.WorkPlanTask(
                            title=t.get("title", ""),
                            description=t.get("description", ""),
                            action_type=t.get("actionType", "") or "",
                            depends_on=t.get("dependsOn") or [],
                        )
                        for t in (p.get("tasks") or [])
                    ],
                )
                for p in phases
            ]
            resp = await server_task_api_stub().CreateWorkPlan(
                task_api_pb2.CreateWorkPlanRequest(
                    ctx=ctx,
                    title=title,
                    client_id=client_id,
                    project_id=project_id or "",
                    phases=proto_phases,
                ),
                timeout=60.0,
            )
            if resp.ok:
                return (
                    f"Work plan vytvořen: {title}\n"
                    f"Root task: {resp.root_task_id}\n"
                    f"Fáze: {resp.phase_count}, Dílčích úkolů: {resp.child_count}\n"
                    f"Úkoly se automaticky zpracují v pořadí dle závislostí."
                )
            return f"Error: {resp.error}"
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

            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().DispatchCodingAgent(
                task_api_pb2.DispatchCodingAgentRequest(
                    ctx=ctx,
                    task_description=workspace_instructions,
                    client_id=client_id,
                    project_id=project_id,
                    agent_preference=agent_preference or "auto",
                ),
                timeout=30.0,
            )
            if not resp.dispatched:
                return f"Error: {resp.error or 'dispatch failed'}"
            return json.dumps({"taskId": resp.task_id, "dispatched": True})
        except Exception as e:
            logger.warning("Failed to dispatch coding agent: %s", e)
            return f"Error: {e}"

    async def search_user_tasks(
        self,
        query: str,
        max_results: int = 5,
    ) -> str:
        """Search user_tasks via gRPC."""
        try:
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().ListUserTasks(
                task_api_pb2.ListUserTasksRequest(
                    ctx=ctx,
                    query=query,
                    max_results=max_results,
                ),
                timeout=15.0,
            )
            return resp.items_json or "[]"
        except Exception as e:
            logger.warning("Failed to search user tasks: %s", e)
            return f"Error: {e}"

    async def respond_to_user_task(
        self,
        task_id: str,
        response: str,
    ) -> str:
        """Respond to user_task via gRPC."""
        try:
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().RespondToUserTask(
                task_api_pb2.RespondToUserTaskRequest(
                    ctx=ctx,
                    task_id=task_id,
                    response=response,
                ),
                timeout=10.0,
            )
            return json.dumps({"ok": resp.ok, "taskId": resp.task_id, "error": resp.error})
        except Exception as e:
            logger.warning("Failed to respond to user task: %s", e)
            return f"Error: {e}"

    async def send_push_notification(
        self,
        client_id: str,
        title: str,
        body: str,
        data: dict | None = None,
    ) -> str:
        """Send push notification to all registered devices for a client."""
        try:
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().PushNotification(
                task_api_pb2.PushNotificationRequest(
                    ctx=ctx,
                    client_id=client_id,
                    title=title,
                    body=body,
                    data=data or {},
                ),
                timeout=10.0,
            )
            return json.dumps({"ok": resp.ok, "fcm": resp.fcm, "apns": resp.apns})
        except Exception as e:
            logger.warning("Failed to send push notification: %s", e)
            return f"Error: {e}"

    async def mark_task_done(self, task_id: str, note: str | None = None) -> str:
        """Mark a task as DONE. Available to both user UI and JERVIS agent."""
        try:
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().MarkDone(
                task_api_pb2.TaskNoteRequest(ctx=ctx, task_id=task_id, note=note or ""),
                timeout=10.0,
            )
            return json.dumps({"ok": resp.ok, "taskId": resp.task_id, "state": resp.state, "error": resp.error})
        except Exception as e:
            logger.warning("Failed to mark task done %s: %s", task_id, e)
            return f"Error: {e}"

    async def reopen_task(self, task_id: str, note: str | None = None) -> str:
        """Reopen a DONE task. Transitions to NEW + needsQualification=true."""
        try:
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().Reopen(
                task_api_pb2.TaskNoteRequest(ctx=ctx, task_id=task_id, note=note or ""),
                timeout=10.0,
            )
            return json.dumps({"ok": resp.ok, "taskId": resp.task_id, "state": resp.state, "error": resp.error})
        except Exception as e:
            logger.warning("Failed to reopen task %s: %s", task_id, e)
            return f"Error: {e}"

    async def dismiss_user_tasks(self, task_ids: list[str]) -> str:
        """Dismiss user_tasks — move to DONE without processing."""
        try:
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().DismissUserTasks(
                task_api_pb2.DismissUserTasksRequest(ctx=ctx, task_ids=task_ids),
                timeout=10.0,
            )
            return json.dumps({"ok": resp.ok, "dismissed": resp.dismissed})
        except Exception as e:
            logger.warning("Failed to dismiss user tasks: %s", e)
            return f"Error: {e}"

    async def get_user_task(self, task_id: str) -> dict | None:
        """Get a user_task by ID for context loading."""
        try:
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().GetUserTask(
                task_api_pb2.TaskIdRequest(ctx=ctx, task_id=task_id),
                timeout=10.0,
            )
            if not resp.ok:
                return None
            return {
                "id": resp.id,
                "title": resp.title,
                "state": resp.state,
                "question": resp.question,
                "context": resp.context,
                "clientId": resp.client_id,
            }
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
        """Classify meeting via gRPC."""
        try:
            from app.grpc_server_client import server_meetings_stub
            from jervis.common import types_pb2
            from jervis.server import meetings_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_meetings_stub().ClassifyMeeting(
                meetings_pb2.ClassifyMeetingRequest(
                    ctx=ctx,
                    meeting_id=meeting_id,
                    client_id=client_id,
                    project_id=project_id or "",
                    title=title or "",
                ),
                timeout=30.0,
            )
            return json.dumps({"ok": resp.ok, "meetingId": resp.meeting_id, "error": resp.error})
        except Exception as e:
            logger.warning("Failed to classify meeting: %s", e)
            return f"Error: {e}"

    async def list_unclassified_meetings(self) -> str:
        """List unclassified meetings via Kotlin gRPC."""
        try:
            from app.grpc_server_client import server_meetings_stub
            from jervis.server import meetings_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_meetings_stub().ListUnclassified(
                meetings_pb2.ListUnclassifiedRequest(ctx=ctx),
                timeout=5.0,
            )
            rows = [
                {
                    "id": m.id,
                    "title": m.title,
                    "startedAt": m.started_at_iso,
                    "durationSeconds": m.duration_seconds,
                }
                for m in resp.meetings
            ]
            return json.dumps(rows, ensure_ascii=False, indent=2)
        except Exception as e:
            logger.warning("Failed to list unclassified meetings: %s", e)
            return f"Error: {e}"

    async def get_meeting_transcript(self, meeting_id: str) -> str:
        """Get meeting transcript (corrected preferred) via Kotlin gRPC."""
        try:
            from app.grpc_server_client import server_meetings_stub
            from jervis.server import meetings_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_meetings_stub().GetTranscript(
                meetings_pb2.GetTranscriptRequest(ctx=ctx, meeting_id=meeting_id),
                timeout=10.0,
            )
            title = resp.title
            state = resp.state
            transcript = resp.transcript
            fmt = resp.format or "text"
            if not transcript:
                return f"Meeting '{title}' ({state}): transcript not available yet."
            header = f"# {title}\nState: {state} | Format: {fmt}\n\n"
            return header + transcript
        except Exception as e:
            logger.warning("Failed to get meeting transcript %s: %s", meeting_id, e)
            return f"Error: {e}"

    async def list_meetings(
        self,
        client_id: str = "",
        project_id: str | None = None,
        state: str | None = None,
        limit: int = 20,
    ) -> str:
        """List meetings via Kotlin gRPC."""
        try:
            from app.grpc_server_client import server_meetings_stub
            from jervis.server import meetings_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_meetings_stub().ListMeetings(
                meetings_pb2.ListMeetingsRequest(
                    ctx=ctx,
                    client_id=client_id or "",
                    project_id=project_id or "",
                    state=state or "",
                    limit=limit,
                ),
                timeout=10.0,
            )
            if not resp.meetings:
                return "No meetings found."
            lines = []
            for m in resp.meetings:
                dur_str = f" ({m.duration_seconds}s)" if m.duration_seconds else ""
                lines.append(
                    f"- {m.title or '?'} (id={m.id}) [{m.state or '?'}] "
                    f"{m.started_at_iso}{dur_str}"
                )
            return "\n".join(lines)
        except Exception as e:
            logger.warning("Failed to list meetings: %s", e)
            return f"Error: {e}"

    # ------------------------------------------------------------------
    # Chat runtime context (for system prompt enrichment)
    # ------------------------------------------------------------------

    async def get_clients_projects(self) -> list[dict]:
        """Get all clients with their projects (id, name) for LLM scope resolution."""
        try:
            from app.grpc_server_client import server_chat_context_stub
            from jervis.server import chat_context_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_chat_context_stub().ListClientsProjects(
                chat_context_pb2.ClientsProjectsRequest(ctx=ctx),
                timeout=5.0,
            )
            return [
                {
                    "id": c.id,
                    "name": c.name,
                    "projects": [{"id": p.id, "name": p.name} for p in c.projects],
                }
                for c in resp.clients
            ]
        except Exception as e:
            logger.warning("Failed to get clients-projects: %s", e)
            return []

    async def get_pending_user_tasks_summary(self, limit: int = 3) -> dict:
        """Get pending user tasks count + top N for proactive mentions."""
        try:
            from app.grpc_server_client import server_chat_context_stub
            from jervis.server import chat_context_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_chat_context_stub().PendingUserTasksSummary(
                chat_context_pb2.PendingUserTasksRequest(ctx=ctx, limit=limit),
                timeout=5.0,
            )
            return {
                "count": resp.count,
                "tasks": [
                    {
                        "id": t.id,
                        "title": t.title,
                        "question": t.question,
                        "clientId": t.client_id,
                        "projectId": t.project_id,
                    }
                    for t in resp.tasks
                ],
            }
        except Exception as e:
            logger.warning("Failed to get pending user tasks summary: %s", e)
            return {"count": 0, "tasks": []}

    async def count_unclassified_meetings(self) -> int:
        """Get count of unclassified meetings."""
        try:
            from app.grpc_server_client import server_chat_context_stub
            from jervis.server import chat_context_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_chat_context_stub().UnclassifiedMeetingsCount(
                chat_context_pb2.UnclassifiedCountRequest(ctx=ctx),
                timeout=5.0,
            )
            return resp.count
        except Exception as e:
            logger.warning("Failed to count unclassified meetings: %s", e)
            return 0

    async def get_user_timezone(self) -> str:
        """Get user timezone from GLOBAL preference (default: Europe/Prague)."""
        try:
            from app.grpc_server_client import server_chat_context_stub
            from jervis.server import chat_context_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_chat_context_stub().GetUserTimezone(
                chat_context_pb2.UserTimezoneRequest(ctx=ctx),
                timeout=5.0,
            )
            return resp.timezone or "Europe/Prague"
        except Exception as e:
            logger.warning("Failed to get user timezone: %s", e)
            return "Europe/Prague"

    # ------------------------------------------------------------------
    # Task tools (for chat agent)
    # ------------------------------------------------------------------

    async def get_task_status(self, task_id: str) -> str:
        """Get task status by ID."""
        try:
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().GetTaskStatus(
                task_api_pb2.TaskIdRequest(ctx=ctx, task_id=task_id),
                timeout=10.0,
            )
            if not resp.ok:
                return f"Task not found: {task_id}"
            return json.dumps(
                {
                    "id": resp.id,
                    "title": resp.title,
                    "state": resp.state,
                    "content": resp.content,
                    "clientId": resp.client_id,
                    "projectId": resp.project_id,
                    "createdAt": resp.created_at,
                    "processingMode": resp.processing_mode,
                    "question": resp.question,
                    "errorMessage": resp.error_message,
                },
                ensure_ascii=False,
                indent=2,
            )
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
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().SearchTasks(
                task_api_pb2.SearchTasksRequest(
                    ctx=ctx,
                    query=query,
                    state=(state if state and state != "all" else ""),
                    limit=max_results,
                ),
                timeout=15.0,
            )
            return resp.items_json or "[]"
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
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().RecentTasks(
                task_api_pb2.RecentTasksRequest(
                    ctx=ctx,
                    limit=limit,
                    state=state or "",
                    since=since or "today",
                    client_id=client_id or "",
                ),
                timeout=15.0,
            )
            return resp.items_json or "[]"
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
            from app.grpc_server_client import server_guidelines_stub
            from jervis.server import guidelines_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_guidelines_stub().GetMerged(
                guidelines_pb2.GetMergedRequest(
                    ctx=ctx,
                    client_id=client_id or "",
                    project_id=project_id or "",
                ),
                timeout=5.0,
            )
            return json.loads(resp.body_json) if resp.body_json else {}
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
            from app.grpc_server_client import server_guidelines_stub
            from jervis.server import guidelines_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            scope_enum_map = {
                "GLOBAL": guidelines_pb2.GUIDELINES_SCOPE_GLOBAL,
                "CLIENT": guidelines_pb2.GUIDELINES_SCOPE_CLIENT,
                "PROJECT": guidelines_pb2.GUIDELINES_SCOPE_PROJECT,
            }
            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_guidelines_stub().Get(
                guidelines_pb2.GetRequest(
                    ctx=ctx,
                    scope=scope_enum_map.get(scope, guidelines_pb2.GUIDELINES_SCOPE_GLOBAL),
                    client_id=client_id or "",
                    project_id=project_id or "",
                ),
                timeout=5.0,
            )
            if not resp.body_json:
                return "{}"
            doc = json.loads(resp.body_json)
            return json.dumps(doc, ensure_ascii=False, indent=2)
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
            from app.grpc_server_client import server_guidelines_stub
            from jervis.server import guidelines_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            payload = {
                "scope": scope,
                "clientId": client_id,
                "projectId": project_id,
                category: rules,
            }
            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_guidelines_stub().Set(
                guidelines_pb2.SetRequest(ctx=ctx, update_json=json.dumps(payload)),
                timeout=10.0,
            )
            if resp.body_json:
                return json.dumps(json.loads(resp.body_json), ensure_ascii=False, indent=2)
            return ""
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
        """Create a filtering rule via Kotlin gRPC."""
        try:
            from app.grpc_server_client import server_filter_rules_stub
            from jervis.server import filter_rules_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_filter_rules_stub().Create(
                filter_rules_pb2.CreateFilterRuleRequest(
                    ctx=ctx,
                    source_type=source_type,
                    condition_type=condition_type,
                    condition_value=condition_value,
                    action=action,
                    description=description or "",
                    client_id=client_id or "",
                    project_id=project_id or "",
                ),
                timeout=5.0,
            )
            return json.dumps(json.loads(resp.body_json), ensure_ascii=False, indent=2) if resp.body_json else ""
        except Exception as e:
            logger.warning("Failed to create filter rule: %s", e)
            return f"Error: {e}"

    async def list_filter_rules(
        self,
        client_id: str | None = None,
        project_id: str | None = None,
    ) -> str:
        """List active filtering rules via Kotlin gRPC."""
        try:
            from app.grpc_server_client import server_filter_rules_stub
            from jervis.server import filter_rules_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_filter_rules_stub().List(
                filter_rules_pb2.ListFilterRulesRequest(
                    ctx=ctx,
                    client_id=client_id or "",
                    project_id=project_id or "",
                ),
                timeout=5.0,
            )
            rules = json.loads(resp.body_json) if resp.body_json else []
            if not rules:
                return "Žádná aktivní filtrační pravidla."
            return json.dumps(rules, ensure_ascii=False, indent=2)
        except Exception as e:
            logger.warning("Failed to list filter rules: %s", e)
            return f"Error: {e}"

    async def remove_filter_rule(self, rule_id: str) -> str:
        """Remove a filtering rule by ID via Kotlin gRPC."""
        try:
            from app.grpc_server_client import server_filter_rules_stub
            from jervis.server import filter_rules_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_filter_rules_stub().Remove(
                filter_rules_pb2.RemoveFilterRuleRequest(ctx=ctx, rule_id=rule_id),
                timeout=5.0,
            )
            return f"Pravidlo {rule_id} odstraněno." if resp.removed else f"Pravidlo {rule_id} nenalezeno."
        except Exception as e:
            logger.warning("Failed to remove filter rule %s: %s", rule_id, e)
            return f"Error: {e}"

    # ------------------------------------------------------------------
    # Merge Request / Pull Request operations
    # ------------------------------------------------------------------

    async def create_merge_request(
        self,
        task_id: str,
        branch: str,
        target_branch: str | None = None,
        title: str = "",
        description: str | None = None,
    ) -> dict:
        """Create MR/PR via gRPC (resolves provider from project)."""
        try:
            from app.grpc_server_client import server_merge_request_stub
            from jervis.common import types_pb2
            from jervis.server import merge_request_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_merge_request_stub().CreateMergeRequest(
                merge_request_pb2.CreateMergeRequestRequest(
                    ctx=ctx,
                    task_id=task_id,
                    branch=branch,
                    target_branch=target_branch or "",
                    title=title or f"Coding: {task_id[:12]}",
                    description=description or "",
                ),
                timeout=60.0,
            )
            if resp.ok:
                return {"ok": True, "url": resp.url}
            logger.warning("create_merge_request failed: %s", resp.error)
            return {"ok": False, "error": resp.error}
        except Exception as e:
            logger.warning("Failed to create MR for task %s: %s", task_id, e)
            return {"ok": False, "error": str(e)}

    async def get_merge_request_diff(self, task_id: str) -> list[dict] | None:
        """Fetch MR/PR diff via gRPC. Returns list of diff entries or None on error."""
        try:
            from app.grpc_server_client import server_merge_request_stub
            from jervis.common import types_pb2
            from jervis.server import merge_request_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_merge_request_stub().GetMergeRequestDiff(
                merge_request_pb2.GetMergeRequestDiffRequest(
                    ctx=ctx,
                    task_id=task_id,
                ),
                timeout=60.0,
            )
            if resp.ok:
                return [
                    {
                        "oldPath": d.old_path,
                        "newPath": d.new_path,
                        "newFile": d.new_file,
                        "deletedFile": d.deleted_file,
                        "renamedFile": d.renamed_file,
                        "diff": d.diff,
                    }
                    for d in resp.diffs
                ]
            logger.warning("get_merge_request_diff failed: %s", resp.error)
            return None
        except Exception as e:
            logger.warning("Failed to get MR diff for task %s: %s", task_id, e)
            return None

    async def get_review_language(self, client_id: str, project_id: str | None = None) -> str:
        """Resolve review language via gRPC (project → group → client → default)."""
        try:
            from app.grpc_server_client import server_merge_request_stub
            from jervis.common import types_pb2
            from jervis.server import merge_request_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_merge_request_stub().ResolveReviewLanguage(
                merge_request_pb2.ResolveReviewLanguageRequest(
                    ctx=ctx,
                    client_id=client_id,
                    project_id=project_id or "",
                ),
                timeout=10.0,
            )
            return resp.language or "English"
        except Exception as e:
            logger.debug("Failed to resolve review language: %s", e)
        return "English"

    async def post_mr_inline_comments(
        self,
        task_id: str,
        summary: str,
        verdict: str = "COMMENT",
        comments: list[dict] | None = None,
        merge_request_url: str | None = None,
    ) -> bool:
        """Post inline review comments on MR/PR (file:line level)."""
        try:
            from app.grpc_server_client import server_merge_request_stub
            from jervis.common import types_pb2
            from jervis.server import merge_request_pb2
            from jervis_contracts.interceptors import prepare_context

            proto_comments = [
                merge_request_pb2.InlineComment(
                    file=c.get("file", ""),
                    line=int(c.get("line") or 0),
                    body=c.get("body", ""),
                )
                for c in (comments or [])
            ]
            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_merge_request_stub().PostMrInlineComments(
                merge_request_pb2.PostMrInlineCommentsRequest(
                    ctx=ctx,
                    task_id=task_id,
                    summary=summary,
                    verdict=verdict,
                    merge_request_url=merge_request_url or "",
                    comments=proto_comments,
                ),
                timeout=60.0,
            )
            if not resp.ok:
                logger.warning("post_mr_inline_comments failed: %s", resp.error)
            return resp.ok
        except Exception as e:
            logger.warning("Failed to post inline comments for task %s: %s", task_id, e)
            return False

    async def post_mr_comment(
        self,
        task_id: str,
        comment: str,
        merge_request_url: str | None = None,
    ) -> bool:
        """Post a comment on an existing MR/PR via gRPC."""
        try:
            from app.grpc_server_client import server_merge_request_stub
            from jervis.common import types_pb2
            from jervis.server import merge_request_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_merge_request_stub().PostMrComment(
                merge_request_pb2.PostMrCommentRequest(
                    ctx=ctx,
                    task_id=task_id,
                    comment=comment,
                    merge_request_url=merge_request_url or "",
                ),
                timeout=60.0,
            )
            if not resp.ok:
                logger.warning("post_mr_comment failed: %s", resp.error)
            return resp.ok
        except Exception as e:
            logger.warning("Failed to post MR comment for task %s: %s", task_id, e)
            return False

    async def retry_failed_task(self, task_id: str) -> str:
        """Retry a failed (ERROR) task — resets state to QUEUED for re-processing."""
        try:
            from app.grpc_server_client import server_task_api_stub
            from jervis.common import types_pb2
            from jervis.server import task_api_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            resp = await server_task_api_stub().RetryTask(
                task_api_pb2.TaskIdRequest(ctx=ctx, task_id=task_id),
                timeout=10.0,
            )
            return json.dumps(
                {"ok": resp.ok, "taskId": resp.task_id, "state": resp.state, "error": resp.error},
                ensure_ascii=False,
            )
        except Exception as e:
            logger.warning("Failed to retry task %s: %s", task_id, e)
            return f"Error: {e}"

    # ------------------------------------------------------------------
    # Thinking graph push to chat
    # ------------------------------------------------------------------

    async def notify_thinking_graph_update(
        self,
        task_id: str,
        task_title: str,
        graph_id: str = "",
        status: str = "vertex_completed",
        message: str = "",
        metadata: dict[str, str] | None = None,
    ) -> bool:
        """Push thinking graph update to Kotlin → chat stream.

        Status values: "started", "vertex_completed", "completed", "failed"
        Terminal states (started/completed/failed) are persisted to chat history.
        """
        try:
            from app.grpc_server_client import server_orchestrator_progress_stub
            from jervis.common import types_pb2
            from jervis.server import orchestrator_progress_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            await server_orchestrator_progress_stub().ThinkingGraphUpdate(
                orchestrator_progress_pb2.ThinkingGraphUpdateRequest(
                    ctx=ctx,
                    task_id=task_id,
                    task_title=task_title,
                    graph_id=graph_id or "",
                    status=status,
                    message=message,
                    metadata=metadata or {},
                ),
                timeout=5.0,
            )
            return True
        except Exception as e:
            logger.debug("Failed to push thinking graph update: %s", e)
            return False

    async def invalidate_cache(self, collection: str) -> None:
        """Invalidate Kotlin in-memory cache for a collection after MongoDB write."""
        try:
            from app.grpc_server_client import server_cache_stub
            from jervis.server import cache_pb2
            from jervis.common import types_pb2
            from jervis_contracts.interceptors import prepare_context

            ctx = types_pb2.RequestContext()
            prepare_context(ctx)
            await server_cache_stub().Invalidate(
                cache_pb2.CacheInvalidateRequest(ctx=ctx, collection=collection),
                timeout=5.0,
            )
        except Exception as e:
            logger.warning("Cache invalidation failed for %s: %s", collection, e)

    async def close(self):
        if self._client and not self._client.is_closed:
            await self._client.aclose()


# Singleton
kotlin_client = KotlinServerClient()
