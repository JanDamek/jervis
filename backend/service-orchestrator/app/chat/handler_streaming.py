"""Shared streaming, saving, and LLM call helpers for chat handler modules.

Eliminates duplicated patterns across handler, agentic loop, and decompose modules:
- stream_text(): chunked token streaming (was 6× duplicated)
- save_assistant_message(): save + optional compress (was 8× duplicated)
- stream_and_save(): combined stream + save (most common pattern)
- call_llm(): unified LLM completion with optional timeout (was 10× duplicated)
"""
from __future__ import annotations

import asyncio
import logging

from bson import ObjectId

from app.chat.context import chat_context_assembler
from app.chat.models import ChatStreamEvent
from app.config import settings
from app.llm.provider import llm_provider
from app.models import ModelTier

logger = logging.getLogger(__name__)

# Re-exported for callers that import from handler_streaming
STREAM_CHUNK_SIZE = settings.stream_chunk_size


async def call_llm(
    messages: list[dict],
    tier: ModelTier,
    tools: list[dict] | None = None,
    max_tokens: int = 4096,
    temperature: float = 0.1,
    timeout: float | None = None,
):
    """Unified LLM completion call with optional timeout.

    All foreground chat LLM calls go through here — ensures consistent
    parameters and priority headers.
    """
    coro = llm_provider.completion(
        messages=messages,
        tier=tier,
        tools=tools,
        max_tokens=max_tokens,
        temperature=temperature,
        extra_headers={"X-Ollama-Priority": "0"},
    )
    if timeout:
        return await asyncio.wait_for(coro, timeout=timeout)
    return await coro


async def stream_text(text: str):
    """Yield chunked token events for progressive rendering."""
    for i in range(0, len(text), STREAM_CHUNK_SIZE):
        yield ChatStreamEvent(type="token", content=text[i:i + STREAM_CHUNK_SIZE])
        await asyncio.sleep(0.03)


async def save_assistant_message(
    session_id: str,
    content: str,
    metadata: dict | None = None,
    compress: bool = True,
) -> None:
    """Save an assistant message to MongoDB with auto-sequence."""
    await chat_context_assembler.save_message(
        conversation_id=session_id,
        role="ASSISTANT",
        content=content,
        correlation_id=str(ObjectId()),
        sequence=await chat_context_assembler.get_next_sequence(session_id),
        metadata=metadata or {},
    )
    if compress:
        try:
            await chat_context_assembler.maybe_compress(session_id)
        except Exception as e:
            logger.warning("Chat compression failed for session=%s: %s", session_id, e)
