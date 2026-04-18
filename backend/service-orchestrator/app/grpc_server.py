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
import json
import logging
from typing import TYPE_CHECKING

import grpc
from grpc_reflection.v1alpha import reflection

from jervis.orchestrator import control_pb2, control_pb2_grpc
from jervis.orchestrator import dispatch_pb2, dispatch_pb2_grpc
from jervis.orchestrator import graph_pb2, graph_pb2_grpc
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
        chat_history = None
        if request.chat_history_json:
            try:
                chat_history = json.loads(request.chat_history_json)
            except Exception as e:
                logger.warning("APPROVE_BAD_CHAT_HISTORY_JSON thread_id=%s: %s", thread_id, e)

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

                if chat_history:
                    await compiled.aupdate_state(config, {"chat_history": chat_history})

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
            from app.agent.persistence import agent_store
            from app.agent.models import GraphStatus

            graph = await agent_store.load(cancel_task_id)
            if graph and graph.status not in (GraphStatus.COMPLETED, GraphStatus.FAILED):
                graph.status = GraphStatus.CANCELLED
                await agent_store.save(graph)
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

        from app.agent.persistence import agent_store
        from app.agent.models import GraphStatus, GraphType
        from app import main as orch_main

        task_id = request.task_id
        client_id = request.client_id or None

        if task_id == "master":
            graph = agent_store.get_memory_graph_cached()
            if not graph:
                graph = await agent_store.get_or_create_memory_graph()
            if not graph:
                return graph_pb2.TaskGraphResponse(graph_json="", found=False)
            if client_id:
                payload = orch_main._filter_graph_for_client(graph, client_id)
            else:
                payload = graph.model_dump()
        else:
            graph = agent_store.get_cached_subgraph(task_id)
            if not graph:
                graph = await agent_store.load(task_id)
            if not graph:
                graph = await agent_store.load_by_graph_id(task_id)
            if not graph:
                return graph_pb2.TaskGraphResponse(graph_json="", found=False)
            payload = graph.model_dump()
            if graph.graph_type == GraphType.THINKING_GRAPH:
                from app.agent.persistence import _THINKING_GRAPH_HIDE_S

                if graph.status in (GraphStatus.COMPLETED, GraphStatus.FAILED) and graph.completed_at:
                    hide_cutoff = (
                        datetime.now(timezone.utc) - timedelta(seconds=_THINKING_GRAPH_HIDE_S)
                    ).isoformat()
                    if graph.completed_at < hide_cutoff:
                        payload["hidden"] = True

        return graph_pb2.TaskGraphResponse(
            graph_json=json.dumps(payload, default=str, ensure_ascii=False),
            found=True,
        )

    async def RunMaintenance(
        self,
        request: graph_pb2.MaintenanceRunRequest,
        context: grpc.aio.ServicerContext,
    ) -> graph_pb2.MaintenanceRunResult:
        from app.agent.persistence import agent_store
        from app import main as orch_main

        phase = request.phase or 1
        client_id = request.client_id or None

        try:
            if phase == 1:
                result = await orch_main._maintenance_phase1(agent_store)
            elif phase == 2:
                if not client_id:
                    await context.abort(grpc.StatusCode.INVALID_ARGUMENT,
                                        "client_id required for phase 2")
                result = await orch_main._maintenance_phase2(agent_store, client_id)
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
        request: dispatch_pb2.DispatchRequest,
        context: grpc.aio.ServicerContext,
    ) -> dispatch_pb2.DispatchAck:
        import uuid as _uuid

        from app import main as orch_main
        from app.tools.kotlin_client import kotlin_client
        from app.unified.qualification_handler import QualifyRequest, handle_qualification

        try:
            payload = json.loads(request.payload_json) if request.payload_json else {}
            qualify_request = QualifyRequest(**payload)
        except Exception as e:
            logger.error("QUALIFY_VALIDATION_FAILED: %s | keys=%s", e, list((payload or {}).keys()))
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
        request: dispatch_pb2.DispatchRequest,
        context: grpc.aio.ServicerContext,
    ) -> dispatch_pb2.DispatchAck:
        import uuid as _uuid

        from app import main as orch_main
        from app.background.handler import handle_background
        from app.models import OrchestrateRequest
        from app.tools.kotlin_client import kotlin_client

        try:
            payload = json.loads(request.payload_json) if request.payload_json else {}
            orchestrate_request = OrchestrateRequest(**payload)
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


async def start_grpc_server(port: int = 5501) -> grpc.aio.Server:
    """Start the gRPC server on `port` and return the handle for cleanup."""
    max_msg_bytes = 64 * 1024 * 1024
    server = grpc.aio.server(
        interceptors=[ServerContextInterceptor()],
        options=[
            ("grpc.max_receive_message_length", max_msg_bytes),
            ("grpc.max_send_message_length", max_msg_bytes),
        ],
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

    service_names = (
        control_pb2.DESCRIPTOR.services_by_name["OrchestratorControlService"].full_name,
        graph_pb2.DESCRIPTOR.services_by_name["OrchestratorGraphService"].full_name,
        dispatch_pb2.DESCRIPTOR.services_by_name["OrchestratorDispatchService"].full_name,
        reflection.SERVICE_NAME,
    )
    reflection.enable_server_reflection(service_names, server)

    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info(
        "gRPC orchestrator services listening on :%d (Control + Graph + Dispatch)",
        port,
    )
    return server
