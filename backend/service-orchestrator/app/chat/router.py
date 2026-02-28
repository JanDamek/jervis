"""Chat API endpoints — FastAPI router.

Endpoints:
- POST /chat                          → Foreground chat handler (new v6 flow)
- POST /internal/prepare-chat-context → replaces Kotlin prepareChatHistoryPayload()
- POST /internal/compress-chat-async  → replaces Kotlin compressIfNeeded()
- POST /orchestrate/v2                → Simplified background handler (new v6 flow)
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
    from app.chat.handler import handle_chat

    async def sse_stream():
        try:
            async for event in handle_chat(request):
                data = event.model_dump_json()
                yield f"event: {event.type}\ndata: {data}\n\n"
        except Exception as e:
            logger.exception("Chat handler failed for session %s", request.session_id)
            error_event = json.dumps({"type": "error", "content": str(e), "metadata": {}})
            yield f"event: error\ndata: {error_event}\n\n"

    return StreamingResponse(sse_stream(), media_type="text/event-stream")


# ---------------------------------------------------------------------------
# Background v2 endpoint
# ---------------------------------------------------------------------------

@router.post("/orchestrate/v2")
async def orchestrate_v2(request: dict):
    """Simplified background orchestration (v6 architecture).

    Replaces the old 14-node LangGraph for background tasks.
    4-phase flow: Intake → Execute → Dispatch → Finalize.

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

    thread_id = f"v2-{orchestrate_request.task_id}-{uuid.uuid4().hex[:8]}"
    logger.info(
        "ORCHESTRATE_V2_START | task_id=%s | thread_id=%s",
        orchestrate_request.task_id, thread_id,
    )

    async def _run_background():
        try:
            result = await handle_background(orchestrate_request)
            await kotlin_client.report_status_change(
                task_id=orchestrate_request.task_id,
                thread_id=thread_id,
                status="done",
                summary=result.get("summary", ""),
                branch=result.get("branch"),
                artifacts=result.get("artifacts", []),
                keep_environment_running=result.get("keep_environment_running", False),
            )
        except asyncio.CancelledError:
            logger.info("ORCHESTRATE_V2_INTERRUPTED | thread_id=%s — preempted by foreground", thread_id)
            await kotlin_client.report_status_change(
                task_id=orchestrate_request.task_id,
                thread_id=thread_id,
                status="error",
                error="Background task interrupted (preempted by foreground chat)",
            )
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
# Request/Response models
# ---------------------------------------------------------------------------

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


