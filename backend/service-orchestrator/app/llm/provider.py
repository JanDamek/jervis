"""LLM provider — thin wrapper over jervis-ollama-router gRPC.

No LiteLLM. No per-provider adapters. No orchestrator-side retry or
rate limiting. The router (service-ollama-router) owns routing, model
selection, cloud failover, rate limiting, tier resolution. Orchestrator
dials `RouterInferenceService.Chat` (server-stream) and assembles the
chunks into a LiteLLM-shape response so existing callers keep working
unchanged.

See KB agent://claude-code/task-routing-unified-design and
agent://claude-code/orchestrator-llm-unification-proposal.
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Optional

import grpc.aio

from app.config import settings
from app.models import ModelTier
from jervis.common import enums_pb2, types_pb2
from jervis.router import inference_pb2, inference_pb2_grpc
from jervis_contracts.interceptors import prepare_context

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


# ── Router gRPC channel ──────────────────────────────────────────────────

_GRPC_MAX_MSG_BYTES = 32 * 1024 * 1024
_router_channel: Optional[grpc.aio.Channel] = None
_router_stub: Optional[inference_pb2_grpc.RouterInferenceServiceStub] = None


def _router_target() -> str:
    url = str(getattr(settings, "ollama_url", "")).rstrip("/")
    if "://" in url:
        url = url.split("://", 1)[1]
    host = url.split("/")[0].split(":")[0] if url else "jervis-ollama-router"
    return f"{host}:5501"


def _get_router_stub() -> inference_pb2_grpc.RouterInferenceServiceStub:
    global _router_channel, _router_stub
    if _router_stub is None:
        _router_channel = grpc.aio.insecure_channel(
            _router_target(),
            options=[
                ("grpc.max_send_message_length", _GRPC_MAX_MSG_BYTES),
                ("grpc.max_receive_message_length", _GRPC_MAX_MSG_BYTES),
                ("grpc.keepalive_time_ms", 30_000),
                ("grpc.keepalive_timeout_ms", 10_000),
                ("grpc.keepalive_permit_without_calls", 1),
            ],
        )
        _router_stub = inference_pb2_grpc.RouterInferenceServiceStub(_router_channel)
        logger.info("RouterInferenceService gRPC channel opened to %s", _router_target())
    return _router_stub


_CAPABILITY_TO_ENUM = {
    "chat": enums_pb2.CAPABILITY_CHAT,
    "thinking": enums_pb2.CAPABILITY_THINKING,
    "coding": enums_pb2.CAPABILITY_CODING,
    "extraction": enums_pb2.CAPABILITY_EXTRACTION,
    "embedding": enums_pb2.CAPABILITY_EMBEDDING,
    "visual": enums_pb2.CAPABILITY_VISUAL,
}


# ── Gemini usage counter (daily cap, kept locally) ───────────────────────

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

def _messages_to_proto(messages: list[dict]) -> list[inference_pb2.ChatMessage]:
    out: list[inference_pb2.ChatMessage] = []
    for m in messages:
        role = m.get("role", "user")
        content = m.get("content", "") or ""
        msg = inference_pb2.ChatMessage(
            role=role,
            content=content if isinstance(content, str) else json.dumps(content, default=str),
            tool_call_id=m.get("tool_call_id", "") or "",
            name=m.get("name", "") or "",
        )
        tc_list = m.get("tool_calls")
        if isinstance(tc_list, list):
            from google.protobuf import struct_pb2
            from google.protobuf.json_format import ParseDict
            proto_tcs: list[inference_pb2.ToolCall] = []
            for tc in tc_list:
                fn = tc.get("function") or {}
                raw_args = fn.get("arguments")
                if isinstance(raw_args, str):
                    try:
                        args = json.loads(raw_args)
                    except Exception:
                        args = {}
                elif isinstance(raw_args, dict):
                    args = raw_args
                else:
                    args = {}
                args_struct = struct_pb2.Struct()
                if args:
                    ParseDict(args, args_struct)
                proto_tcs.append(
                    inference_pb2.ToolCall(
                        id=tc.get("id") or "",
                        name=fn.get("name", ""),
                        args=args_struct,
                    )
                )
            msg.tool_calls.extend(proto_tcs)
        out.append(msg)
    return out


def _tools_to_proto(tools: list[dict] | None) -> list[inference_pb2.Tool]:
    if not tools:
        return []
    from google.protobuf import struct_pb2
    from google.protobuf.json_format import ParseDict
    out: list[inference_pb2.Tool] = []
    for t in tools:
        fn = t.get("function") or {}
        params = fn.get("parameters") or {}
        params_struct = struct_pb2.Struct()
        if params:
            ParseDict(params, params_struct)
        out.append(inference_pb2.Tool(
            name=fn.get("name", ""),
            description=fn.get("description", ""),
            parameters=params_struct,
        ))
    return out


class LlmProvider:
    """Stateless wrapper that forwards every call to the router via gRPC."""

    async def completion(
        self,
        messages: list[dict],
        *,
        capability: str = "chat",
        client_id: str | None = None,
        tools: list[dict] | None = None,
        temperature: float = 0.1,
        max_tokens: int = 8192,
    ) -> CompletionResponse:
        """Send an LLM chat request through the router (gRPC streaming).

        Args:
            messages: OpenAI-style chat messages.
            capability: chat | thinking | coding | extraction | embedding | visual.
            client_id: resolves tier from CloudModelPolicy on the router side.
            tools: OpenAI tool schema.
            temperature / max_tokens: mapped to ChatOptions.
        """
        ctx = types_pb2.RequestContext(
            scope=types_pb2.Scope(client_id=client_id or ""),
            priority=enums_pb2.PRIORITY_FOREGROUND,
            capability=_CAPABILITY_TO_ENUM.get(capability.lower(), enums_pb2.CAPABILITY_CHAT),
            intent=capability,
        )
        prepare_context(ctx)

        request = inference_pb2.ChatRequest(
            ctx=ctx,
            messages=_messages_to_proto(messages),
            tools=_tools_to_proto(tools),
            options=inference_pb2.ChatOptions(
                temperature=temperature,
                num_predict=max_tokens,
            ),
        )

        return await _drain_chat(request)


async def _drain_chat(request: inference_pb2.ChatRequest) -> CompletionResponse:
    """Stream RouterInferenceService.Chat and assemble a LiteLLM-shape response."""
    content_parts: list[str] = []
    thinking_parts: list[str] = []
    tool_call_slots: list[dict] = []
    id_to_slot: dict[str, int] = {}
    model_name = ""
    prompt_tokens = 0
    completion_tokens = 0
    finish_reason = "stop"
    last_chunk_time = time.monotonic()

    stub = _get_router_stub()
    async for chunk in stub.Chat(request):
        now = time.monotonic()
        if now - last_chunk_time > TOKEN_TIMEOUT_SECONDS:
            raise TokenTimeoutError(
                f"No chunk from router for {TOKEN_TIMEOUT_SECONDS}s"
            )
        last_chunk_time = now

        if chunk.model_used and not model_name:
            model_name = chunk.model_used
        if chunk.content_delta:
            content_parts.append(chunk.content_delta)
        if chunk.thinking_delta:
            thinking_parts.append(chunk.thinking_delta)

        for idx_in_chunk, tc in enumerate(chunk.tool_calls):
            # OpenAI-style deltas: id+name in one chunk, args in the next.
            if tc.id and tc.id in id_to_slot:
                slot = tool_call_slots[id_to_slot[tc.id]]
            elif tc.id:
                slot = {"id": tc.id, "name": "", "args": {}}
                id_to_slot[tc.id] = len(tool_call_slots)
                tool_call_slots.append(slot)
            elif idx_in_chunk < len(tool_call_slots):
                slot = tool_call_slots[idx_in_chunk]
            else:
                slot = {"id": "", "name": "", "args": {}}
                tool_call_slots.append(slot)
            if tc.name:
                slot["name"] = tc.name
            if tc.args and tc.args.fields:
                from google.protobuf.json_format import MessageToDict
                args_delta = MessageToDict(tc.args, preserving_proto_field_name=True)
                slot["args"].update(args_delta)

        if chunk.done:
            prompt_tokens = int(chunk.prompt_tokens) or prompt_tokens
            completion_tokens = int(chunk.completion_tokens) or completion_tokens
            finish_reason = chunk.finish_reason or finish_reason

    tool_calls: list[_ToolCall] = []
    for slot in tool_call_slots:
        if not slot["name"]:
            continue
        tool_calls.append(
            _ToolCall(
                id=slot["id"] or "",
                type="function",
                function=_FunctionCall(
                    name=slot["name"],
                    arguments=json.dumps(slot["args"], ensure_ascii=False),
                ),
            )
        )

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

    The router owns the API key and refreshes it from Kotlin server
    directly. This function is kept so the existing `app.main.lifespan`
    startup import still resolves; a follow-up cleanup can delete the
    call site.
    """
    return None
