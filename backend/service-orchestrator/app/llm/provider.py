"""LLM provider — thin wrapper over jervis-ollama-router /api/chat.

No LiteLLM. No per-provider adapters. No orchestrator-side retry or rate
limiting. The router (backend/service-ollama-router) owns routing, model
selection, cloud failover, and rate limiting. Orchestrator just calls
`/api/chat` with urgency headers and consumes the Ollama-format response.

See KB agent://claude-code/task-routing-unified-design and
agent://claude-code/orchestrator-llm-unification-proposal.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.config import settings
from app.models import ModelTier

logger = logging.getLogger(__name__)


# Legacy tier config surface — orchestrator nodes still read `TIER_CONFIG[tier]`
# to pick `num_ctx` for context-budget math. Actual model routing happens in
# the router; these are just budget hints.
DEFAULT_TIER_CONTEXT = 48_000
TIER_CONFIG: dict = {
    ModelTier.LOCAL_COMPACT: {"num_ctx": 32_000, "model": "qwen3:14b"},
    ModelTier.LOCAL_STANDARD: {"num_ctx": 48_000, "model": "qwen3-coder-tool:30b"},
    ModelTier.CLOUD_REASONING: {"num_ctx": 128_000, "model": "anthropic/claude-sonnet"},
    ModelTier.CLOUD_CODING: {"num_ctx": 128_000, "model": "openai/gpt-4o"},
    ModelTier.CLOUD_LARGE_CONTEXT: {"num_ctx": 1_000_000, "model": "gemini-2.5-pro"},
    ModelTier.CLOUD_OPENROUTER: {"num_ctx": 200_000, "model": "openrouter/auto"},
}


TOKEN_TIMEOUT_SECONDS = 120.0
"""Max wait between streamed chunks before we abort. Router itself has no
read timeout — it waits for the GPU / cloud as long as needed. Orchestrator
gives up only when no chunk arrived within this window."""


class TokenTimeoutError(RuntimeError):
    """Raised when no chunk arrives within TOKEN_TIMEOUT_SECONDS."""


# ── Response shape compatible with former LiteLLM callers ────────────────

@dataclass
class _FunctionCall:
    name: str = ""
    arguments: str = ""


@dataclass
class _ToolCall:
    id: str = ""
    type: str = "function"
    function: _FunctionCall = field(default_factory=_FunctionCall)


@dataclass
class _Message:
    role: str = "assistant"
    content: str | None = ""
    tool_calls: list[_ToolCall] | None = None
    thinking: str | None = None


@dataclass
class _Choice:
    index: int = 0
    message: _Message = field(default_factory=_Message)
    finish_reason: str = "stop"


@dataclass
class _Usage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0


@dataclass
class CompletionResponse:
    """Compatibility surface for former `litellm.completion()` responses.
    Callers access `.choices[0].message.content`, `.choices[0].message.tool_calls`,
    and optionally `.usage.completion_tokens`."""
    choices: list[_Choice] = field(default_factory=lambda: [_Choice()])
    usage: _Usage = field(default_factory=_Usage)
    model: str = ""


# ── Router configuration ─────────────────────────────────────────────────

def _router_base() -> str:
    return settings.ollama_url.rstrip("/").replace("/v1", "").replace("/api", "")


def _build_headers(
    *,
    capability: str,
    deadline_iso: str | None,
    priority: str,
    client_id: str | None,
    min_model_size: int,
    max_tier: str | None,
    extra: dict[str, str] | None = None,
) -> dict[str, str]:
    headers: dict[str, str] = {
        "Content-Type": "application/json",
        "X-Capability": capability,
        "X-Priority": priority or "NORMAL",
    }
    if deadline_iso:
        headers["X-Deadline-Iso"] = deadline_iso
    if client_id:
        headers["X-Client-Id"] = client_id
    if min_model_size > 0:
        headers["X-Min-Model-Size"] = str(min_model_size)
    if max_tier and max_tier != "NONE":
        headers["X-Max-Tier"] = max_tier
    if extra:
        headers.update(extra)
    return headers


# ── Gemini usage counter (daily cap, kept locally) ───────────────────────
# Router does not track per-provider daily quotas. This is a tiny counter so
# we can warn the user when the free-tier Gemini limit is close.

_gemini_daily_count = 0
_gemini_day = ""


def _gemini_day_key() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def check_gemini_available() -> bool:
    global _gemini_daily_count, _gemini_day
    today = _gemini_day_key()
    if today != _gemini_day:
        _gemini_day = today
        _gemini_daily_count = 0
    limit = getattr(settings, "gemini_daily_limit", 250)
    return _gemini_daily_count < limit


def increment_gemini_counter() -> None:
    global _gemini_daily_count, _gemini_day
    today = _gemini_day_key()
    if today != _gemini_day:
        _gemini_day = today
        _gemini_daily_count = 0
    _gemini_daily_count += 1


# ── Main entry point ─────────────────────────────────────────────────────

class LlmProvider:
    """Stateless wrapper that forwards every call to the router."""

    async def completion(
        self,
        messages: list[dict],
        *,
        capability: str = "chat",
        deadline_iso: str | None = None,
        priority: str = "NORMAL",
        client_id: str | None = None,
        max_tier: str = "NONE",
        min_model_size: int = 0,
        tools: list[dict] | None = None,
        tool_choice: str | None = None,
        temperature: float = 0.1,
        max_tokens: int = 8192,
        extra_headers: dict[str, str] | None = None,
        tier: ModelTier | None = None,             # Backward-compat: ignored.
        model_override: str | None = None,         # Backward-compat: ignored.
        api_base_override: str | None = None,      # Backward-compat: ignored.
        api_key_override: str | None = None,       # Backward-compat: ignored.
    ) -> CompletionResponse:
        """Send an LLM chat request through the router.

        Args:
            messages: OpenAI-style chat messages.
            capability: chat | thinking | coding | extraction | embedding | visual.
            deadline_iso: absolute ISO-8601 deadline (or None for BATCH).
            priority: CASCADE | CRITICAL | NORMAL.
            client_id: resolves tier from CloudModelPolicy server-side.
            max_tier: explicit tier override.
            min_model_size: minimum local model size in billions.
            tools: OpenAI tool schema, passed through to /api/chat.
            tool_choice: ignored by Ollama chat today, reserved.
            temperature / max_tokens: Ollama `options`.
            extra_headers: additional HTTP headers (ignored for now).
            tier: legacy ModelTier — ignored, the router decides.
        """
        url = f"{_router_base()}/api/chat"
        headers = _build_headers(
            capability=capability,
            deadline_iso=deadline_iso,
            priority=priority,
            client_id=client_id,
            min_model_size=min_model_size,
            max_tier=max_tier,
            extra=extra_headers,
        )
        body: dict[str, Any] = {
            "messages": messages,
            "stream": True,
            "options": {
                "temperature": temperature,
                "num_predict": max_tokens,
            },
        }
        if tools:
            body["tools"] = tools

        return await _stream_and_assemble(url, headers, body)


async def _stream_and_assemble(
    url: str, headers: dict[str, str], body: dict,
) -> CompletionResponse:
    """Stream NDJSON chunks from the router, assemble a LiteLLM-shape response."""
    content_parts: list[str] = []
    thinking_parts: list[str] = []
    tool_calls: list[_ToolCall] = []
    model_name = ""
    prompt_tokens = 0
    completion_tokens = 0
    finish_reason = "stop"
    last_chunk_time = time.monotonic()

    timeout = httpx.Timeout(connect=10, read=None, write=10, pool=30)
    async with httpx.AsyncClient(timeout=timeout) as client:
        async with client.stream("POST", url, headers=headers, json=body) as response:
            if response.status_code != 200:
                text = (await response.aread()).decode("utf-8", errors="replace")
                raise RuntimeError(
                    f"Router /api/chat returned {response.status_code}: {text[:500]}"
                )
            async for line in response.aiter_lines():
                if not line.strip():
                    continue
                now = time.monotonic()
                if now - last_chunk_time > TOKEN_TIMEOUT_SECONDS:
                    raise TokenTimeoutError(
                        f"No chunk from router for {TOKEN_TIMEOUT_SECONDS}s"
                    )
                last_chunk_time = now
                try:
                    chunk = json.loads(line)
                except json.JSONDecodeError:
                    logger.debug("LLM stream: non-JSON line: %s", line[:120])
                    continue

                if "error" in chunk:
                    raise RuntimeError(f"Router error: {chunk['error']}")

                if "model" in chunk and not model_name:
                    model_name = chunk["model"]

                msg = chunk.get("message")
                if isinstance(msg, dict):
                    c = msg.get("content")
                    if c:
                        content_parts.append(c)
                    t = msg.get("thinking")
                    if t:
                        thinking_parts.append(t)
                    tc = msg.get("tool_calls")
                    if isinstance(tc, list):
                        for raw in tc:
                            fn = raw.get("function") or {}
                            args = fn.get("arguments")
                            if isinstance(args, dict):
                                args = json.dumps(args, ensure_ascii=False)
                            tool_calls.append(
                                _ToolCall(
                                    id=raw.get("id", ""),
                                    type=raw.get("type", "function"),
                                    function=_FunctionCall(
                                        name=fn.get("name", ""),
                                        arguments=args or "",
                                    ),
                                )
                            )

                if chunk.get("done"):
                    prompt_tokens = chunk.get("prompt_eval_count", prompt_tokens)
                    completion_tokens = chunk.get("eval_count", completion_tokens)
                    finish_reason = chunk.get("done_reason") or finish_reason

    full_content = "".join(content_parts) if content_parts else None
    full_thinking = "".join(thinking_parts) if thinking_parts else None
    return CompletionResponse(
        choices=[
            _Choice(
                index=0,
                message=_Message(
                    role="assistant",
                    content=full_content,
                    tool_calls=tool_calls or None,
                    thinking=full_thinking,
                ),
                finish_reason=finish_reason,
            ),
        ],
        usage=_Usage(
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            total_tokens=prompt_tokens + completion_tokens,
        ),
        model=model_name,
    )


llm_provider = LlmProvider()


async def refresh_openrouter_api_key() -> None:
    """No-op shim — orchestrator no longer holds OpenRouter credentials.

    The router (/api/chat → proxy_to_openrouter) owns the API key and refreshes
    it from Kotlin server directly. This function is kept so the existing
    `app.main.lifespan` startup import still resolves; a follow-up cleanup can
    delete the call site.
    """
    return None
