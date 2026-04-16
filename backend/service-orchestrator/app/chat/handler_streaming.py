"""Shared streaming, saving, and LLM call helpers for chat handler modules.

`call_llm` is a thin wrapper over `llm_provider.completion()`, which itself
forwards to the router's `/api/chat`. Cloud model retry on failure lives
inside the router — not here.
"""
from __future__ import annotations

import asyncio
import logging

from bson import ObjectId

from app.chat.context import chat_context_assembler
from app.chat.models import ChatStreamEvent
from app.config import settings
from app.llm.provider import llm_provider, TokenTimeoutError

logger = logging.getLogger(__name__)

STREAM_CHUNK_SIZE = settings.stream_chunk_size


async def call_llm(
    messages: list[dict],
    *,
    tools: list[dict] | None = None,
    max_tokens: int = settings.default_output_tokens,
    temperature: float = 0.1,
    timeout: float | None = None,
    capability: str = "chat",
    max_tier: str = "NONE",
    client_id: str | None = None,
    deadline_iso: str | None = None,
    priority: str = "NORMAL",
    min_model_size: int = 0,
    extra_headers: dict[str, str] | None = None,
):
    """Call the router for an LLM completion.

    Routing (local vs cloud), cross-model retry, and rate limiting all happen
    inside the router. This helper only adds an optional top-level timeout.
    """
    coro = llm_provider.completion(
        messages=messages,
        capability=capability,
        tools=tools,
        max_tokens=max_tokens,
        temperature=temperature,
        client_id=client_id,
        max_tier=max_tier,
        deadline_iso=deadline_iso,
        priority=priority,
        min_model_size=min_model_size,
        extra_headers=extra_headers,
    )
    if timeout:
        return await asyncio.wait_for(coro, timeout=timeout)
    return await coro


async def stream_text(text: str):
    """Yield text as a single token event."""
    yield ChatStreamEvent(type="token", content=text)


async def save_assistant_message(
    session_id: str,
    content: str,
    metadata: dict | None = None,
    compress: bool = True,
    client_id: str | None = None,
    project_id: str | None = None,
    group_id: str | None = None,
) -> None:
    """Save an assistant message to MongoDB with auto-sequence."""
    await chat_context_assembler.save_message(
        conversation_id=session_id,
        role="ASSISTANT",
        content=content,
        correlation_id=str(ObjectId()),
        sequence=await chat_context_assembler.get_next_sequence(session_id),
        metadata=metadata or {},
        client_id=client_id,
        project_id=project_id,
        group_id=group_id,
    )
    if compress:
        try:
            await chat_context_assembler.maybe_compress(session_id)
        except Exception as e:
            logger.warning("Chat compression failed for session=%s: %s", session_id, e)
