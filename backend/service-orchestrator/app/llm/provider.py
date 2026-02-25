"""LLM provider abstraction using litellm.

Supports local Ollama models and cloud providers (Anthropic, OpenAI, Google).
Implements EscalationPolicy for local-first tier selection with cloud fallback.

Timeout strategy:
- Streaming calls: per-chunk token-arrival timeout (TOKEN_TIMEOUT_SECONDS). As long as
  tokens keep arriving, the call can run indefinitely.
- Blocking calls (tool calls): tier-based timeout via TIER_TIMEOUT_SECONDS.
  GPU tiers (≤32k): 300s. GPU-boundary (48k): 600s. CPU-spill (128k+): 900-1200s.

Cloud model usage:
- Always try local Ollama first
- Cloud only on local failure, with per-provider settings
- Three cloud providers: Anthropic (reasoning), OpenAI (code editing), Gemini (large context)
- Cloud ONLY used when explicitly allowed in project rules (auto_use_*)

Hardening:
- W-21: Rate limiting — asyncio.Semaphore per priority level
"""

from __future__ import annotations

import asyncio
import logging
from typing import AsyncIterator

import litellm

from app.config import settings
from app.models import Complexity, ModelTier

logger = logging.getLogger(__name__)

# Token-arrival timeout: no token for this long = stream dead
TOKEN_TIMEOUT_SECONDS = 300  # 5 min

# W-21: Rate limiting semaphores
# Ollama can only handle limited concurrent requests
_SEMAPHORE_LOCAL = asyncio.Semaphore(2)     # Max 2 concurrent local LLM calls
_SEMAPHORE_CLOUD = asyncio.Semaphore(5)     # Max 5 concurrent cloud calls


class TokenTimeoutError(Exception):
    """LLM stopped sending tokens within the allowed timeout."""
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
        "num_ctx": 40960,  # 40k — fits in P40 VRAM (30b weights ~17GB + 40k KV ~4GB < 24GB)
    },
    ModelTier.LOCAL_XLARGE: {
        "model": f"ollama/{settings.default_local_model}",
        "api_base": settings.ollama_url,
        "num_ctx": 131072,  # 128k — CPU RAM spill (~7-12 tok/s)
    },
    ModelTier.LOCAL_XXLARGE: {
        "model": f"ollama/{settings.default_local_model}",
        "api_base": settings.ollama_url,
        "num_ctx": 262144,  # 256k — qwen3 max
    },
    ModelTier.CLOUD_REASONING: {
        "model": f"anthropic/{settings.default_cloud_model}",
    },
    ModelTier.CLOUD_CODING: {
        "model": f"openai/{settings.default_openai_model}",
    },
    ModelTier.CLOUD_PREMIUM: {
        "model": f"anthropic/{settings.default_premium_model}",
    },
    ModelTier.CLOUD_LARGE_CONTEXT: {
        "model": f"google/{settings.default_large_context_model}",
    },
}

# Blocking call timeout per tier (seconds).
# GPU tiers (≤32k, ~30 tok/s): 300s is plenty.
# GPU-boundary tier (40k): 600s — safety margin if KV cache approaches VRAM limit.
# CPU-spill tiers (~7-12 tok/s): need longer — large context = slow generation.
# Cloud tiers: fast APIs, 300s is fine.
TIER_TIMEOUT_SECONDS: dict[ModelTier, int] = {
    ModelTier.LOCAL_FAST:           300,   # 8k ctx, GPU
    ModelTier.LOCAL_STANDARD:       300,   # 32k ctx, GPU
    ModelTier.LOCAL_LARGE:          600,   # 40k ctx, GPU boundary — safety margin for VRAM limit
    ModelTier.LOCAL_XLARGE:         900,   # 128k ctx, CPU spill (~7-12 tok/s)
    ModelTier.LOCAL_XXLARGE:       1200,   # 256k ctx, CPU, slower
    ModelTier.CLOUD_REASONING:     300,
    ModelTier.CLOUD_CODING:        300,
    ModelTier.CLOUD_PREMIUM:       300,
    ModelTier.CLOUD_LARGE_CONTEXT: 300,
}


# ---------------------------------------------------------------------------
# Pre-trim: cut oversized user messages to fit tier context window
# ---------------------------------------------------------------------------

def _estimate_max_user_chars(num_ctx: int, messages: list[dict], max_tokens: int) -> int | None:
    """Estimate max chars allowed for the largest user message.

    Returns None if no trimming is needed (everything fits).
    Returns max char count for user message content if trimming is needed.
    """
    if not num_ctx:
        return None

    # Total budget in chars (1 token ≈ 4 chars)
    total_chars = num_ctx * 4

    # Subtract non-user overhead + output reserve
    overhead = max_tokens * 4  # output reserve
    for msg in messages:
        if msg.get("role") != "user":
            overhead += len(msg.get("content", "") or "") + 20  # +20 for role/formatting

    available = total_chars - overhead
    if available < 4000:
        available = 4000  # absolute minimum

    # Check if any user message exceeds budget
    max_user_len = max(
        (len(msg.get("content", "") or "") for msg in messages if msg.get("role") == "user"),
        default=0,
    )
    if max_user_len <= available:
        return None

    return available


def _trim_content(content: str, max_chars: int) -> str:
    """Trim content to max_chars preserving head (75%) + tail (25%).

    Inserts a truncation marker showing how many chars were removed.
    """
    if len(content) <= max_chars:
        return content

    marker = f"\n\n[… {len(content) - max_chars} znaků vynecháno pro LLM kontext …]\n\n"
    usable = max_chars - len(marker)
    if usable < 1000:
        usable = 1000

    head_len = int(usable * 0.75)
    tail_len = usable - head_len
    return content[:head_len] + marker + content[-tail_len:]


def _trim_messages_for_context(
    messages: list[dict], num_ctx: int, max_tokens: int,
) -> list[dict]:
    """Return messages with OLD user content trimmed to fit tier's context window.

    NO-TRIM PRINCIPLE: The LAST user message (current message) is NEVER trimmed.
    Only older conversation history messages may be trimmed.
    Current user message should have been summarized by the handler before
    reaching the LLM provider. If it still overflows, we log a warning
    but do NOT truncate it — the model handles the overflow via Ollama's
    internal truncation (which at least doesn't silently lose data).

    Non-user messages are never trimmed.
    Returns a new list (does not mutate input).
    """
    max_chars = _estimate_max_user_chars(num_ctx, messages, max_tokens)
    if max_chars is None:
        return messages  # no trimming needed

    # Find the LAST user message index (current message — never trim)
    last_user_idx = -1
    for i in range(len(messages) - 1, -1, -1):
        if messages[i].get("role") == "user":
            last_user_idx = i
            break

    trimmed = []
    for i, msg in enumerate(messages):
        if msg.get("role") == "user" and len(msg.get("content", "") or "") > max_chars:
            if i == last_user_idx:
                # NO-TRIM: Current user message — never truncate
                logger.warning(
                    "LLM pre-trim: SKIPPING current user message (%d chars > %d max) — no-trim principle. "
                    "Message should have been summarized by handler.",
                    len(msg.get("content", "")), max_chars,
                )
                trimmed.append(msg)
            else:
                # OLD conversation history — safe to trim
                new_msg = dict(msg)
                original_len = len(new_msg["content"])
                new_msg["content"] = _trim_content(new_msg["content"], max_chars)
                logger.info(
                    "LLM pre-trim: history message %d → %d chars (num_ctx=%d)",
                    original_len, len(new_msg["content"]), num_ctx,
                )
                trimmed.append(new_msg)
        else:
            trimmed.append(msg)
    return trimmed


class EscalationPolicy:
    """Local-first model selection. Cloud only via explicit policy check."""

    def select_local_tier(self, context_tokens: int = 0) -> ModelTier:
        """Always returns a local tier based on context size.

        Qwen3 supports up to 256k context. P40 GPU VRAM fits 40k tokens
        for 30b model (~17GB weights + ~4GB KV cache < 24GB VRAM).
        Above 40k spills to CPU RAM, still works at reduced speed (~7-12 tok/s).
        """
        if context_tokens > 128_000:
            return ModelTier.LOCAL_XXLARGE  # 256k — qwen3 max
        if context_tokens > 40_000:
            return ModelTier.LOCAL_XLARGE   # 128k — CPU RAM spill
        if context_tokens > 32_000:
            return ModelTier.LOCAL_LARGE    # 40k — GPU VRAM limit
        if context_tokens > 8_000:
            return ModelTier.LOCAL_STANDARD # 32k
        return ModelTier.LOCAL_FAST         # 8k

    def suggest_cloud_tier(
        self,
        context_tokens: int = 0,
        auto_providers: set[str] | None = None,
        task_type: str = "general",
    ) -> ModelTier | None:
        """Suggest best cloud tier based on enabled providers and task type.

        Returns None if no suitable provider is auto-enabled.
        """
        providers = auto_providers or set()

        # Large context → Gemini only
        if context_tokens > 40_000:
            return ModelTier.CLOUD_LARGE_CONTEXT if "gemini" in providers else None

        has_anthropic = "anthropic" in providers
        has_openai = "openai" in providers

        # Both → pick by task type
        if has_anthropic and has_openai:
            if task_type in ("architecture", "design_review", "decomposition"):
                return ModelTier.CLOUD_REASONING   # Anthropic
            return ModelTier.CLOUD_CODING           # OpenAI

        if has_anthropic:
            return ModelTier.CLOUD_REASONING
        if has_openai:
            return ModelTier.CLOUD_CODING

        return None  # No auto-enabled provider

    def get_available_providers(self) -> set[str]:
        """Providers with API keys configured (can be used via user approval)."""
        available = set()
        if settings.anthropic_api_key:
            available.add("anthropic")
        if settings.openai_api_key:
            available.add("openai")
        if settings.google_api_key:
            available.add("gemini")
        return available

    def best_available_cloud_tier(
        self, context_tokens: int = 0, task_type: str = "general",
    ) -> ModelTier | None:
        """Best cloud tier from ANY configured provider (for interrupt description)."""
        return self.suggest_cloud_tier(
            context_tokens, self.get_available_providers(), task_type,
        )

    def select_tier(
        self,
        task_type: str = "general",
        complexity: Complexity = Complexity.MEDIUM,
        context_tokens: int = 0,
        user_preference: str = "balanced",
    ) -> ModelTier:
        """Backward-compatible: always returns local tier."""
        return self.select_local_tier(context_tokens)


class LLMProvider:
    """Unified LLM provider using litellm.

    All calls use streaming + token-arrival timeout:
    - Internally streams, collects tokens, returns full response
    - TokenTimeoutError if no token for TOKEN_TIMEOUT_SECONDS
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
        extra_headers: dict[str, str] | None = None,
    ) -> dict:
        """Call LLM with streaming + token-arrival timeout.

        Internally streams, accumulates tokens, returns assembled response
        in the same format as litellm non-streaming response.
        Tool calls are not streamed (litellm limitation) — falls back to blocking.

        W-21: Rate-limited via asyncio.Semaphore per provider type.
        """
        config = TIER_CONFIG[tier]

        # W-21: Select appropriate rate limiter
        is_cloud = tier.value.startswith("cloud_")
        semaphore = _SEMAPHORE_CLOUD if is_cloud else _SEMAPHORE_LOCAL

        async with semaphore:
            # Tool calls can't be reliably streamed — use blocking call
            if tools:
                return await self._blocking_completion(
                    config, messages, tools, temperature, max_tokens, extra_headers, tier,
                )

            return await self._streaming_completion(
                config, messages, temperature, max_tokens, extra_headers,
            )

    @staticmethod
    async def _call_with_retry(fn, kwargs: dict, model: str, max_retries: int = 2) -> object:
        """Call LLM function with retry for transient Ollama errors.

        Retries on: connection errors, "Operation not allowed", 503/429 status.
        Uses exponential backoff: 2s, 4s.
        """
        last_exc: Exception | None = None
        for attempt in range(1 + max_retries):
            try:
                return await fn(**kwargs)
            except (OSError, ConnectionError) as e:
                last_exc = e
                logger.warning("LLM connection error (attempt %d/%d, model=%s): %s",
                               attempt + 1, 1 + max_retries, model, e)
            except Exception as e:
                err_str = str(e).lower()
                retryable = (
                    "operation not allowed" in err_str
                    or "service unavailable" in err_str
                    or "rate limit" in err_str
                    or "503" in err_str
                    or "429" in err_str
                )
                if not retryable or attempt >= max_retries:
                    raise
                last_exc = e
                logger.warning("LLM transient error (attempt %d/%d, model=%s): %s",
                               attempt + 1, 1 + max_retries, model, e)

            delay = 2 ** (attempt + 1)
            logger.info("LLM retry in %ds (model=%s)", delay, model)
            await asyncio.sleep(delay)

        raise last_exc  # type: ignore[misc]

    async def _streaming_completion(
        self,
        config: dict,
        messages: list[dict],
        temperature: float,
        max_tokens: int,
        extra_headers: dict[str, str] | None = None,
    ) -> dict:
        """Stream LLM response with token-arrival timeout."""
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
            # Pre-trim oversized user messages to avoid sending excess data to Ollama
            kwargs["messages"] = _trim_messages_for_context(messages, config["num_ctx"], max_tokens)
        if extra_headers:
            kwargs["extra_headers"] = extra_headers

        logger.info("LLM streaming call: model=%s headers=%s", config["model"], extra_headers or {})

        response = await self._call_with_retry(litellm.acompletion, kwargs, config["model"])

        # Accumulate streamed content with token-arrival timeout
        content_parts: list[str] = []
        token_count = 0

        async for chunk in self._iter_with_timeout(response):
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
        extra_headers: dict[str, str] | None = None,
        tier: ModelTier = ModelTier.LOCAL_STANDARD,
    ) -> dict:
        """Blocking LLM call (for tool calls) with tier-based timeout."""
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
            # Pre-trim oversized user messages to avoid sending excess data to Ollama
            kwargs["messages"] = _trim_messages_for_context(messages, config["num_ctx"], max_tokens)
        if tools:
            kwargs["tools"] = tools
        if extra_headers:
            kwargs["extra_headers"] = extra_headers

        timeout = TIER_TIMEOUT_SECONDS.get(tier, TOKEN_TIMEOUT_SECONDS)
        logger.info("LLM blocking call (tools): model=%s tier=%s timeout=%ds api_base=%s headers=%s",
                     config["model"], tier.value, timeout, config.get("api_base"), extra_headers or {})
        logger.info("LLM request kwargs: %s", {k: v for k, v in kwargs.items() if k not in ["messages"]})
        logger.info("LLM request has tools: %s, num_tools: %d", bool(kwargs.get("tools")), len(kwargs.get("tools", [])))
        try:
            response = await asyncio.wait_for(
                self._call_with_retry(litellm.acompletion, kwargs, config["model"]),
                timeout=timeout,
            )
        except asyncio.TimeoutError:
            raise TokenTimeoutError(
                f"LLM blocking call timed out after {timeout}s (tier={tier.value})"
            )

        # DEBUG: Log RAW response to debug tool_calls parsing
        try:
            import json
            raw_response = response.model_dump() if hasattr(response, "model_dump") else dict(response)
            logger.info("LLM RAW response: %s", json.dumps(raw_response, default=str)[:1000])
        except Exception as e:
            logger.warning("Failed to log raw response: %s", e)

        # DEBUG: Log response details
        try:
            choice = response.choices[0] if response.choices else None
            if choice:
                msg = choice.message
                logger.info(
                    "LLM blocking response: finish_reason=%s, has_content=%s, content_len=%d, has_tool_calls=%s",
                    choice.finish_reason,
                    bool(msg.content),
                    len(msg.content or ""),
                    bool(getattr(msg, "tool_calls", None)),
                )
                if msg.content:
                    logger.info("LLM blocking content: %s", msg.content[:500])
                if hasattr(msg, "tool_calls") and msg.tool_calls:
                    logger.info("LLM tool_calls: %s", msg.tool_calls)
        except Exception as e:
            logger.warning("Failed to log response details: %s", e)

        return response

    async def _iter_with_timeout(self, stream):
        """Iterate over streaming chunks with token-arrival timeout.

        Raises TokenTimeoutError if no chunk arrives for TOKEN_TIMEOUT_SECONDS.
        """
        aiter = stream.__aiter__()
        while True:
            try:
                chunk = await asyncio.wait_for(
                    aiter.__anext__(),
                    timeout=TOKEN_TIMEOUT_SECONDS,
                )
                yield chunk
            except asyncio.TimeoutError:
                raise TokenTimeoutError(
                    f"LLM stopped sending tokens for {TOKEN_TIMEOUT_SECONDS}s"
                )
            except StopAsyncIteration:
                return

    async def stream_completion(
        self,
        messages: list[dict],
        tier: ModelTier = ModelTier.LOCAL_STANDARD,
        temperature: float = 0.1,
        max_tokens: int = 8192,
        extra_headers: dict[str, str] | None = None,
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
        if extra_headers:
            kwargs["extra_headers"] = extra_headers

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
