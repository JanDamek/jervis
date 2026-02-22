"""Chat API endpoints — FastAPI router.

Endpoints:
- POST /chat                          → Foreground chat handler (new v6 flow)
- POST /internal/prepare-chat-context → replaces Kotlin prepareChatHistoryPayload()
- POST /internal/compress-chat-async  → replaces Kotlin compressIfNeeded()
- POST /internal/compress-chat        → backward-compatible (existing endpoint, refactored)
- POST /orchestrate/v2                → Simplified background handler (new v6 flow)
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

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

    Flow: User message → agentic loop → streamed answer → MongoDB save.
    Streaming happens via kotlin_client push (POST /internal/streaming-token).
    """
    from app.chat.handler import handle_chat

    try:
        result = await handle_chat(request)
        return {
            "success": True,
            "answer": result.get("answer", ""),
            "tool_calls_count": result.get("tool_calls_count", 0),
            "iterations": result.get("iterations", 0),
        }
    except Exception as e:
        logger.exception("Chat handler failed for task %s", request.task_id)
        raise HTTPException(status_code=500, detail=str(e))


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
            )
        except Exception as e:
            logger.exception("Background v2 failed: %s", e)
            await kotlin_client.report_status_change(
                task_id=orchestrate_request.task_id,
                thread_id=thread_id,
                status="error",
                error=str(e),
            )

    asyncio.create_task(_run_background())
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


@router.post("/internal/compress-chat")
async def compress_chat_legacy(request: dict):
    """Legacy compress endpoint — backward compatible with existing Kotlin calls.

    Kotlin sends messages directly -> Python compresses -> returns summary.
    DEPRECATED: Use /internal/compress-chat-async instead.
    Kept during migration for backward compatibility.
    """
    try:
        messages_data = request.get("messages", [])
        previous_summary = request.get("previous_summary")
        task_id = request.get("task_id", request.get("conversation_id", "unknown"))

        if not messages_data:
            return {
                "summary": "",
                "key_decisions": [],
                "topics": [],
                "is_checkpoint": False,
                "checkpoint_reason": None,
            }

        from app.llm.provider import llm_provider
        from app.models import ModelTier

        formatted = []
        for m in messages_data:
            label = {"user": "Uživatel", "assistant": "Jervis"}.get(m.get("role", ""), m.get("role", ""))
            formatted.append(f"[{label}]: {m.get('content', '')[:500]}")
        conversation_text = "\n".join(formatted)

        previous_context = ""
        if previous_summary:
            previous_context = f"\n\nPředchozí kontext konverzace:\n{previous_summary}"

        llm_messages = [
            {
                "role": "system",
                "content": (
                    "Jsi analytik konverzací. Tvůj úkol je shrnout blok konverzace do stručného souhrnu.\n\n"
                    "Pravidla:\n"
                    "- Piš česky\n"
                    "- Souhrn: 2-3 věty shrnující hlavní téma a průběh (max 500 znaků)\n"
                    "- Klíčová rozhodnutí: důležitá rozhodnutí učiněná v konverzaci\n"
                    "- Témata: hlavní témata diskuze (stručné štítky)\n"
                    "- Pokud se směr konverzace ZÁSADNĚ změnil oproti předchozímu kontextu, "
                    "nastav is_checkpoint=true a uveď důvod\n\n"
                    "Odpověz POUZE validním JSON (bez markdown backticks):\n"
                    "{\n"
                    '  "summary": "...",\n'
                    '  "key_decisions": ["rozhodnutí 1", "rozhodnutí 2"],\n'
                    '  "topics": ["téma 1", "téma 2"],\n'
                    '  "is_checkpoint": false,\n'
                    '  "checkpoint_reason": null\n'
                    "}"
                ),
            },
            {
                "role": "user",
                "content": (
                    f"Shrň tento blok konverzace:{previous_context}\n\n"
                    f"Konverzace k shrnutí:\n{conversation_text}"
                ),
            },
        ]

        response = await llm_provider.completion(
            messages=llm_messages,
            tier=ModelTier.LOCAL_FAST,
            max_tokens=2048,
            temperature=0.1,
        )
        content = response.choices[0].message.content

        parsed = _parse_json_response(content)
        if not parsed:
            parsed = {"summary": content[:500]}

        return {
            "summary": parsed.get("summary", content[:500]),
            "key_decisions": parsed.get("key_decisions", []),
            "topics": parsed.get("topics", []),
            "is_checkpoint": parsed.get("is_checkpoint", False),
            "checkpoint_reason": parsed.get("checkpoint_reason"),
        }

    except Exception as e:
        logger.exception("Failed to compress chat for conversation %s", request.get("task_id", request.get("conversation_id")))
        raise HTTPException(status_code=500, detail=str(e))
