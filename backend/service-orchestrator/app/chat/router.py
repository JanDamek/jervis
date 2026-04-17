"""Chat API endpoints — FastAPI router.

Endpoints:
- POST /chat                          → Foreground chat handler
- POST /internal/prepare-chat-context → replaces Kotlin prepareChatHistoryPayload()
- POST /internal/compress-chat-async  → replaces Kotlin compressIfNeeded()
- POST /orchestrate                   → Background orchestration (Graph Agent)
- POST /qualify                       → Qualification agent (fire-and-forget)
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from starlette.responses import StreamingResponse

from app.chat.context import chat_context_assembler, _parse_json_response
from app.chat.models import ChatRequest

logger = logging.getLogger(__name__)

router = APIRouter()


# ---------------------------------------------------------------------------
# Foreground Chat endpoint
# ---------------------------------------------------------------------------

@router.post("/chat")
async def chat(request: ChatRequest):
    """Handle foreground chat message — dedicated agentic loop.

    This is the primary foreground chat path (v6 architecture).
    Replaces the LangGraph respond node for interactive chat.

    Flow: User message → agentic loop → streamed SSE events → Kotlin → UI.
    handle_chat is an async generator yielding ChatStreamEvent objects.
    """
    from app.agent.sse_handler import handle_chat_sse

    async def sse_stream():
        try:
            async for event in handle_chat_sse(request):
                data = event.model_dump_json()
                if event.type == "approval_request":
                    logger.info("SSE: emitting approval_request event to HTTP stream")
                yield f"event: {event.type}\ndata: {data}\n\n"
        except Exception as e:
            logger.exception("Chat handler failed for session %s", request.session_id)
            error_event = json.dumps({"type": "error", "content": str(e), "metadata": {}})
            yield f"event: error\ndata: {error_event}\n\n"

    return StreamingResponse(sse_stream(), media_type="text/event-stream")


# ---------------------------------------------------------------------------
# Chat approval endpoint
# ---------------------------------------------------------------------------


class ChatApproveRequest(BaseModel):
    session_id: str
    approved: bool = False
    always: bool = False
    action: str | None = None


@router.post("/chat/approve")
async def approve_chat_action(request: ChatApproveRequest):
    """Approve or deny a pending chat tool action.

    Called by Kotlin when user clicks Approve/Deny in the approval dialog.
    Resolves the asyncio.Future in the agentic loop, unblocking tool execution.
    """
    from app.chat.handler_agentic import resolve_pending_approval

    logger.info(
        "CHAT_APPROVE | session=%s | approved=%s | always=%s | action=%s",
        request.session_id, request.approved, request.always, request.action,
    )
    resolve_pending_approval(
        session_id=request.session_id,
        approved=request.approved,
        always=request.always,
        action=request.action,
    )
    return {"status": "ok"}


# ---------------------------------------------------------------------------
# Background orchestration endpoint
# ---------------------------------------------------------------------------

@router.post("/orchestrate")
async def orchestrate(request: dict):
    """Background orchestration — Graph Agent.

    Fire-and-forget: returns thread_id immediately, pushes status to Kotlin.
    """
    from app.background.handler import handle_background
    from app.main import _active_tasks
    from app.models import OrchestrateRequest
    from app.tools.kotlin_client import kotlin_client

    try:
        orchestrate_request = OrchestrateRequest(**request)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid request: {e}")

    thread_id = f"graph-{orchestrate_request.task_id}-{uuid.uuid4().hex[:8]}"
    logger.info(
        "ORCHESTRATE_START | task_id=%s | thread_id=%s",
        orchestrate_request.task_id, thread_id,
    )

    async def _run_background():
        try:
            result = await handle_background(orchestrate_request, thread_id=thread_id)

            # Job limit reached — task requeued, just log and return
            if result.get("requeue"):
                logger.info(
                    "ORCHESTRATE_REQUEUED | task_id=%s | thread=%s — job limit, will retry later",
                    orchestrate_request.task_id, thread_id,
                )
                return  # Task already set to QUEUED via direct DB update

            # Coding agent dispatched — task is now CODING, watcher handles completion
            if result.get("coding_dispatched"):
                logger.info(
                    "CODING_DISPATCHED | task_id=%s | job=%s | thread=%s",
                    orchestrate_request.task_id, result.get("job_name"), thread_id,
                )
                return  # Don't report "done" — AgentTaskWatcher monitors the K8s Job

            # Graph BLOCKED — vertices waiting for user input (ASK_USER)
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
                    interrupt_description=result.get("summary", "Graph paused — vertices waiting for user input"),
                )
                return  # Don't report "done" — graph is paused

            # Check if result indicates an LangGraph interrupt (ask_user)
            # When Graph Agent's interrupt() fires, ainvoke() returns partial state
            # with empty summary and the interrupt data is in the LangGraph checkpoint.
            if not result.get("summary") and not result.get("success", True):
                # Likely an interrupt — check checkpoint for interrupt data
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
                                return  # Don't report "done" — graph is paused
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

            # Update thinking map vertex if this was a dispatched vertex
            try:
                from app.chat.thinking_graph import handle_vertex_result
                await handle_vertex_result(
                    orchestrate_request.task_id,
                    result.get("summary", ""),
                )
            except Exception as e:
                logger.debug("Thinking map vertex update (non-fatal): %s", e)
        except asyncio.CancelledError:
            # Preemption is NOT an error — Kotlin already reset task to QUEUED.
            # Do NOT send error status — it would show "Chyba" banner in UI.
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
            _active_tasks.pop(thread_id, None)

    task = asyncio.create_task(_run_background())
    _active_tasks[thread_id] = task
    return {"thread_id": thread_id}


# ---------------------------------------------------------------------------
# Qualification Agent endpoint
# ---------------------------------------------------------------------------

@router.post("/qualify")
async def qualify(request: dict):
    """Qualification agent — LLM with CORE tools analyzes KB results.

    Fire-and-forget: returns thread_id immediately, pushes result to Kotlin
    via /internal/qualification-done callback.
    """
    from app.main import _active_tasks
    from app.tools.kotlin_client import kotlin_client
    from app.unified.qualification_handler import QualifyRequest, handle_qualification

    try:
        qualify_request = QualifyRequest(**request)
    except Exception as e:
        logger.error("QUALIFY_VALIDATION_FAILED: %s | keys=%s", e, list(request.keys()))
        raise HTTPException(status_code=400, detail=f"Invalid qualify request: {e}")

    thread_id = f"qual-{qualify_request.task_id}-{uuid.uuid4().hex[:8]}"
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
                # Phase 3 — re-entrant qualifier extensions
                pending_user_question=result.get("pending_user_question"),
                user_question_context=result.get("user_question_context"),
                sub_tasks=result.get("sub_tasks"),
            )
        except Exception as e:
            logger.exception("Qualification failed: %s", e)
            # On failure, default to QUEUED (fail-safe)
            await kotlin_client.report_qualification_done(
                task_id=qualify_request.task_id,
                client_id=qualify_request.client_id,
                decision="QUEUED",
                priority_score=5,
                reason=f"Qualification error: {e}",
                context_summary="",
                suggested_approach="",
                action_type="",
                estimated_complexity="",
            )
        finally:
            _active_tasks.pop(thread_id, None)

    task = asyncio.create_task(_run_qualification())
    _active_tasks[thread_id] = task
    return {"thread_id": thread_id}


# ---------------------------------------------------------------------------
# Request/Response models
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Job Logs SSE endpoint — live K8s pod log streaming
# ---------------------------------------------------------------------------

@router.get("/job-logs/{task_id}")
async def stream_job_logs(task_id: str):
    """Stream K8s pod logs for a CODING task as SSE events.

    Looks up the task's agentJobName, finds the K8s pod, streams logs.
    Used by Kotlin server to relay live output to UI.
    """
    from app.agents.job_runner import job_runner
    from app.grpc_server_client import server_task_api_stub
    from jervis.common import types_pb2
    from jervis.server import task_api_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_task_api_stub().GetTask(
            task_api_pb2.TaskIdRequest(ctx=ctx, task_id=task_id),
            timeout=10.0,
        )
        if not resp.ok:
            return StreamingResponse(
                iter(['data: {"type":"error","content":"Task not found"}\n\n']),
                media_type="text/event-stream",
            )
    except Exception as e:
        return StreamingResponse(
            iter([f'data: {{"type":"error","content":"Failed to fetch task: {e}"}}\n\n']),
            media_type="text/event-stream",
        )

    job_name = resp.agent_job_name or ""
    if not job_name:
        return StreamingResponse(
            iter(['data: {"type":"error","content":"Task has no agentJobName"}\n\n']),
            media_type="text/event-stream",
        )

    return StreamingResponse(
        job_runner.stream_job_logs_sse(job_name),
        media_type="text/event-stream",
    )


class PrepareChatContextRequest(BaseModel):
    task_id: str = ""
    conversation_id: str = ""
    memory_context: str = ""
    context_budget: int = 0  # 0 = use default

    def get_conversation_id(self) -> str:
        """Return conversation_id, falling back to task_id for backward compat."""
        return self.conversation_id or self.task_id


class CompressChatAsyncRequest(BaseModel):
    task_id: str = ""
    conversation_id: str = ""

    def get_conversation_id(self) -> str:
        return self.conversation_id or self.task_id


class CompressChatAsyncResponse(BaseModel):
    triggered: bool
    message: str = ""


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@router.post("/internal/prepare-chat-context")
async def prepare_chat_context(request: PrepareChatContextRequest):
    """Build chat context payload from MongoDB.

    Replaces Kotlin ChatHistoryService.prepareChatHistoryPayload().
    Kotlin sends conversationId -> Python reads MongoDB -> returns payload.
    Same JSON structure as ChatHistoryPayloadDto.
    """
    cid = request.get_conversation_id()
    try:
        payload = await chat_context_assembler.prepare_payload_for_kotlin(cid)

        if payload is None:
            return {
                "recent_messages": [],
                "summary_blocks": [],
                "total_message_count": 0,
            }

        logger.info(
            "PREPARE_CONTEXT | conversationId=%s | messages=%d | summaries=%d",
            cid,
            len(payload["recent_messages"]),
            len(payload["summary_blocks"]),
        )

        return payload

    except Exception as e:
        logger.exception("Failed to prepare chat context for conversation %s", cid)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/internal/compress-chat-async")
async def compress_chat_async(request: CompressChatAsyncRequest):
    """Trigger async compression for a conversation thread.

    Replaces Kotlin ChatHistoryService.compressIfNeeded().
    Python reads MongoDB -> checks threshold -> fires LLM compression -> saves to MongoDB.
    """
    cid = request.get_conversation_id()
    try:
        triggered = await chat_context_assembler.maybe_compress(cid)

        return CompressChatAsyncResponse(
            triggered=triggered,
            message="Compression triggered" if triggered else "No compression needed",
        )

    except Exception as e:
        logger.exception("Failed to trigger compression for conversation %s", cid)
        raise HTTPException(status_code=500, detail=str(e))


