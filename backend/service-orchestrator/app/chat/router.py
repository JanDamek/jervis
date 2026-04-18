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


# /chat (streaming) + /chat/approve migrated to gRPC
# (OrchestratorChatService.{Chat,ApproveAction} on :5501 —
# see app/grpc_server.py).


# /orchestrate + /qualify migrated to gRPC
# (OrchestratorDispatchService.{Orchestrate,Qualify} on :5501 — see
# app/grpc_server.py). The background closures moved into the servicer
# verbatim so thread_id format + kotlin_client callback timing are
# preserved.


# (Orchestrate + Qualify bodies live in app/grpc_server.py now.)


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


