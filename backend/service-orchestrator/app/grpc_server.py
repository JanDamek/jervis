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

    service_names = (
        control_pb2.DESCRIPTOR.services_by_name["OrchestratorControlService"].full_name,
        reflection.SERVICE_NAME,
    )
    reflection.enable_server_reflection(service_names, server)

    server.add_insecure_port(f"[::]:{port}")
    await server.start()
    logger.info("gRPC orchestrator services listening on :%d (OrchestratorControlService)", port)
    return server
