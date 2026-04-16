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
from app.llm.router_client import RouteDecision
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
    route: RouteDecision | None = None,
    max_tier: str = "NONE",
    estimated_tokens: int = 0,
    client_id: str | None = None,
    deadline_iso: str | None = None,
    priority: str = "NORMAL",
):
    """Unified LLM completion call with optional timeout and route override.

    If a route is provided (from route_request()), uses the route decision:
    - target == "openrouter" → OpenRouter model via litellm
    - target == "local" → local model via Ollama router

    Implements two fallback strategies:
    1. Timeout fallback: local GPU times out → retry on OpenRouter
    2. Model error fallback: OpenRouter model fails → try next model in queue
    """
    # Use route to determine actual tier and model
    effective_tier = tier
    extra_headers = foreground_headers("FOREGROUND")
    model_override = None
    api_base_override = None

    api_key_override = None

    if route and route.target == "openrouter" and route.model:
        effective_tier = ModelTier.CLOUD_OPENROUTER
        model_override = route.model
        api_key_override = route.api_key
    elif route and route.target == "local" and route.model:
        model_override = route.model
        api_base_override = route.api_base

    coro = llm_provider.completion(
        messages=messages,
        tier=effective_tier,
        tools=tools,
        max_tokens=max_tokens,
        temperature=temperature,
        extra_headers=extra_headers,
        model_override=model_override,
        api_base_override=api_base_override,
        api_key_override=api_key_override,
    )
    try:
        if timeout:
            result = await asyncio.wait_for(coro, timeout=timeout)
        else:
            result = await coro
    except (TokenTimeoutError, asyncio.TimeoutError):
        # Timeout fallback: if local GPU timed out, try cloud via router
        if route and route.target == "local":
            from app.llm.router_client import route_request
            fallback = await route_request(
                capability="chat", estimated_tokens=48_000,
                deadline_iso=deadline_iso, priority=priority, client_id=client_id,
            )
            if fallback.target == "openrouter" and fallback.model:
                logger.info("Local GPU timeout, falling back to OpenRouter %s", fallback.model)
                return await llm_provider.completion(
                    messages=messages,
                    tier=ModelTier.CLOUD_OPENROUTER,
                    tools=tools,
                    max_tokens=max_tokens,
                    temperature=temperature,
                    extra_headers=foreground_headers("FOREGROUND"),
                    model_override=fallback.model,
                    api_key_override=fallback.api_key,
                )
        raise
    except Exception as e:
        # Model error fallback: if OpenRouter model failed, try next model in queue
        if effective_tier != ModelTier.CLOUD_OPENROUTER or not model_override:
            raise
        return await _retry_with_next_model(
            e, model_override, messages, tools, max_tokens, temperature,
            max_tier=max_tier, estimated_tokens=estimated_tokens,
        )

    # Empty response check OUTSIDE try/except — prevents double retry
    # (ValueError from _retry_with_next_model must NOT be caught by except Exception above)
    if effective_tier == ModelTier.CLOUD_OPENROUTER and model_override and result.choices:
        msg = result.choices[0].message
        content = getattr(msg, "content", None) or ""
        tool_calls = getattr(msg, "tool_calls", None)
        if not content.strip() and not tool_calls:
            finish = getattr(result.choices[0], "finish_reason", None)
            logger.warning("Empty response from OpenRouter %s (finish_reason=%s), retrying",
                           model_override, finish)
            from app.llm.router_client import report_model_error
            await report_model_error(
                model_override,
                f"Empty response (finish_reason={finish})",
            )
            return await _retry_with_next_model(
                ValueError(f"Empty response from {model_override}"),
                model_override, messages, tools, max_tokens, temperature,
                max_tier=max_tier, estimated_tokens=estimated_tokens,
            )

    return result


_MAX_MODEL_RETRIES_SAFETY_CAP = 20  # Safety cap only — actual limit is queue size


async def _retry_with_next_model(
    original_error: Exception,
    failed_model: str,
    messages: list[dict],
    tools: list[dict] | None,
    max_tokens: int,
    temperature: float,
    max_tier: str = "NONE",
    estimated_tokens: int = 0,
    deadline_iso: str | None = None,
    priority: str = "NORMAL",
    client_id: str | None = None,
):
    """Try next models in queue after a cloud model failure."""
    from app.llm.router_client import route_request, report_model_error

    skip_models = [failed_model]
    extra_headers = foreground_headers("FOREGROUND")

    for attempt in range(_MAX_MODEL_RETRIES_SAFETY_CAP):
        fallback = await route_request(
            capability="chat",
            max_tier=max_tier,
            estimated_tokens=estimated_tokens,
            deadline_iso=deadline_iso,
            priority=priority,
            skip_models=skip_models,
            require_tools=bool(tools),
            client_id=client_id,
        )
        if fallback.target != "openrouter" or not fallback.model or fallback.model in skip_models:
            logger.warning("No more cloud models available after %s failed (skip=%s)",
                           failed_model, skip_models)
            break

        logger.info("OpenRouter model %s failed, trying fallback %s (attempt %d)",
                     failed_model, fallback.model, attempt + 1)
        try:
            result = await llm_provider.completion(
                messages=messages,
                tier=ModelTier.CLOUD_OPENROUTER,
                tools=tools,
                max_tokens=max_tokens,
                temperature=temperature,
                extra_headers=extra_headers,
                model_override=fallback.model,
                api_key_override=fallback.api_key,
            )
            # Check for empty response from fallback model too
            if not result.choices:
                skip_models.append(fallback.model)
                continue
            msg = result.choices[0].message
            content = getattr(msg, "content", None) or ""
            tool_calls = getattr(msg, "tool_calls", None)
            if not content.strip() and not tool_calls:
                finish = getattr(result.choices[0], "finish_reason", None)
                logger.warning("Fallback model %s also returned empty (finish_reason=%s)",
                               fallback.model, finish)
                await report_model_error(
                    fallback.model,
                    f"Empty response (finish_reason={finish})",
                )
                skip_models.append(fallback.model)
                continue
            return result
        except Exception as retry_err:
            logger.warning("Fallback model %s also failed: %s", fallback.model, retry_err)
            await report_model_error(
                fallback.model,
                str(retry_err)[:500],
            )
            skip_models.append(fallback.model)

    # All cloud models exhausted at this instant. Strategy:
    # 1. If a paused model can recover within RECOVER_WAIT_S → wait for it
    #    (preferred over a degraded local fallback for high-quality models).
    # 2. Otherwise → fall back to local GPU.
    RECOVER_WAIT_S = 20.0
    try:
        from app.llm.router_client import wait_for_any_model_recovery
        recovered = await wait_for_any_model_recovery(
            max_tier=max_tier, max_wait_s=RECOVER_WAIT_S,
        )
        if recovered:
            logger.info("Cloud queue empty — waited %.1fs, model %s recovered, retrying",
                        recovered.get("waited", 0), recovered.get("model"))
            # Re-enter routing flow now that a model is back
            fallback = await route_request(
                capability="chat",
                max_tier=max_tier,
                estimated_tokens=estimated_tokens,
                deadline_iso=deadline_iso,
                priority=priority,
                require_tools=bool(tools),
                client_id=client_id,
            )
            if fallback.target == "openrouter" and fallback.model:
                try:
                    return await llm_provider.completion(
                        messages=messages,
                        tier=ModelTier.CLOUD_OPENROUTER,
                        tools=tools,
                        max_tokens=max_tokens,
                        temperature=temperature,
                        extra_headers=extra_headers,
                        model_override=fallback.model,
                        api_key_override=fallback.api_key,
                    )
                except Exception as recovered_err:
                    logger.warning("Recovered model %s failed too: %s",
                                   fallback.model, recovered_err)
    except Exception as wait_err:
        logger.debug("wait_for_any_model_recovery failed: %s", wait_err)

    # Final last-resort fallback to local GPU
    logger.info("All cloud models failed, falling back to local GPU (skip=%s)", skip_models)
    try:
        return await llm_provider.completion(
            messages=messages,
            tier=ModelTier.LOCAL_STANDARD,
            tools=tools,
            max_tokens=max_tokens,
            temperature=temperature,
            extra_headers=foreground_headers("FOREGROUND"),
        )
    except Exception as local_err:
        logger.warning("Local GPU fallback also failed: %s", local_err)
        raise original_error


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
