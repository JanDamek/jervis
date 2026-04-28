"""gRPC server for `service-orchestrator`.

Phase 3 surface — OrchestratorControlService covers the short-payload
control RPCs (health, status, approve, cancel, interrupt). Long-running
orchestration events continue to stream back via the Phase-1
`ServerOrchestratorCallbackService` (KbProgress / OrchestratorProgress
callbacks). Subsequent slices will add graph / chat / voice / companion
servicers to the same bootstrap.
"""

from __future__ import annotations

import asyncio
import datetime
import json
import logging
from typing import TYPE_CHECKING

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.orchestrator import chat_pb2, chat_pb2_grpc
from jervis.orchestrator import companion_pb2, companion_pb2_grpc
from jervis.orchestrator import control_pb2, control_pb2_grpc
from jervis.orchestrator import dashboard_pb2, dashboard_pb2_grpc
from jervis.orchestrator import dispatch_pb2, dispatch_pb2_grpc
from jervis.orchestrator import graph_pb2, graph_pb2_grpc
from jervis.orchestrator import job_logs_pb2, job_logs_pb2_grpc
from jervis.orchestrator import meeting_helper_pb2, meeting_helper_pb2_grpc
from jervis.orchestrator import proposal_pb2, proposal_pb2_grpc
from jervis.orchestrator import voice_pb2, voice_pb2_grpc
from jervis_contracts.interceptors import ServerContextInterceptor

if TYPE_CHECKING:
    from fastapi import FastAPI

logger = logging.getLogger("orchestrator.grpc")


class OrchestratorControlServicer(control_pb2_grpc.OrchestratorControlServiceServicer):
    """OrchestratorControlService implementation.

    Delegates to the same handlers the FastAPI routes used to call so
    behavior matches byte-for-byte. The shared `_active_tasks` dict on
    `app.main` is dereferenced lazily because that module owns the
    asyncio task lifecycle.
    """

    async def Health(
        self,
        request: control_pb2.HealthRequest,
        context: grpc.aio.ServicerContext,
    ) -> control_pb2.HealthResponse:
        from app import main as orch_main

        return control_pb2.HealthResponse(
            status="ok",
            service="orchestrator",
            active_tasks=len(orch_main._active_tasks),
        )

    async def GetStatus(
        self,
        request: control_pb2.StatusRequest,
        context: grpc.aio.ServicerContext,
    ) -> control_pb2.StatusResponse:
        thread_id = request.thread_id
        if not thread_id:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "thread_id required")

        from app import main as orch_main
        from app.agent.langgraph_runner import _get_compiled_graph

        compiled = _get_compiled_graph()
        config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 200}
        graph_state = await compiled.aget_state(config)

        if graph_state is None:
            return control_pb2.StatusResponse(status="unknown", thread_id=thread_id)

        if graph_state.next:
            interrupt_data = None
            if graph_state.tasks:
                for task in graph_state.tasks:
                    if hasattr(task, "interrupts") and task.interrupts:
                        interrupt_data = task.interrupts[0].value
                        break
            if interrupt_data:
                return control_pb2.StatusResponse(
                    status="interrupted",
                    thread_id=thread_id,
                    interrupt_action=str(interrupt_data.get("action", "unknown") or ""),
                    interrupt_description=str(interrupt_data.get("description", "") or ""),
                )
            if thread_id not in orch_main._active_tasks:
                logger.warning(
                    "Stale thread detected: %s has pending nodes but no active task (pod restarted?)",
                    thread_id,
                )
                return control_pb2.StatusResponse(
                    status="error",
                    thread_id=thread_id,
                    error="Orchestrace přerušena restartem — úloha bude automaticky obnovena",
                )
            return control_pb2.StatusResponse(status="running", thread_id=thread_id)

        values = graph_state.values or {}
        error = values.get("error")
        if error:
            return control_pb2.StatusResponse(status="error", thread_id=thread_id, error=str(error))
        final_result = values.get("final_result")
        if final_result:
            return control_pb2.StatusResponse(
                status="done",
                thread_id=thread_id,
                summary=str(final_result or ""),
                branch=str(values.get("branch") or ""),
                artifacts=[str(a) for a in (values.get("artifacts") or [])],
                keep_environment_running=bool(values.get("keep_environment_running", False)),
            )
        return control_pb2.StatusResponse(status="unknown", thread_id=thread_id)

    async def Approve(
        self,
        request: control_pb2.ApproveRequest,
        context: grpc.aio.ServicerContext,
    ) -> control_pb2.ApproveAck:
        thread_id = request.thread_id
        if not thread_id:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, "thread_id required")

        from app import main as orch_main
        from app.agent.langgraph_runner import _get_compiled_graph
        from app.tools.kotlin_client import kotlin_client
        from langgraph.types import Command

        approved = bool(request.approved)
        reason = request.reason or ""
        modification = request.modification or None

        logger.info(
            "APPROVE_START | thread_id=%s | approved=%s | reason=%s",
            thread_id, approved, reason,
        )

        resume_value = {"approved": approved, "reason": reason, "modification": modification}

        async def _run_resume():
            try:
                compiled = _get_compiled_graph()
                config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 200}

                existing = await compiled.aget_state(config)
                if not existing or not existing.values or "task" not in existing.values:
                    raise ValueError(
                        f"No valid checkpoint for thread {thread_id} — "
                        f"thread may be stale",
                    )

                final_state = await compiled.ainvoke(
                    Command(resume=resume_value), config=config,
                )
                parts = thread_id.split("-")
                task_id = parts[1] if len(parts) >= 2 else thread_id
                await kotlin_client.report_status_change(
                    task_id=task_id,
                    thread_id=thread_id,
                    status="done",
                    summary=final_state.get("final_result", ""),
                    branch=final_state.get("branch"),
                    artifacts=final_state.get("artifacts", []),
                    keep_environment_running=final_state.get("keep_environment_running", False),
                )
            except asyncio.CancelledError:
                logger.info("APPROVE_INTERRUPTED | thread_id=%s — preempted", thread_id)
            except Exception as e:
                logger.exception("APPROVE_FAILED | thread_id=%s: %s", thread_id, e)
                parts = thread_id.split("-")
                task_id = parts[1] if len(parts) >= 2 else thread_id
                await kotlin_client.report_status_change(
                    task_id=task_id,
                    thread_id=thread_id,
                    status="error",
                    error=str(e),
                )
            finally:
                orch_main._active_tasks.pop(thread_id, None)

        task = asyncio.create_task(_run_resume())
        orch_main._active_tasks[thread_id] = task
        return control_pb2.ApproveAck(status="resuming")

    async def Cancel(
        self,
        request: control_pb2.ThreadRequest,
        context: grpc.aio.ServicerContext,
    ) -> control_pb2.CancelAck:
        thread_id = request.thread_id
        from app import main as orch_main

        task = orch_main._active_tasks.get(thread_id)
        if not task:
            await context.abort(grpc.StatusCode.NOT_FOUND, f"No active task for {thread_id}")

        parts = thread_id.split("-")
        cancel_task_id = parts[1] if len(parts) >= 2 else thread_id

        try:
            from app.agent.models import GraphStatus

            graph  = None
            if graph and graph.status not in (GraphStatus.COMPLETED, GraphStatus.FAILED):
                graph.status = GraphStatus.CANCELLED
                logger.info("Marked graph %s as CANCELLED", graph.id)
        except Exception as e:
            logger.warning("Failed to mark graph as cancelled: %s", e)

        try:
            from app.tools.kotlin_client import kotlin_client

            await kotlin_client.report_status_change(
                task_id=cancel_task_id,
                thread_id=thread_id,
                status="cancelled",
                summary="Úkol zrušen uživatelem.",
            )
        except Exception as e:
            logger.warning("Failed to report cancel to Kotlin: %s", e)

        task.cancel()
        orch_main._active_tasks.pop(thread_id, None)
        logger.info("Cancelled orchestration: thread=%s task=%s", thread_id, cancel_task_id)
        return control_pb2.CancelAck(cancelled=True)

    async def Interrupt(
        self,
        request: control_pb2.ThreadRequest,
        context: grpc.aio.ServicerContext,
    ) -> control_pb2.InterruptAck:
        from app import main as orch_main

        thread_id = request.thread_id
        task = orch_main._active_tasks.get(thread_id)
        if not task:
            logger.warning("INTERRUPT_NOT_FOUND: No active task for thread_id=%s", thread_id)
            return control_pb2.InterruptAck(interrupted=False, detail="no-active-task")
        try:
            task.cancel()
            logger.info(
                "INTERRUPT_SUCCESS: Gracefully interrupted thread_id=%s, checkpoint saved to MongoDB",
                thread_id,
            )
            orch_main._active_tasks.pop(thread_id, None)
            return control_pb2.InterruptAck(interrupted=True, detail="")
        except Exception as e:
            logger.error("INTERRUPT_ERROR: Failed to interrupt thread_id=%s: %s", thread_id, e)
            return control_pb2.InterruptAck(interrupted=False, detail=str(e))


def _enum_str(value) -> str:
    """Render a value as the wire string.

    The graph vertices/edges arrive as dicts where a few fields can hold
    str-based `Enum` instances (VertexType, VertexStatus, EdgeType).
    Python 3.11+ changed `str(Enum.MEMBER)` from `"member_value"` back to
    `"EnumClass.MEMBER"`, which breaks the Kotlin UI's lowercase-keyed
    `when { "client" -> ... }` dispatch — it started throwing
    `Unknown vertex type: VertexType.CLIENT` after the 3.12 bump.
    `.value` keeps the stable lowercase wire form across Python versions.
    """
    if value is None:
        return ""
    return str(getattr(value, "value", value))


def _vertex_to_proto(vid: str, v: dict) -> graph_pb2.GraphVertex:
    return graph_pb2.GraphVertex(
        id=str(v.get("id") or vid),
        title=str(v.get("title") or ""),
        description=str(v.get("description") or ""),
        vertex_type=_enum_str(v.get("vertex_type")),
        status=_enum_str(v.get("status")),
        agent_name=str(v.get("agent_name") or ""),
        input_request=str(v.get("input_request") or ""),
        result=str(v.get("result") or ""),
        result_summary=str(v.get("result_summary") or ""),
        local_context=str(v.get("local_context") or ""),
        parent_id=str(v.get("parent_id") or ""),
        depth=int(v.get("depth") or 0),
        tools_used=[str(t) for t in (v.get("tools_used") or [])],
        token_count=int(v.get("token_count") or 0),
        llm_calls=int(v.get("llm_calls") or 0),
        started_at=str(v.get("started_at") or ""),
        completed_at=str(v.get("completed_at") or ""),
        error=str(v.get("error") or ""),
        client_id=str(v.get("client_id") or ""),
    )


def _edge_to_proto(e: dict) -> graph_pb2.GraphEdge:
    payload_dict = e.get("payload")
    payload_proto = None
    if isinstance(payload_dict, dict):
        payload_proto = graph_pb2.EdgePayload(
            source_vertex_id=str(payload_dict.get("source_vertex_id") or ""),
            source_vertex_title=str(payload_dict.get("source_vertex_title") or ""),
            summary=str(payload_dict.get("summary") or ""),
            context=str(payload_dict.get("context") or ""),
        )
    return graph_pb2.GraphEdge(
        id=str(e.get("id") or ""),
        source_id=str(e.get("source_id") or ""),
        target_id=str(e.get("target_id") or ""),
        edge_type=_enum_str(e.get("edge_type")),
        payload=payload_proto,
    )


def _agent_graph_to_proto(payload: dict, hidden: bool = False) -> graph_pb2.AgentGraph:
    """Convert an AgentGraph pydantic model dump into the typed proto.

    Accepts either the master-graph client-filtered payload (already a
    dict with vertices keyed by id) or `graph.model_dump()` output.
    """
    vertices_raw = payload.get("vertices") or {}
    vertices: dict[str, graph_pb2.GraphVertex] = {}
    if isinstance(vertices_raw, dict):
        for vid, v in vertices_raw.items():
            if isinstance(v, dict):
                vertices[str(vid)] = _vertex_to_proto(str(vid), v)
    edges_raw = payload.get("edges") or []
    edges = [_edge_to_proto(e) for e in edges_raw if isinstance(e, dict)]
    return graph_pb2.AgentGraph(
        id=str(payload.get("id") or ""),
        task_id=str(payload.get("task_id") or ""),
        client_id=str(payload.get("client_id") or ""),
        project_id=str(payload.get("project_id") or ""),
        status=_enum_str(payload.get("status")),
        graph_type=_enum_str(payload.get("graph_type")),
        root_vertex_id=str(payload.get("root_vertex_id") or ""),
        synthesis_vertex_id=str(payload.get("synthesis_vertex_id") or ""),
        vertices=vertices,
        edges=edges,
        created_at=str(payload.get("created_at") or ""),
        completed_at=str(payload.get("completed_at") or ""),
        total_token_count=int(payload.get("total_token_count") or 0),
        total_llm_calls=int(payload.get("total_llm_calls") or 0),
        hidden=bool(hidden or payload.get("hidden") or False),
    )


class OrchestratorGraphServicer(graph_pb2_grpc.OrchestratorGraphServiceServicer):
    """OrchestratorGraphService — AgentGraph lookup + maintenance trigger.

    GetTaskGraph mirrors the FastAPI /graph/{task_id} handler byte-for-byte,
    including the master-graph live-RAM path + client filter.
    RunMaintenance dispatches to the existing _maintenance_phase{1,2}
    helpers kept in main.py.
    """

    async def GetTaskGraph(
        self,
        request: graph_pb2.GetTaskGraphRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.TaskGraphResponse:
        from datetime import datetime, timedelta, timezone

        from app.agent.models import GraphStatus, GraphType
        from app import main as orch_main

        task_id = request.task_id
        client_id = request.client_id or None

        hidden = False
        if task_id == "master":
            graph  = None
            if not graph:
                graph  = None
            if not graph:
                return graph_pb2.TaskGraphResponse(found=False)
            if client_id:
                payload = orch_main._filter_graph_for_client(graph, client_id)
            else:
                payload = graph.model_dump()
        else:
            graph  = None
            if not graph:
                graph  = None
            if not graph:
                graph  = None
            if not graph:
                return graph_pb2.TaskGraphResponse(found=False)
            payload = graph.model_dump()
            if graph.graph_type == GraphType.THINKING_GRAPH:

                if graph.status in (GraphStatus.COMPLETED, GraphStatus.FAILED) and graph.completed_at:
                    hide_cutoff = (
                        datetime.now(timezone.utc) - timedelta(seconds=_THINKING_GRAPH_HIDE_S)
                    ).isoformat()
                    if graph.completed_at < hide_cutoff:
                        hidden = True

        return graph_pb2.TaskGraphResponse(
            graph=_agent_graph_to_proto(payload, hidden=hidden),
            found=True,
        )


    async def RunMaintenance(
        self,
        request: graph_pb2.MaintenanceRunRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.MaintenanceRunResult:
        from app import main as orch_main

        phase = request.phase or 1
        client_id = request.client_id or None

        try:
            if phase == 1:
                result = await orch_main._maintenance_phase1()
            elif phase == 2:
                if not client_id:
                    await context.abort(grpc.StatusCode.INVALID_ARGUMENT,
                                        "client_id required for phase 2")
                result = await orch_main._maintenance_phase2(client_id)
            else:
                await context.abort(grpc.StatusCode.INVALID_ARGUMENT,
                                    f"Unknown phase: {phase}")
        except Exception as e:
            logger.exception("RUN_MAINTENANCE_ERROR phase=%s client=%s: %s", phase, client_id, e)
            await context.abort(grpc.StatusCode.INTERNAL, str(e))

        if not isinstance(result, dict):
            result = {}

        return graph_pb2.MaintenanceRunResult(
            phase=int(result.get("phase", phase) or phase),
            mem_removed=int(result.get("mem_removed", 0) or 0),
            thinking_evicted=int(result.get("thinking_evicted", 0) or 0),
            lqm_drained=int(result.get("lqm_drained", 0) or 0),
            affairs_archived=int(result.get("affairs_archived", 0) or 0),
            next_client_for_phase2=str(result.get("next_client_for_phase2") or ""),
            client_id=str(result.get("client_id") or client_id or ""),
            findings=[str(f) for f in (result.get("findings") or [])],
        )


def _empty_to_none(v: str) -> str | None:
    return v if v else None


def _qualify_request_from_proto(request: dispatch_pb2.QualifyRequest):
    """Translate a typed dispatch.proto QualifyRequest into the pydantic
    `app/unified/qualification_handler.py::QualifyRequest`."""
    from app.unified.qualification_handler import QualifyRequest

    attachments = [
        {
            "filename": a.filename,
            "contentType": a.content_type,
            "size": int(a.size),
            "index": int(a.index),
        }
        for a in request.attachments
    ]
    chat_topics = [
        {"role": t.role, "content": t.content} for t in request.chat_topics
    ]
    active_tasks = [
        {
            "task_id": t.task_id,
            "type": t.type,
            "state": t.state,
            "task_name": t.task_name,
            "topic_id": _empty_to_none(t.topic_id),
        }
        for t in request.active_tasks
    ]
    return QualifyRequest(
        task_id=request.task_id,
        client_id=request.client_id,
        project_id=_empty_to_none(request.project_id),
        group_id=_empty_to_none(request.group_id),
        client_name=_empty_to_none(request.client_name),
        project_name=_empty_to_none(request.project_name),
        source_urn=request.source_urn,
        max_openrouter_tier=request.max_openrouter_tier or "FREE",
        deadline_iso=_empty_to_none(request.deadline_iso),
        priority=request.priority or "NORMAL",
        summary=request.summary,
        entities=list(request.entities),
        suggested_actions=list(request.suggested_actions),
        urgency=request.urgency,
        action_type=_empty_to_none(request.action_type),
        estimated_complexity=_empty_to_none(request.estimated_complexity),
        is_assigned_to_me=bool(request.is_assigned_to_me),
        has_future_deadline=bool(request.has_future_deadline),
        suggested_deadline=_empty_to_none(request.suggested_deadline),
        has_attachments=bool(request.has_attachments),
        attachment_count=int(request.attachment_count),
        attachments=attachments,
        suggested_agent=_empty_to_none(request.suggested_agent),
        affected_files=list(request.affected_files),
        related_kb_nodes=list(request.related_kb_nodes),
        chat_topics=chat_topics,
        content=request.content,
        active_tasks=active_tasks,
        mentions_jervis=bool(request.mentions_jervis),
    )


def _project_rules_from_proto(rules: dispatch_pb2.ProjectRules) -> dict:
    return {
        "branch_naming": rules.branch_naming or "task/{taskId}",
        "commit_prefix": rules.commit_prefix or "task({taskId}):",
        "require_review": bool(rules.require_review),
        "require_tests": bool(rules.require_tests),
        "require_approval_commit": bool(rules.require_approval_commit),
        "require_approval_push": bool(rules.require_approval_push),
        "allowed_branches": list(rules.allowed_branches) or ["task/*", "fix/*"],
        "forbidden_files": list(rules.forbidden_files) or ["*.env", "secrets/*"],
        "max_changed_files": int(rules.max_changed_files) or 20,
        "auto_push": bool(rules.auto_push),
        "auto_use_anthropic": bool(rules.auto_use_anthropic),
        "auto_use_openai": bool(rules.auto_use_openai),
        "auto_use_gemini": bool(rules.auto_use_gemini),
        "max_openrouter_tier": rules.max_openrouter_tier or "NONE",
        "git_author_name": _empty_to_none(rules.git_author_name),
        "git_author_email": _empty_to_none(rules.git_author_email),
        "git_committer_name": _empty_to_none(rules.git_committer_name),
        "git_committer_email": _empty_to_none(rules.git_committer_email),
        "git_gpg_sign": bool(rules.git_gpg_sign),
        "git_gpg_key_id": _empty_to_none(rules.git_gpg_key_id),
        "git_message_pattern": _empty_to_none(rules.git_message_pattern),
    }


def _environment_from_proto(env: dispatch_pb2.EnvironmentContext) -> dict | None:
    # A zero-value (unset) EnvironmentContext has empty id + namespace → treat
    # the whole block as "no environment attached".
    if not env.id and not env.namespace:
        return None
    components = [
        {
            "id": c.id,
            "name": c.name,
            "type": c.type,
            "image": c.image,
            "projectId": c.project_id,
            "host": c.host,
            "ports": [
                {
                    "container": int(p.container),
                    "service": int(p.service) if p.service else int(p.container),
                    "name": p.name,
                }
                for p in c.ports
            ],
            "envVars": dict(c.env_vars),
            "autoStart": bool(c.auto_start),
            "startOrder": int(c.start_order),
            "sourceRepo": c.source_repo,
            "sourceBranch": c.source_branch,
            "dockerfilePath": c.dockerfile_path,
            "componentState": c.component_state,
        }
        for c in env.components
    ]
    component_links = [
        {"source": l.source, "target": l.target, "description": l.description}
        for l in env.component_links
    ]
    return {
        "id": env.id,
        "namespace": env.namespace,
        "tier": env.tier,
        "state": env.state,
        "groupId": _empty_to_none(env.group_id),
        "agentInstructions": env.agent_instructions,
        "components": components,
        "componentLinks": component_links,
    }


def _chat_history_from_proto(
    history: dispatch_pb2.ChatHistoryPayload,
):
    from app.models import ChatHistoryMessage, ChatHistoryPayload, ChatSummaryBlock

    if not history.recent_messages and not history.summary_blocks and history.total_message_count == 0:
        return None
    recent = [
        ChatHistoryMessage(
            role=m.role,
            content=m.content,
            timestamp=m.timestamp,
            sequence=int(m.sequence),
        )
        for m in history.recent_messages
    ]
    blocks = [
        ChatSummaryBlock(
            sequence_range=b.sequence_range,
            summary=b.summary,
            key_decisions=list(b.key_decisions),
            topics=list(b.topics),
            is_checkpoint=bool(b.is_checkpoint),
            checkpoint_reason=_empty_to_none(b.checkpoint_reason),
        )
        for b in history.summary_blocks
    ]
    return ChatHistoryPayload(
        recent_messages=recent,
        summary_blocks=blocks,
        total_message_count=int(history.total_message_count),
    )


def _orchestrate_request_from_proto(request: dispatch_pb2.OrchestrateRequest):
    from app.models import OrchestrateRequest, ProjectRules

    rules = ProjectRules(**_project_rules_from_proto(request.rules))
    environment = _environment_from_proto(request.environment)
    chat_history = _chat_history_from_proto(request.chat_history)
    return OrchestrateRequest(
        task_id=request.task_id,
        client_id=request.client_id,
        project_id=_empty_to_none(request.project_id),
        group_id=_empty_to_none(request.group_id),
        client_name=_empty_to_none(request.client_name),
        project_name=_empty_to_none(request.project_name),
        group_name=_empty_to_none(request.group_name),
        workspace_path=request.workspace_path,
        query=request.query,
        agent_preference=request.agent_preference or "auto",
        rules=rules,
        environment=environment,
        environment_id=_empty_to_none(request.environment_id),
        jervis_project_id=_empty_to_none(request.jervis_project_id),
        chat_history=chat_history,
        processing_mode=request.processing_mode or "FOREGROUND",
        max_openrouter_tier=request.max_openrouter_tier or "NONE",
        qualifier_context=_empty_to_none(request.qualifier_context),
        source_urn=_empty_to_none(request.source_urn),
        task_name=_empty_to_none(request.task_name),
    )


class OrchestratorDispatchServicer(dispatch_pb2_grpc.OrchestratorDispatchServiceServicer):
    """OrchestratorDispatchService — fire-and-forget dispatch RPCs.

    Both handlers reuse the existing chat/router.py background closures
    (handle_background, handle_qualification) so dispatch semantics stay
    byte-for-byte identical to the FastAPI routes (thread_id generation,
    asyncio.create_task + _active_tasks lifecycle, kotlin_client progress
    callbacks, interrupt detection).
    """

    async def Qualify(
        self,
        request: dispatch_pb2.QualifyRequest,
        context: grpc.aio.ServicerContext,
    ) -> dispatch_pb2.DispatchAck:
        import uuid as _uuid

        from app import main as orch_main
        from app.tools.kotlin_client import kotlin_client
        from app.unified.qualification_handler import handle_qualification

        try:
            qualify_request = _qualify_request_from_proto(request)
        except Exception as e:
            logger.error("QUALIFY_VALIDATION_FAILED: %s", e)
            return dispatch_pb2.DispatchAck(status="error", thread_id="", detail=f"Invalid qualify request: {e}")

        thread_id = f"qual-{qualify_request.task_id}-{_uuid.uuid4().hex[:8]}"
        logger.info(
            "QUALIFY_START | task_id=%s | thread_id=%s",
            qualify_request.task_id, thread_id,
        )

        async def _run_qualification():
            try:
                result = await handle_qualification(qualify_request)
                await kotlin_client.report_qualification_done(
                    task_id=qualify_request.task_id,
                    client_id=qualify_request.client_id,
                    decision=result.get("decision", "QUEUED"),
                    priority_score=result.get("priority_score", 5),
                    reason=result.get("reason", ""),
                    alert_message=result.get("alert_message"),
                    target_task_id=result.get("target_task_id"),
                    context_summary=result.get("context_summary", ""),
                    suggested_approach=result.get("suggested_approach", ""),
                    action_type=result.get("action_type", ""),
                    estimated_complexity=result.get("estimated_complexity", ""),
                    pending_user_question=result.get("pending_user_question"),
                    user_question_context=result.get("user_question_context"),
                    sub_tasks=result.get("sub_tasks"),
                )
            except Exception as e:
                logger.exception("Qualification failed: %s", e)
                await kotlin_client.report_qualification_done(
                    task_id=qualify_request.task_id,
                    client_id=qualify_request.client_id,
                    decision="QUEUED",
                    priority_score=5,
                    reason=f"Qualification error: {e!s}"[:500],
                )
            finally:
                orch_main._active_tasks.pop(thread_id, None)

        task = asyncio.create_task(_run_qualification())
        orch_main._active_tasks[thread_id] = task
        return dispatch_pb2.DispatchAck(status="accepted", thread_id=thread_id)

    async def Orchestrate(
        self,
        request: dispatch_pb2.OrchestrateRequest,
        context: grpc.aio.ServicerContext,
    ) -> dispatch_pb2.DispatchAck:
        import uuid as _uuid

        from app import main as orch_main
        from app.background.handler import handle_background
        from app.tools.kotlin_client import kotlin_client

        try:
            orchestrate_request = _orchestrate_request_from_proto(request)
        except Exception as e:
            return dispatch_pb2.DispatchAck(status="error", thread_id="", detail=f"Invalid orchestrate request: {e}")

        thread_id = f"graph-{orchestrate_request.task_id}-{_uuid.uuid4().hex[:8]}"
        logger.info(
            "ORCHESTRATE_START | task_id=%s | thread_id=%s",
            orchestrate_request.task_id, thread_id,
        )

        async def _run_background():
            try:
                result = await handle_background(orchestrate_request, thread_id=thread_id)

                if result.get("requeue"):
                    logger.info(
                        "ORCHESTRATE_REQUEUED | task_id=%s | thread=%s — job limit, will retry later",
                        orchestrate_request.task_id, thread_id,
                    )
                    return

                if result.get("coding_dispatched"):
                    logger.info(
                        "CODING_DISPATCHED | task_id=%s | job=%s | thread=%s",
                        orchestrate_request.task_id, result.get("job_name"), thread_id,
                    )
                    return

                if result.get("blocked"):
                    logger.info(
                        "ORCHESTRATE_BLOCKED | task_id=%s | thread=%s — graph has BLOCKED vertices",
                        orchestrate_request.task_id, thread_id,
                    )
                    await kotlin_client.report_status_change(
                        task_id=orchestrate_request.task_id,
                        thread_id=thread_id,
                        status="interrupted",
                        interrupt_action="clarify",
                        interrupt_description=result.get(
                            "summary", "Graph paused — vertices waiting for user input",
                        ),
                    )
                    return

                if not result.get("summary") and not result.get("success", True):
                    try:
                        from app.agent.langgraph_runner import _get_compiled_graph

                        compiled = _get_compiled_graph()
                        config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 200}
                        graph_state = await compiled.aget_state(config)
                        if graph_state and graph_state.next and graph_state.tasks:
                            for task_item in graph_state.tasks:
                                if hasattr(task_item, "interrupts") and task_item.interrupts:
                                    interrupt_data = task_item.interrupts[0].value
                                    await kotlin_client.report_status_change(
                                        task_id=orchestrate_request.task_id,
                                        thread_id=thread_id,
                                        status="interrupted",
                                        interrupt_action=interrupt_data.get("action", "clarify"),
                                        interrupt_description=interrupt_data.get("description", ""),
                                    )
                                    logger.info(
                                        "ORCHESTRATE_ASK_USER | thread_id=%s | action=%s",
                                        thread_id, interrupt_data.get("action"),
                                    )
                                    return
                    except Exception as e:
                        logger.debug("Interrupt check failed (non-fatal): %s", e)

                await kotlin_client.report_status_change(
                    task_id=orchestrate_request.task_id,
                    thread_id=thread_id,
                    status="done",
                    summary=result.get("summary", ""),
                    branch=result.get("branch"),
                    artifacts=result.get("artifacts", []),
                    keep_environment_running=result.get("keep_environment_running", False),
                )

                try:
                    from app.chat.thinking_graph import handle_vertex_result

                    await handle_vertex_result(
                        orchestrate_request.task_id,
                        result.get("summary", ""),
                    )
                except Exception as e:
                    logger.debug("Thinking map vertex update (non-fatal): %s", e)
            except asyncio.CancelledError:
                logger.info("ORCHESTRATE_INTERRUPTED | thread_id=%s — preempted by foreground", thread_id)
            except Exception as e:
                logger.exception("Background v2 failed: %s", e)
                await kotlin_client.report_status_change(
                    task_id=orchestrate_request.task_id,
                    thread_id=thread_id,
                    status="error",
                    error=str(e),
                )
            finally:
                orch_main._active_tasks.pop(thread_id, None)

        task = asyncio.create_task(_run_background())
        orch_main._active_tasks[thread_id] = task
        return dispatch_pb2.DispatchAck(status="accepted", thread_id=thread_id)


class OrchestratorCompanionServicer(companion_pb2_grpc.OrchestratorCompanionServiceServicer):
    """OrchestratorCompanionService — persistent-session surface for the
    Claude companion K8s Jobs. Unary RPCs delegate to the same
    `companion_runner` instance the FastAPI handlers used; StreamSession
    bridges the async generator into gRPC server-streaming.

    Adhoc dispatch was removed in V3-cleanup — no pod-to-pod consumer
    existed. The internal orchestrator graph executor still uses
    `companion_runner.dispatch_adhoc` / `wait_for_result` directly.
    """

    async def StartSession(
        self,
        request: companion_pb2.SessionStartRequest,
        context: grpc.aio.ServicerContext,
    ) -> companion_pb2.SessionStartResponse:
        from pathlib import Path

        from app.agents.companion_runner import companion_runner

        attachments = [Path(p) for p in list(request.attachment_paths) if Path(p).exists()]
        try:
            disp = await companion_runner.start_session(
                session_id=request.session_id or None,
                brief=request.brief,
                context=dict(request.context),
                attachments=attachments,
                client_id=request.client_id,
                project_id=request.project_id or None,
                language=request.language or "cs",
            )
        except RuntimeError as e:
            return companion_pb2.SessionStartResponse(error=str(e))
        return companion_pb2.SessionStartResponse(
            job_name=disp.job_name,
            workspace_path=disp.workspace_path,
            session_id=disp.session_id,
        )

    async def SessionEvent(
        self,
        request: companion_pb2.SessionEventRequest,
        context: grpc.aio.ServicerContext,
    ) -> companion_pb2.SessionEventAck:
        from app.agents.companion_runner import companion_runner

        try:
            companion_runner.send_event(
                request.session_id,
                request.type or "user",
                request.content,
                dict(request.meta),
            )
        except Exception as e:
            logger.warning("COMPANION_SESSION_EVENT_ERROR session=%s error=%s",
                           request.session_id, e)
            return companion_pb2.SessionEventAck(ok=False, error=str(e))
        return companion_pb2.SessionEventAck(ok=True, error="")

    async def StopSession(
        self,
        request: companion_pb2.SessionRef,
        context: grpc.aio.ServicerContext,
    ) -> companion_pb2.SessionAck:
        from app.agents.companion_runner import companion_runner

        try:
            await companion_runner.stop_session(request.session_id)
        except Exception as e:
            logger.warning("COMPANION_STOP_SESSION_ERROR session=%s error=%s",
                           request.session_id, e)
            return companion_pb2.SessionAck(ok=False, error=str(e))
        return companion_pb2.SessionAck(ok=True, error="")

    async def StreamSession(
        self,
        request: companion_pb2.StreamSessionRequest,
        context: grpc.aio.ServicerContext,
    ):
        from app.agents.companion_runner import companion_runner
        from app.config import settings as _settings

        max_age = request.max_age_seconds
        if max_age == 0:
            ttl = _settings.companion_assistant_event_ttl_seconds
        elif max_age < 0:
            ttl = None
        else:
            ttl = float(max_age)
        effective_ttl = None if (ttl is not None and ttl <= 0) else ttl

        stop_event = asyncio.Event()
        try:
            async for event in companion_runner.stream_outbox(
                request.session_id,
                stop_event=stop_event,
                max_age_seconds=effective_ttl,
            ):
                if not isinstance(event, dict):
                    continue
                yield companion_pb2.OutboxEvent(
                    ts=str(event.get("ts") or ""),
                    type=str(event.get("type") or ""),
                    content=str(event.get("content") or ""),
                    final=bool(event.get("final", False)),
                    meta={str(k): str(v) for k, v in (event.get("meta") or {}).items() if v is not None},
                )
        except asyncio.CancelledError:
            stop_event.set()
            raise


def _chat_request_from_proto(request: chat_pb2.ChatRequest):
    """Translate a typed chat.proto `ChatRequest` into the pydantic
    `app/chat/models.py::ChatRequest` the rest of the pipeline consumes.

    Proto3 scalar defaults (empty string, zero int) that stand in for
    "not set" get mapped back to None / sensible defaults so the pydantic
    model keeps its previous semantics.
    """
    from app.chat.models import ChatRequest

    def _empty_to_none(v: str) -> str | None:
        return v if v else None

    attachments = [
        {
            "filename": a.filename,
            "mime_type": a.mime_type,
            "size_bytes": int(a.size_bytes),
            "content_base64": _empty_to_none(a.content_base64),
        }
        for a in request.attachments
    ]

    return ChatRequest(
        session_id=request.session_id,
        message=request.message,
        message_sequence=int(request.message_sequence),
        user_id=request.user_id or "jan",
        active_client_id=_empty_to_none(request.active_client_id),
        active_project_id=_empty_to_none(request.active_project_id),
        active_group_id=_empty_to_none(request.active_group_id),
        active_client_name=_empty_to_none(request.active_client_name),
        active_project_name=_empty_to_none(request.active_project_name),
        active_group_name=_empty_to_none(request.active_group_name),
        context_task_id=_empty_to_none(request.context_task_id),
        timestamp=_empty_to_none(request.timestamp),
        max_openrouter_tier=request.max_openrouter_tier or "NONE",
        deadline_iso=_empty_to_none(request.deadline_iso),
        priority=request.priority or "NORMAL",
        client_timezone=_empty_to_none(request.client_timezone),
        attachments=attachments,
    )


class OrchestratorChatServicer(chat_pb2_grpc.OrchestratorChatServiceServicer):
    """OrchestratorChatService — foreground agentic chat stream.

    Routing rule:
    - active_client_id + active_project_id → ProjectSessionManager
      (per-(client, project) Claude session, scope ``project:<cid>:<pid>``)
    - active_client_id only → ClientSessionManager
      (per-client Claude session, scope ``client:<cid>``)
    - missing client_id → ClientSessionManager surfaces an error upstream.
    """

    async def Chat(
        self,
        request: chat_pb2.ChatRequest,
        context: grpc.aio.ServicerContext,
    ):
        session_id = request.session_id or ""
        message = request.message or ""
        client_id = (request.active_client_id or "").strip()
        project_id = (request.active_project_id or "").strip()

        logger.info(
            "CHAT_REQUEST | session=%s | client=%s | project=%s | message=%s",
            session_id, client_id or "-", project_id or "-", message[:100],
        )

        try:
            if client_id and project_id:
                from app.sessions.project_session_manager import project_session_manager

                stream = project_session_manager.chat(
                    client_id=client_id,
                    project_id=project_id,
                    message=message,
                )
            else:
                from app.sessions.client_session_manager import client_session_manager

                stream = client_session_manager.chat(
                    client_id=client_id,
                    project_id=project_id or None,
                    message=message,
                )
            async for evt in stream:
                if context.cancelled():
                    break
                meta = {str(k): str(v) for k, v in (evt.get("metadata") or {}).items()}
                yield chat_pb2.ChatEvent(
                    type=str(evt.get("type", "")),
                    content=str(evt.get("content", "")),
                    metadata=meta,
                )
        except Exception as e:
            logger.exception("chat failed | session=%s", session_id)
            yield chat_pb2.ChatEvent(type="error", content=str(e)[:500])

    async def ApproveAction(
        self,
        request: chat_pb2.ApproveActionRequest,
        context: grpc.aio.ServicerContext,
    ) -> chat_pb2.ApproveActionAck:
        from app.chat.handler_agentic import resolve_pending_approval

        logger.info(
            "CHAT_APPROVE | session=%s | approved=%s | always=%s | action=%s",
            request.session_id, request.approved, request.always, request.action,
        )
        try:
            resolve_pending_approval(
                session_id=request.session_id,
                approved=bool(request.approved),
                always=bool(request.always),
                action=request.action or None,
            )
        except Exception as e:
            logger.warning("CHAT_APPROVE_ERROR session=%s: %s", request.session_id, e)
            return chat_pb2.ApproveActionAck(ok=False, error=str(e))
        return chat_pb2.ApproveActionAck(ok=True, error="")

    async def Stop(
        self,
        request: chat_pb2.StopChatRequest,
        context: grpc.aio.ServicerContext,
    ) -> chat_pb2.StopChatAck:
        """Stop the Claude session bound to this chat session_id.

        The chat session_id from the UI is an opaque string that
        identifies the user's chat tab; to map it to a client session
        we need the active_client_id from the original Chat request.
        The UI sends it through ApproveAction today, not Stop. For now
        Stop is advisory — a future turn cancels the in-flight prompt
        via the manager's turn_lock. No-op keeps UI flow clean.
        """
        logger.info("CHAT_STOP | session=%s (no-op placeholder)", request.session_id)
        return chat_pb2.StopChatAck(ok=True, error="")


def _voice_event_to_proto(event) -> voice_pb2.VoiceStreamEvent:
    """Map one VoiceStreamEvent (pydantic, dict-payload) to the typed
    proto oneof. Unknown `event.event` values fall through to the error
    variant so the stream never emits an untagged event."""
    data = event.data or {}
    kind = event.event
    if kind == "responding":
        return voice_pb2.VoiceStreamEvent(responding=voice_pb2.Responding())
    if kind == "token":
        return voice_pb2.VoiceStreamEvent(
            token=voice_pb2.Token(text=str(data.get("text") or "")),
        )
    if kind == "response":
        return voice_pb2.VoiceStreamEvent(
            response=voice_pb2.Response(
                text=str(data.get("text") or ""),
                complete=bool(data.get("complete") or False),
            ),
        )
    if kind == "stored":
        return voice_pb2.VoiceStreamEvent(
            stored=voice_pb2.Stored(
                kind=str(data.get("kind") or ""),
                summary=str(data.get("summary") or ""),
            ),
        )
    if kind == "done":
        return voice_pb2.VoiceStreamEvent(done=voice_pb2.Done())
    # "error" or anything else is routed through the error variant so
    # the client's oneof switch always has a branch.
    return voice_pb2.VoiceStreamEvent(
        error=voice_pb2.ErrorPayload(text=str(data.get("text") or kind or "")),
    )


class OrchestratorVoiceServicer(voice_pb2_grpc.OrchestratorVoiceServiceServicer):
    """OrchestratorVoiceService — post-transcription voice pipeline.

    Process wraps handle_voice_stream(); Hint wraps generate_hint().
    Each pipeline stage yields a typed oneof payload (see
    VoiceStreamEvent in voice.proto) — no JSON passthrough on the wire.
    """

    async def Process(
        self,
        request: voice_pb2.VoiceProcessRequest,
        context: grpc.aio.ServicerContext,
    ):
        from app.voice.models import VoiceStreamRequest
        from app.voice.stream_handler import handle_voice_stream

        try:
            voice_request = VoiceStreamRequest(
                text=request.text,
                source=request.source or "app_chat",
                client_id=request.client_id or None,
                project_id=request.project_id or None,
                group_id=request.group_id or None,
                tts=bool(request.tts),
                meeting_id=request.meeting_id or None,
                live_assist=bool(request.live_assist),
                chunk_index=int(request.chunk_index or 0),
                is_final=bool(request.is_final),
            )
        except Exception as e:
            yield voice_pb2.VoiceStreamEvent(
                error=voice_pb2.ErrorPayload(text=f"Invalid voice request: {e}"),
            )
            yield voice_pb2.VoiceStreamEvent(done=voice_pb2.Done())
            return

        logger.info(
            "VOICE_PROCESS | text=%s | source=%s",
            voice_request.text[:80], voice_request.source,
        )
        try:
            async for event in handle_voice_stream(voice_request):
                yield _voice_event_to_proto(event)
        except Exception as e:
            logger.exception("Voice handler failed: %s", e)
            yield voice_pb2.VoiceStreamEvent(
                error=voice_pb2.ErrorPayload(text=str(e)[:100]),
            )
            yield voice_pb2.VoiceStreamEvent(done=voice_pb2.Done())

    async def Hint(
        self,
        request: voice_pb2.VoiceHintRequest,
        context: grpc.aio.ServicerContext,
    ) -> voice_pb2.VoiceHintResponse:
        from app.voice.stream_handler import generate_hint

        text = (request.text or "").strip()
        if not text:
            return voice_pb2.VoiceHintResponse(hint="")
        hint = await generate_hint(text, request.client_id or "", request.project_id or "")
        return voice_pb2.VoiceHintResponse(hint=str(hint or ""))


class OrchestratorMeetingHelperServicer(
    meeting_helper_pb2_grpc.OrchestratorMeetingHelperServiceServicer,
):
    """OrchestratorMeetingHelperService — live meeting assistance.

    Start/Stop/Chunk/Status take over the legacy `/meeting-helper/*`
    FastAPI routes byte-for-byte. Chunk runs the helper pipeline inline
    and pushes messages back to the Kotlin server via
    ServerMeetingHelperCallbacksService (the gRPC analogue of the old
    `/internal/meeting-helper/push` route).
    """

    async def Start(
        self,
        request: meeting_helper_pb2.StartHelperRequest,
        context: grpc.aio.ServicerContext,
    ) -> meeting_helper_pb2.StartHelperResponse:
        from app.meeting.live_helper import start_session

        if not request.meeting_id or not request.device_id:
            return meeting_helper_pb2.StartHelperResponse(
                status="error",
                error="meeting_id and device_id required",
            )
        session = start_session(
            request.meeting_id,
            request.device_id,
            request.source_lang or "en",
            request.target_lang or "cs",
        )
        logger.info(
            "MEETING_HELPER_START | meeting=%s device=%s %s→%s",
            session.meeting_id, session.device_id,
            session.source_lang, session.target_lang,
        )
        return meeting_helper_pb2.StartHelperResponse(
            status="ok",
            meeting_id=session.meeting_id,
            device_id=session.device_id,
            source_lang=session.source_lang,
            target_lang=session.target_lang,
        )

    async def Stop(
        self,
        request: meeting_helper_pb2.StopHelperRequest,
        context: grpc.aio.ServicerContext,
    ) -> meeting_helper_pb2.StopHelperResponse:
        from app.meeting.live_helper import stop_session

        if not request.meeting_id:
            return meeting_helper_pb2.StopHelperResponse(status="error", meeting_id="")
        stop_session(request.meeting_id)
        logger.info("MEETING_HELPER_STOP | meeting=%s", request.meeting_id)
        return meeting_helper_pb2.StopHelperResponse(
            status="ok", meeting_id=request.meeting_id,
        )

    async def Chunk(
        self,
        request: meeting_helper_pb2.HelperChunkRequest,
        context: grpc.aio.ServicerContext,
    ) -> meeting_helper_pb2.HelperChunkResponse:
        from app.grpc_server_client import (
            build_request_context,
            server_meeting_helper_callbacks_stub,
        )
        from app.meeting.live_helper import get_session, process_transcript_chunk
        from jervis.server import meeting_helper_callbacks_pb2

        session = get_session(request.meeting_id)
        if not session:
            return meeting_helper_pb2.HelperChunkResponse(
                status="no_session", messages_pushed=0,
            )

        try:
            from app.llm.provider import get_provider

            llm_provider = get_provider()
        except Exception as e:
            logger.warning("MEETING_HELPER_LLM_UNAVAILABLE meeting=%s: %s",
                           request.meeting_id, e)
            return meeting_helper_pb2.HelperChunkResponse(status="error", error=str(e))

        try:
            messages = await process_transcript_chunk(
                meeting_id=request.meeting_id,
                transcript_text=request.text,
                speaker=request.speaker or "",
                llm_provider=llm_provider,
            )
        except Exception as e:
            logger.exception("MEETING_HELPER_PIPELINE_FAIL meeting=%s", request.meeting_id)
            return meeting_helper_pb2.HelperChunkResponse(status="error", error=str(e))

        stub = server_meeting_helper_callbacks_stub()
        ctx_pb = build_request_context()
        pushed = 0
        for msg in messages:
            if not getattr(msg, "text", ""):
                continue
            try:
                await stub.PushMessage(
                    meeting_helper_callbacks_pb2.HelperPushRequest(
                        ctx=ctx_pb,
                        meeting_id=request.meeting_id,
                        type=str(msg.type or ""),
                        text=str(msg.text or ""),
                        context=str(getattr(msg, "context", "") or ""),
                        from_lang=str(getattr(msg, "from_lang", "") or ""),
                        to_lang=str(getattr(msg, "to_lang", "") or ""),
                        timestamp=str(getattr(msg, "timestamp", "") or ""),
                    ),
                    timeout=10.0,
                )
                pushed += 1
            except Exception as e:
                logger.warning("MEETING_HELPER_PUSH_FAIL meeting=%s: %s",
                               request.meeting_id, e)

        return meeting_helper_pb2.HelperChunkResponse(status="ok", messages_pushed=pushed)

    async def Status(
        self,
        request: meeting_helper_pb2.HelperStatusRequest,
        context: grpc.aio.ServicerContext,
    ) -> meeting_helper_pb2.HelperStatusResponse:
        from app.meeting.live_helper import get_session

        session = get_session(request.meeting_id)
        if not session:
            return meeting_helper_pb2.HelperStatusResponse(
                active=False, meeting_id=request.meeting_id,
            )
        return meeting_helper_pb2.HelperStatusResponse(
            active=bool(session.active),
            meeting_id=session.meeting_id,
            device_id=session.device_id,
            source_lang=session.source_lang,
            target_lang=session.target_lang,
            context_size=len(getattr(session, "context_window", []) or []),
        )


class OrchestratorJobLogsServicer(
    job_logs_pb2_grpc.OrchestratorJobLogsServiceServicer,
):
    """OrchestratorJobLogsService — server-streaming K8s pod logs for a
    CODING task. Replaces the legacy /job-logs/{task_id} SSE route.
    """

    async def StreamLogs(
        self,
        request: job_logs_pb2.JobLogsRequest,
        context: grpc.aio.ServicerContext,
    ):
        from app.agents.job_runner import job_runner
        from app.grpc_server_client import server_task_api_stub
        from jervis.common import types_pb2 as _types_pb2
        from jervis.server import task_api_pb2
        from jervis_contracts.interceptors import prepare_context

        task_id = request.task_id or ""
        if not task_id:
            yield job_logs_pb2.JobLogEvent(type="error", content="task_id required")
            return

        ctx = _types_pb2.RequestContext()
        prepare_context(ctx)
        try:
            resp = await server_task_api_stub().GetTask(
                task_api_pb2.TaskIdRequest(ctx=ctx, task_id=task_id),
                timeout=10.0,
            )
            if not resp.ok:
                yield job_logs_pb2.JobLogEvent(type="error", content="Task not found")
                return
        except Exception as e:
            yield job_logs_pb2.JobLogEvent(type="error", content=f"Failed to fetch task: {e}")
            return

        job_name = resp.agent_job_name or ""
        if not job_name:
            yield job_logs_pb2.JobLogEvent(type="error", content="Task has no agentJobName")
            return

        try:
            async for sse_line in job_runner.stream_job_logs_sse(job_name):
                # sse_line format: `data: {"type":"text","content":"…"}\n\n`
                if not sse_line or not sse_line.startswith("data:"):
                    continue
                payload = sse_line.removeprefix("data:").strip()
                if not payload:
                    continue
                try:
                    obj = json.loads(payload)
                except Exception:
                    continue
                yield job_logs_pb2.JobLogEvent(
                    type=str(obj.get("type") or "text"),
                    content=str(obj.get("content") or ""),
                    tool=str(obj.get("tool") or ""),
                )
        except asyncio.CancelledError:
            raise
        except Exception as e:
            yield job_logs_pb2.JobLogEvent(type="error", content=f"Log stream failed: {e}")


# --------------------------------------------------------------------------
# OrchestratorDashboardService — read-only view over SessionBroker (PR-D1)
# --------------------------------------------------------------------------

# Eviction history window — must match the brief copy in the UI
# ("Eviction history (24h)").
_DASHBOARD_EVICTION_WINDOW_HOURS = 24
_DASHBOARD_EVICTION_LIMIT = 50


async def _read_recent_evictions(limit: int = _DASHBOARD_EVICTION_LIMIT) -> list[dict]:
    """Read the last `limit` LRU eviction audit records from
    `claude_scratchpad` (scope=broker, namespace=audit, event=evict).

    Returns a list of dicts with keys `scope`, `reason`, `ts` (ISO 8601).
    Newest-first by `data.ts`. Audit failures are logged but never raised
    so the dashboard never falls because of an audit-side hiccup.
    """
    from app.config import settings as _settings
    from motor.motor_asyncio import AsyncIOMotorClient

    try:
        client = AsyncIOMotorClient(_settings.mongodb_url)
        db = client.get_default_database()
        cutoff = (
            datetime.datetime.now(datetime.timezone.utc)
            - datetime.timedelta(hours=_DASHBOARD_EVICTION_WINDOW_HOURS)
        )
        cursor = db["claude_scratchpad"].find(
            {
                "scope": "broker",
                "namespace": "audit",
                "data.event": "evict",
                "created_at": {"$gte": cutoff},
            },
        ).sort("created_at", -1).limit(limit)
        out: list[dict] = []
        async for doc in cursor:
            data = doc.get("data") or {}
            out.append({
                "scope": str(data.get("session_scope") or ""),
                "reason": str(data.get("reason") or "lru_cap"),
                "ts": str(data.get("ts") or ""),
            })
        return out
    except Exception:
        logger.exception("dashboard: failed to read eviction history")
        return []


async def _last_compact_age_seconds(scope: str) -> int:
    """Return the age (in seconds) of the most recent ``compact_snapshots``
    row for ``scope``. Zero when none exists or on read failure (the UI
    just shows '—' in that case)."""
    if not scope:
        return 0
    try:
        from app.sessions.compact_store import load_latest

        snap = await load_latest(scope)
        if snap is None:
            return 0
        delta = datetime.datetime.now(datetime.timezone.utc) - snap.snapshot_at
        return max(0, int(delta.total_seconds()))
    except Exception:
        logger.debug("dashboard: last_compact_age failed for scope=%s", scope, exc_info=True)
        return 0


class OrchestratorDashboardServicer(
    dashboard_pb2_grpc.OrchestratorDashboardServiceServicer,
):
    """Read-only snapshot of the SessionBroker for the desktop UI Dashboard.

    The Kotlin server polls this once every 5 s into a kRPC push-flow
    (UI ↔ server stays push-only per rule #9). Pull cadence here is an
    internal implementation detail — the broker holds in-process state
    that is not (yet) wired to a server-streaming push channel.
    """

    async def GetSessionSnapshot(
        self,
        request: dashboard_pb2.GetSessionSnapshotRequest,
        context: grpc.aio.ServicerContext,
    ) -> dashboard_pb2.SessionSnapshotResponse:
        from app.sessions.session_broker import session_broker

        try:
            snap = session_broker.snapshot()
        except Exception as e:
            logger.exception("dashboard: snapshot failed")
            return dashboard_pb2.SessionSnapshotResponse(ok=False, error=str(e)[:200])

        sessions_proto: list[dashboard_pb2.ActiveSession] = []
        for s in snap.get("sessions", []):
            scope = str(s.get("scope") or "")
            age = await _last_compact_age_seconds(scope)
            sessions_proto.append(
                dashboard_pb2.ActiveSession(
                    scope=scope,
                    session_id=str(s.get("session_id") or ""),
                    client_id=str(s.get("client_id") or ""),
                    project_id=str(s.get("project_id") or ""),
                    cumulative_tokens=int(s.get("cumulative_tokens") or 0),
                    idle_seconds=int(s.get("idle_seconds") or 0),
                    compact_in_progress=bool(s.get("compact_in_progress") or False),
                    last_compact_age_seconds=age,
                ),
            )

        evictions = await _read_recent_evictions()
        evictions_proto = [
            dashboard_pb2.EvictionRecord(
                scope=str(e.get("scope") or ""),
                reason=str(e.get("reason") or ""),
                ts=str(e.get("ts") or ""),
            )
            for e in evictions
        ]

        agent_job_holds = {
            str(k): str(v) for k, v in (snap.get("agent_job_holds") or {}).items()
        }

        return dashboard_pb2.SessionSnapshotResponse(
            ok=True,
            error="",
            active_count=int(snap.get("active") or 0),
            cap=int(snap.get("cap") or 0),
            paused=bool(snap.get("paused") or False),
            sessions=sessions_proto,
            agent_job_holds=agent_job_holds,
            recent_evictions=evictions_proto,
        )


class OrchestratorProposalServicer(
    proposal_pb2_grpc.OrchestratorProposalServiceServicer,
):
    """OrchestratorProposalService — Claude CLI proposal lifecycle.

    Thin gRPC wrapper around `app.agent.proposal_service`. The real
    embedding + dedup + Kotlin-write logic lives there; this servicer
    just maps proto fields and converts results back to proto.

    The MCP server (`propose_task` / `update_proposed_task` /
    `send_for_approval` tools) calls this servicer rather than the
    Kotlin server directly so embedding & dedup remain in one place.
    Approve/Reject (UI actions) bypass this layer and go straight to
    the Kotlin ServerTaskProposalService — they don't need embedding.
    """

    async def ProposeTask(
        self,
        request: proposal_pb2.ProposeTaskRequest,
        context: grpc.aio.ServicerContext,
    ) -> proposal_pb2.ProposeTaskResponse:
        from app.agent.proposal_service import propose_task

        try:
            res = await propose_task(
                client_id=request.client_id,
                project_id=request.project_id,
                title=request.title,
                description=request.description,
                reason=request.reason,
                proposed_by=request.proposed_by,
                proposal_task_type=request.proposal_task_type,
                scheduled_at_iso=request.scheduled_at_iso,
                parent_task_id=request.parent_task_id,
                depends_on_task_ids=list(request.depends_on_task_ids),
            )
        except Exception as e:
            logger.exception("ProposeTask failed")
            return proposal_pb2.ProposeTaskResponse(
                ok=False, error=str(e)[:500],
            )
        return proposal_pb2.ProposeTaskResponse(
            ok=res.ok,
            error=res.error,
            task_id=res.task_id,
            dedup_decision=res.decision,
            conflicting_task_id=res.conflicting_task_id,
            conflicting_title=res.conflicting_title,
        )

    async def UpdateProposedTask(
        self,
        request: proposal_pb2.UpdateProposedTaskRequest,
        context: grpc.aio.ServicerContext,
    ) -> proposal_pb2.UpdateProposedTaskResponse:
        from app.agent.proposal_service import update_proposed_task

        try:
            # Best-effort: client_id_for_embed is optional; the embedding
            # call only uses it for router scope tracking. The Mongo
            # write is keyed on task_id so the right document is found.
            res = await update_proposed_task(
                task_id=request.task_id,
                title=request.title,
                description=request.description,
                reason=request.reason,
                proposal_task_type=request.proposal_task_type,
                scheduled_at_iso=request.scheduled_at_iso,
            )
        except Exception as e:
            logger.exception("UpdateProposedTask failed")
            return proposal_pb2.UpdateProposedTaskResponse(
                ok=False, error=str(e)[:500],
            )
        return proposal_pb2.UpdateProposedTaskResponse(
            ok=res.ok,
            error=res.error,
        )

    async def SendForApproval(
        self,
        request: proposal_pb2.TaskIdRequest,
        context: grpc.aio.ServicerContext,
    ) -> proposal_pb2.ProposalActionResponse:
        from app.agent.proposal_service import send_for_approval

        try:
            res = await send_for_approval(task_id=request.task_id)
        except Exception as e:
            logger.exception("SendForApproval failed")
            return proposal_pb2.ProposalActionResponse(
                ok=False, error=str(e)[:500],
            )
        return proposal_pb2.ProposalActionResponse(
            ok=res.ok,
            error=res.error,
            proposal_stage="AWAITING_APPROVAL" if res.ok else "",
        )


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    """Start the gRPC server on `port` and return the handle for cleanup."""
    from jervis_contracts.grpc_options import build_server_options

    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=build_server_options(),
    )
    control_pb2_grpc.add_OrchestratorControlServiceServicer_to_server(
        OrchestratorControlServicer(), server,
    )
    graph_pb2_grpc.add_OrchestratorGraphServiceServicer_to_server(
        OrchestratorGraphServicer(), server,
    )
    dispatch_pb2_grpc.add_OrchestratorDispatchServiceServicer_to_server(
        OrchestratorDispatchServicer(), server,
    )
    companion_pb2_grpc.add_OrchestratorCompanionServiceServicer_to_server(
        OrchestratorCompanionServicer(), server,
    )
    chat_pb2_grpc.add_OrchestratorChatServiceServicer_to_server(
        OrchestratorChatServicer(), server,
    )
    voice_pb2_grpc.add_OrchestratorVoiceServiceServicer_to_server(
        OrchestratorVoiceServicer(), server,
    )
    meeting_helper_pb2_grpc.add_OrchestratorMeetingHelperServiceServicer_to_server(
        OrchestratorMeetingHelperServicer(), server,
    )
    job_logs_pb2_grpc.add_OrchestratorJobLogsServiceServicer_to_server(
        OrchestratorJobLogsServicer(), server,
    )
    dashboard_pb2_grpc.add_OrchestratorDashboardServiceServicer_to_server(
        OrchestratorDashboardServicer(), server,
    )
    proposal_pb2_grpc.add_OrchestratorProposalServiceServicer_to_server(
        OrchestratorProposalServicer(), server,
    )

    service_names = (
        control_pb2.DESCRIPTOR.services_by_name["OrchestratorControlService"].full_name,
        graph_pb2.DESCRIPTOR.services_by_name["OrchestratorGraphService"].full_name,
        dispatch_pb2.DESCRIPTOR.services_by_name["OrchestratorDispatchService"].full_name,
        companion_pb2.DESCRIPTOR.services_by_name["OrchestratorCompanionService"].full_name,
        chat_pb2.DESCRIPTOR.services_by_name["OrchestratorChatService"].full_name,
        voice_pb2.DESCRIPTOR.services_by_name["OrchestratorVoiceService"].full_name,
        meeting_helper_pb2.DESCRIPTOR.services_by_name["OrchestratorMeetingHelperService"].full_name,
        job_logs_pb2.DESCRIPTOR.services_by_name["OrchestratorJobLogsService"].full_name,
        dashboard_pb2.DESCRIPTOR.services_by_name["OrchestratorDashboardService"].full_name,
        proposal_pb2.DESCRIPTOR.services_by_name["OrchestratorProposalService"].full_name,
        reflection.SERVICE_NAME,
    )
    reflection.enable_server_reflection(service_names, server)

    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info(
        "gRPC orchestrator services listening on :%d "
        "(Control + Graph + Dispatch + Companion + Chat + Voice + MeetingHelper + JobLogs + Dashboard + Proposal)",
        port,
    )
    return server
