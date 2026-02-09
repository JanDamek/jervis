"""LLM provider abstraction using litellm.

Supports local Ollama models and cloud providers (Anthropic, Google).
Implements EscalationPolicy for tier selection.

ALL calls use streaming + heartbeat liveness detection:
- No hard timeouts on LLM calls
- Liveness = tokens keep arriving
- If no token for HEARTBEAT_DEAD_SECONDS → HeartbeatTimeoutError

IMPORTANT – Cloud models are NOT failure fallbacks.
Cloud tiers exist only for legitimate capability needs:
- Ultra-large context (>49k tokens → Gemini 1M)
- Critical architecture/design decisions
- Explicit user preference for quality
Local model failures are NEVER escalated to cloud.
"""

from __future__ import annotations

import asyncio
import logging
from typing import AsyncIterator

import litellm

from app.config import settings
from app.models import Complexity, ModelTier

logger = logging.getLogger(__name__)

# Heartbeat: no token for this long = dead
HEARTBEAT_DEAD_SECONDS = 300  # 5 min


class HeartbeatTimeoutError(Exception):
    """LLM stopped sending tokens (heartbeat dead)."""
    pass


# Model configuration per tier
TIER_CONFIG: dict[ModelTier, dict] = {
    ModelTier.LOCAL_FAST: {
        "model": f"ollama/{settings.default_local_model}",
        "api_base": settings.ollama_url,
        "num_ctx": 8192,
    },
    ModelTier.LOCAL_STANDARD: {
        "model": f"ollama/{settings.default_local_model}",
        "api_base": settings.ollama_url,
        "num_ctx": 32768,
    },
    ModelTier.LOCAL_LARGE: {
        "model": f"ollama/{settings.default_local_model}",
        "api_base": settings.ollama_url,
        "num_ctx": 49152,
    },
    ModelTier.CLOUD_REASONING: {
        "model": f"anthropic/{settings.default_cloud_model}",
    },
    ModelTier.CLOUD_CODING: {
        "model": f"anthropic/{settings.default_cloud_model}",
    },
    ModelTier.CLOUD_PREMIUM: {
        "model": f"anthropic/{settings.default_premium_model}",
    },
    ModelTier.CLOUD_LARGE_CONTEXT: {
        "model": f"google/{settings.default_large_context_model}",
    },
}


class EscalationPolicy:
    """Decides when to use cloud models instead of local.

    RULES (invariant):
    - Local models (Ollama) are the DEFAULT – always used when sufficient
    - Cloud models are NEVER failure fallbacks for local models
    - Cloud is used ONLY for legitimate capability needs:
      1. Ultra-large context (>49k tokens) → CLOUD_LARGE_CONTEXT (Gemini, 1M)
      2. Critical architecture/design → CLOUD_REASONING / CLOUD_PREMIUM
      3. Critical code changes → CLOUD_CODING
      4. Explicit user preference "quality" → CLOUD_REASONING
    - Gemini is ONLY for context that exceeds local capacity (absolute necessity)
    """

    def select_tier(
        self,
        task_type: str,
        complexity: Complexity,
        context_tokens: int = 0,
        user_preference: str = "balanced",
    ) -> ModelTier:
        # 1. Ultra-large context → Gemini (only when absolutely necessary)
        if context_tokens > 49_000:
            return ModelTier.CLOUD_LARGE_CONTEXT

        # 2. User explicitly wants quality → cloud reasoning
        if user_preference == "quality":
            return ModelTier.CLOUD_REASONING

        # 3. Critical complexity → cloud
        if complexity == Complexity.CRITICAL:
            if task_type in ("architecture", "design_review"):
                return ModelTier.CLOUD_PREMIUM
            return ModelTier.CLOUD_CODING

        # 4. Complex architecture → cloud reasoning
        if task_type in ("architecture", "design_review") and complexity == Complexity.COMPLEX:
            return ModelTier.CLOUD_REASONING

        # 5. Complex code changes → cloud coding
        if task_type == "code_change" and complexity == Complexity.COMPLEX:
            return ModelTier.CLOUD_CODING

        # 6. Default: local tiers based on context size
        if context_tokens > 32_000:
            return ModelTier.LOCAL_LARGE
        if context_tokens > 8_000:
            return ModelTier.LOCAL_STANDARD
        return ModelTier.LOCAL_FAST


class LLMProvider:
    """Unified LLM provider using litellm.

    All calls use streaming + heartbeat:
    - Internally streams, collects tokens, returns full response
    - HeartbeatTimeoutError if no token for HEARTBEAT_DEAD_SECONDS
    """

    def __init__(self):
        self.escalation = EscalationPolicy()

    async def completion(
        self,
        messages: list[dict],
        tier: ModelTier = ModelTier.LOCAL_STANDARD,
        tools: list[dict] | None = None,
        temperature: float = 0.1,
        max_tokens: int = 8192,
    ) -> dict:
        """Call LLM with streaming + heartbeat liveness detection.

        Internally streams, accumulates tokens, returns assembled response
        in the same format as litellm non-streaming response.
        Tool calls are not streamed (litellm limitation) — falls back to blocking.
        """
        config = TIER_CONFIG[tier]

        # Tool calls can't be reliably streamed — use blocking call
        if tools:
            return await self._blocking_completion(
                config, messages, tools, temperature, max_tokens,
            )

        return await self._streaming_completion(
            config, messages, temperature, max_tokens,
        )

    async def _streaming_completion(
        self,
        config: dict,
        messages: list[dict],
        temperature: float,
        max_tokens: int,
    ) -> dict:
        """Stream LLM response with heartbeat timeout."""
        kwargs: dict = {
            "model": config["model"],
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": True,
        }

        if config.get("api_base"):
            kwargs["api_base"] = config["api_base"]
        if config.get("num_ctx"):
            kwargs["num_ctx"] = config["num_ctx"]

        logger.info("LLM streaming call: model=%s", config["model"])

        response = await litellm.acompletion(**kwargs)

        # Accumulate streamed content with heartbeat
        content_parts: list[str] = []
        token_count = 0

        async for chunk in self._iter_with_heartbeat(response):
            delta = chunk.choices[0].delta if chunk.choices else None
            if delta and delta.content:
                content_parts.append(delta.content)
                token_count += 1

        content = "".join(content_parts)
        logger.info(
            "LLM streaming complete: model=%s, %d tokens, %d chars",
            config["model"], token_count, len(content),
        )

        # Return in litellm-compatible format
        return _build_response(content, config["model"])

    async def _blocking_completion(
        self,
        config: dict,
        messages: list[dict],
        tools: list[dict] | None,
        temperature: float,
        max_tokens: int,
    ) -> dict:
        """Blocking LLM call (for tool calls)."""
        kwargs: dict = {
            "model": config["model"],
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }

        if config.get("api_base"):
            kwargs["api_base"] = config["api_base"]
        if config.get("num_ctx"):
            kwargs["num_ctx"] = config["num_ctx"]
        if tools:
            kwargs["tools"] = tools

        logger.info("LLM blocking call (tools): model=%s", config["model"])
        response = await litellm.acompletion(**kwargs)
        return response

    async def _iter_with_heartbeat(self, stream):
        """Iterate over streaming chunks with heartbeat timeout.

        Raises HeartbeatTimeoutError if no chunk arrives for HEARTBEAT_DEAD_SECONDS.
        """
        aiter = stream.__aiter__()
        while True:
            try:
                chunk = await asyncio.wait_for(
                    aiter.__anext__(),
                    timeout=HEARTBEAT_DEAD_SECONDS,
                )
                yield chunk
            except asyncio.TimeoutError:
                raise HeartbeatTimeoutError(
                    f"LLM stopped sending tokens for {HEARTBEAT_DEAD_SECONDS}s"
                )
            except StopAsyncIteration:
                return

    async def stream_completion(
        self,
        messages: list[dict],
        tier: ModelTier = ModelTier.LOCAL_STANDARD,
        temperature: float = 0.1,
        max_tokens: int = 8192,
    ) -> AsyncIterator:
        """Stream LLM response (raw chunks for caller to process)."""
        config = TIER_CONFIG[tier]

        kwargs: dict = {
            "model": config["model"],
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": True,
        }

        if config.get("api_base"):
            kwargs["api_base"] = config["api_base"]
        if config.get("num_ctx"):
            kwargs["num_ctx"] = config["num_ctx"]

        response = await litellm.acompletion(**kwargs)
        return response

    def select_tier(
        self,
        task_type: str = "general",
        complexity: Complexity = Complexity.MEDIUM,
        context_tokens: int = 0,
        user_preference: str = "balanced",
    ) -> ModelTier:
        """Select model tier based on task characteristics."""
        return self.escalation.select_tier(
            task_type=task_type,
            complexity=complexity,
            context_tokens=context_tokens,
            user_preference=user_preference,
        )


def _build_response(content: str, model: str) -> object:
    """Build a litellm-compatible response object from streamed content."""

    class _Message:
        def __init__(self, c):
            self.content = c
            self.tool_calls = None

    class _Choice:
        def __init__(self, c):
            self.message = _Message(c)

    class _Response:
        def __init__(self, c, m):
            self.choices = [_Choice(c)]
            self.model = m

    return _Response(content, model)


# Singleton
llm_provider = LLMProvider()
