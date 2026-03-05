"""LLM provider abstraction using litellm.

Supports local Ollama models and cloud providers (Anthropic, OpenAI, Google).
num_ctx is sent as a FIXED constant per GPU — never changes between requests.
GPU1: 48k (full VRAM, no embedding), GPU2: 32k (30b + embedding 8b coexist).
Changing num_ctx dynamically causes Ollama to reload the model with different
context, spilling to CPU RAM and dropping speed from ~30 tok/s to ~2 tok/s.

Timeout strategy:
- Streaming calls: per-chunk token-arrival timeout (TOKEN_TIMEOUT_SECONDS).
- Blocking calls (tool calls): tier-based timeout via TIER_TIMEOUT_SECONDS.

Router handles all GPU/cloud concurrency — no artificial limits here.
"""

from __future__ import annotations

import asyncio
import logging
import threading
from datetime import datetime
from typing import AsyncIterator

import litellm

from app.config import settings, estimate_tokens
from app.models import ModelTier

logger = logging.getLogger(__name__)

# ── Gemini daily counter ──────────────────────────────────────────────────
_gemini_lock = threading.Lock()
_gemini_daily_count: int = 0
_gemini_count_date: str = ""


def check_gemini_available() -> bool:
    """Check if Gemini daily limit not exceeded."""
    global _gemini_daily_count, _gemini_count_date
    today = datetime.now().strftime("%Y-%m-%d")
    with _gemini_lock:
        if _gemini_count_date != today:
            _gemini_daily_count = 0
            _gemini_count_date = today
        return _gemini_daily_count < settings.gemini_daily_limit


def increment_gemini_counter():
    """Increment Gemini daily counter after a call."""
    global _gemini_daily_count, _gemini_count_date
    today = datetime.now().strftime("%Y-%m-%d")
    with _gemini_lock:
        if _gemini_count_date != today:
            _gemini_daily_count = 0
            _gemini_count_date = today
        _gemini_daily_count += 1
        logger.info("Gemini daily counter: %d/%d", _gemini_daily_count, settings.gemini_daily_limit)

# Token-arrival timeout: no token for this long = stream dead
TOKEN_TIMEOUT_SECONDS = 300  # 5 min

class TokenTimeoutError(Exception):
    """LLM stopped sending tokens within the allowed timeout."""
    pass


# Model configuration per tier — fixed constant num_ctx per GPU, never changes
TIER_CONFIG: dict[ModelTier, dict] = {
    ModelTier.LOCAL_STANDARD: {
        "model": f"ollama/{settings.default_local_model}",
        "api_base": settings.ollama_url,
        "num_ctx": 48000,  # GPU1 — fixed 48k, full VRAM speed (~30 tok/s)
    },
    ModelTier.LOCAL_COMPACT: {
        "model": f"ollama/{settings.default_local_model}",
        "api_base": settings.ollama_url,
        "num_ctx": 32000,  # GPU2 — fixed 32k (30b + embedding 8b coexist)
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
    ModelTier.CLOUD_OPENROUTER: {
        "model": "openrouter/auto",  # Default; overridden at runtime from settings DB
        "api_base": settings.openrouter_api_base,
        "api_key": settings.openrouter_api_key,
    },
}

# Blocking call timeout per tier (seconds).
# Fixed num_ctx = consistent timeouts, no CPU-spill tiers.
TIER_TIMEOUT_SECONDS: dict[ModelTier, int] = {
    ModelTier.LOCAL_STANDARD:       300,   # 48k ctx, GPU1
    ModelTier.LOCAL_COMPACT:        300,   # 32k ctx, GPU2
    ModelTier.CLOUD_REASONING:     300,
    ModelTier.CLOUD_CODING:        300,
    ModelTier.CLOUD_PREMIUM:       300,
    ModelTier.CLOUD_LARGE_CONTEXT: 300,
    ModelTier.CLOUD_OPENROUTER:    300,
}


# ---------------------------------------------------------------------------
# Pre-trim: cut oversized user messages to fit tier context window
# ---------------------------------------------------------------------------

def _estimate_max_user_chars(num_ctx: int, messages: list[dict], max_tokens: int) -> int | None:
    """Estimate max chars allowed for the largest user message.

    Returns None if no trimming is needed (everything fits).
    Returns max char count for user message content if trimming is needed.
    Uses tiktoken for accurate token counting.
    """
    if not num_ctx:
        return None

    # Count non-user tokens + output reserve
    overhead_tokens = max_tokens  # output reserve
    for msg in messages:
        if msg.get("role") != "user":
            overhead_tokens += estimate_tokens(msg.get("content", "") or "") + 5  # +5 for role/formatting

    available_tokens = num_ctx - overhead_tokens
    if available_tokens < 1000:
        available_tokens = 1000  # absolute minimum

    # Convert to approximate chars (conservative: 1 token ≈ 3 chars for mixed content)
    available_chars = available_tokens * 3

    # Check if any user message exceeds budget
    max_user_len = max(
        (len(msg.get("content", "") or "") for msg in messages if msg.get("role") == "user"),
        default=0,
    )
    if max_user_len <= available_chars:
        return None

    return available_chars


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


class LLMProvider:
    """Unified LLM provider using litellm.

    All calls use streaming + token-arrival timeout:
    - Internally streams, collects tokens, returns full response
    - TokenTimeoutError if no token for TOKEN_TIMEOUT_SECONDS
    """

    async def completion(
        self,
        messages: list[dict],
        tier: ModelTier = ModelTier.LOCAL_STANDARD,
        tools: list[dict] | None = None,
        temperature: float = 0.1,
        max_tokens: int = 8192,
        extra_headers: dict[str, str] | None = None,
        model_override: str | None = None,
        api_base_override: str | None = None,
        api_key_override: str | None = None,
    ) -> dict:
        """Call LLM with streaming + token-arrival timeout.

        Internally streams, accumulates tokens, returns assembled response
        in the same format as litellm non-streaming response.
        Tool calls are not streamed (litellm limitation) — falls back to blocking.

        model_override/api_base_override/api_key_override: from router's route decision.
        For OpenRouter: model_override is the cloud model ID (prefixed with "openrouter/").
        For local: model_override is the Ollama model name (prefixed with "ollama/").

        """
        config = dict(TIER_CONFIG[tier])  # Copy to avoid mutating global

        # Gemini daily limit check
        if tier == ModelTier.CLOUD_LARGE_CONTEXT:
            if not check_gemini_available():
                logger.warning(
                    "Gemini daily limit exceeded (%d/%d) — skipping call",
                    _gemini_daily_count, settings.gemini_daily_limit,
                )
                raise TokenTimeoutError(
                    f"Gemini daily limit exceeded ({_gemini_daily_count}/{settings.gemini_daily_limit})"
                )

        # Apply route decision overrides
        if model_override:
            if tier == ModelTier.CLOUD_OPENROUTER:
                config["model"] = f"openrouter/{model_override}"
            elif tier.value.startswith("local_"):
                config["model"] = f"ollama/{model_override}"
            else:
                config["model"] = model_override
        if api_base_override:
            config["api_base"] = api_base_override
        if api_key_override:
            config["api_key"] = api_key_override

        # Tool calls can't be reliably streamed — use blocking call
        if tools:
            result = await self._blocking_completion(
                config, messages, tools, temperature, max_tokens, extra_headers, tier,
            )
            if tier == ModelTier.CLOUD_LARGE_CONTEXT:
                increment_gemini_counter()
            return result

        result = await self._streaming_completion(
            config, messages, temperature, max_tokens, extra_headers,
        )
        if tier == ModelTier.CLOUD_LARGE_CONTEXT:
            increment_gemini_counter()
        return result

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
        if config.get("api_key"):
            kwargs["api_key"] = config["api_key"]
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
        if config.get("api_key"):
            kwargs["api_key"] = config["api_key"]
        if config.get("num_ctx"):
            kwargs["num_ctx"] = config["num_ctx"]
            # Pre-trim oversized user messages to avoid sending excess data to Ollama
            kwargs["messages"] = _trim_messages_for_context(messages, config["num_ctx"], max_tokens)
        if tools:
            kwargs["tools"] = tools
        if extra_headers:
            kwargs["extra_headers"] = extra_headers

        timeout = TIER_TIMEOUT_SECONDS.get(tier, TOKEN_TIMEOUT_SECONDS)
        logger.info("LLM blocking call (tools): model=%s tier=%s timeout=%ds",
                     config["model"], tier.value, timeout)
        logger.debug("LLM request kwargs: %s", {k: v for k, v in kwargs.items() if k not in ["messages"]})
        try:
            response = await asyncio.wait_for(
                self._call_with_retry(litellm.acompletion, kwargs, config["model"]),
                timeout=timeout,
            )
        except asyncio.TimeoutError:
            raise TokenTimeoutError(
                f"LLM blocking call timed out after {timeout}s (tier={tier.value})"
            )
        except Exception as e:
            # Dump message structure on provider errors for debugging
            if "400" in str(e) or "BadRequest" in type(e).__name__:
                import json as _json
                msg_summary = []
                for i, m in enumerate(kwargs.get("messages", [])):
                    role = m.get("role", "?")
                    content = m.get("content")
                    has_tc = bool(m.get("tool_calls"))
                    tc_id = m.get("tool_call_id", "")
                    content_info = f"null" if content is None else f"{type(content).__name__}({len(str(content))}ch)"
                    extras = []
                    if has_tc:
                        tc_names = [tc.get("function", {}).get("name", "?") for tc in m.get("tool_calls", [])]
                        extras.append(f"tool_calls=[{','.join(tc_names)}]")
                    if tc_id:
                        extras.append(f"tool_call_id={tc_id}")
                    extra_str = f" {' '.join(extras)}" if extras else ""
                    msg_summary.append(f"  [{i}] role={role} content={content_info}{extra_str}")
                logger.error(
                    "LLM 400 error — message dump (model=%s, %d msgs, %d tools):\n%s",
                    config["model"], len(kwargs.get("messages", [])),
                    len(kwargs.get("tools", [])),
                    "\n".join(msg_summary),
                )
            raise

        # Log response summary
        try:
            choice = response.choices[0] if response.choices else None
            if choice:
                msg = choice.message
                logger.info(
                    "LLM blocking response: finish_reason=%s, content_len=%d, has_tool_calls=%s",
                    choice.finish_reason,
                    len(msg.content or ""),
                    bool(getattr(msg, "tool_calls", None)),
                )
        except Exception as e:
            logger.debug("Failed to log response summary: %s", e)

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
        """Stream LLM response (raw chunks for caller to process).

        Pre-trims oversized messages for local tiers.
        """
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
        if config.get("api_key"):
            kwargs["api_key"] = config["api_key"]
        if config.get("num_ctx"):
            kwargs["num_ctx"] = config["num_ctx"]
            kwargs["messages"] = _trim_messages_for_context(messages, config["num_ctx"], max_tokens)
        if extra_headers:
            kwargs["extra_headers"] = extra_headers

        return await self._call_with_retry(litellm.acompletion, kwargs, config["model"])

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


async def refresh_openrouter_api_key() -> None:
    """Fetch OpenRouter API key from Kotlin server and update TIER_CONFIG.

    Called at startup and can be called periodically to refresh the key.
    Falls back to env var OPENROUTER_API_KEY if Kotlin server is unreachable.
    """
    import httpx

    url = f"{settings.kotlin_server_url.rstrip('/')}/internal/openrouter-settings"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            data = resp.json()
            api_key = data.get("apiKey", "")
            if api_key:
                TIER_CONFIG[ModelTier.CLOUD_OPENROUTER]["api_key"] = api_key
                logger.info("OpenRouter API key loaded from Kotlin server")
            else:
                logger.warning("OpenRouter API key is empty in Kotlin server settings")
    except Exception as e:
        logger.warning("Failed to fetch OpenRouter API key from Kotlin server: %s", e)
        if settings.openrouter_api_key:
            logger.info("Using OpenRouter API key from environment variable")


# Singleton
llm_provider = LLMProvider()
