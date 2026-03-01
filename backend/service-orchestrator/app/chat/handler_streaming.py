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
from app.config import settings, foreground_headers
from app.llm.provider import llm_provider, TokenTimeoutError
from app.llm.openrouter_resolver import Route, _first_cloud_model
from app.models import ModelTier

logger = logging.getLogger(__name__)

# Re-exported for callers that import from handler_streaming
STREAM_CHUNK_SIZE = settings.stream_chunk_size


async def call_llm(
    messages: list[dict],
    tier: ModelTier,
    tools: list[dict] | None = None,
    max_tokens: int = settings.default_output_tokens,
    temperature: float = 0.1,
    timeout: float | None = None,
    route: Route | None = None,
):
    """Unified LLM completion call with optional timeout and route override.

    If a route is provided (from select_route()), uses OpenRouter when
    route.target == "openrouter". Falls back to local tier otherwise.

    Implements timeout fallback: if local GPU times out and client has
    OpenRouter enabled, retries on OpenRouter automatically.
    """
    # Use route to determine actual tier and model
    effective_tier = tier
    extra_headers = foreground_headers("FOREGROUND")

    if route and route.target == "openrouter" and route.model:
        effective_tier = ModelTier.CLOUD_OPENROUTER
        extra_headers = {**extra_headers, "X-Route-Model": route.model}

    coro = llm_provider.completion(
        messages=messages,
        tier=effective_tier,
        tools=tools,
        max_tokens=max_tokens,
        temperature=temperature,
        extra_headers=extra_headers,
    )
    try:
        if timeout:
            return await asyncio.wait_for(coro, timeout=timeout)
        return await coro
    except (TokenTimeoutError, asyncio.TimeoutError):
        # Timeout fallback: if local GPU timed out, try FREE cloud model
        if route and route.target == "local":
            fallback_model = await _first_cloud_model("FREE", 48_000)
            if fallback_model:
                logger.info("Local GPU timeout, falling back to OpenRouter %s", fallback_model)
                return await llm_provider.completion(
                    messages=messages,
                    tier=ModelTier.CLOUD_OPENROUTER,
                    tools=tools,
                    max_tokens=max_tokens,
                    temperature=temperature,
                    extra_headers={**foreground_headers("FOREGROUND"), "X-Route-Model": fallback_model},
                )
        raise


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
