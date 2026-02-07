"""LLM provider abstraction using litellm.

Supports local Ollama models and cloud providers (Anthropic, OpenAI).
Implements EscalationPolicy for automatic tier selection.
"""

from __future__ import annotations

import logging
from typing import AsyncIterator

import litellm

from app.config import settings
from app.models import Complexity, ModelTier

logger = logging.getLogger(__name__)

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
}


class EscalationPolicy:
    """Decides when to escalate from local to cloud model."""

    def select_tier(
        self,
        task_type: str,
        complexity: Complexity,
        context_tokens: int,
        local_failures: int = 0,
        user_preference: str = "balanced",
    ) -> ModelTier:
        # User explicitly wants quality
        if user_preference == "quality":
            return ModelTier.CLOUD_REASONING

        # Local model failed 2x -> escalate
        if local_failures >= 2:
            return ModelTier.CLOUD_REASONING

        # Large context -> cloud (200k+ context)
        if context_tokens > 32_000:
            if user_preference == "economy":
                return ModelTier.LOCAL_LARGE
            return ModelTier.CLOUD_REASONING

        # Complex coding -> cloud
        if task_type == "code_change" and complexity in (
            Complexity.COMPLEX,
            Complexity.CRITICAL,
        ):
            return ModelTier.CLOUD_CODING

        # Architectural decision -> cloud reasoning
        if task_type in ("architecture", "design_review") and complexity != Complexity.SIMPLE:
            return ModelTier.CLOUD_REASONING

        # Critical -> premium
        if complexity == Complexity.CRITICAL:
            return ModelTier.CLOUD_PREMIUM

        # Default: local
        if context_tokens > 16_000:
            return ModelTier.LOCAL_STANDARD
        return ModelTier.LOCAL_FAST


class LLMProvider:
    """Unified LLM provider using litellm."""

    def __init__(self):
        self.escalation = EscalationPolicy()

    async def completion(
        self,
        messages: list[dict],
        tier: ModelTier = ModelTier.LOCAL_STANDARD,
        tools: list[dict] | None = None,
        temperature: float = 0.1,
        max_tokens: int = 4096,
    ) -> dict:
        """Call LLM with specified tier."""
        config = TIER_CONFIG[tier]

        kwargs: dict = {
            "model": config["model"],
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }

        if config.get("api_base"):
            kwargs["api_base"] = config["api_base"]
        if tools:
            kwargs["tools"] = tools

        logger.info("LLM call: tier=%s model=%s", tier, config["model"])
        response = await litellm.acompletion(**kwargs)
        return response

    async def stream_completion(
        self,
        messages: list[dict],
        tier: ModelTier = ModelTier.LOCAL_STANDARD,
        temperature: float = 0.1,
        max_tokens: int = 4096,
    ) -> AsyncIterator:
        """Stream LLM response."""
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

        response = await litellm.acompletion(**kwargs)
        return response

    def select_tier(
        self,
        task_type: str = "general",
        complexity: Complexity = Complexity.MEDIUM,
        context_tokens: int = 0,
        local_failures: int = 0,
        user_preference: str = "balanced",
    ) -> ModelTier:
        """Select model tier based on task characteristics."""
        return self.escalation.select_tier(
            task_type=task_type,
            complexity=complexity,
            context_tokens=context_tokens,
            local_failures=local_failures,
            user_preference=user_preference,
        )


# Singleton
llm_provider = LLMProvider()
