"""LLM wrapper for KB service — thin pass-through to ollama-router.

Dials `jervis-ollama-router:5501` over gRPC (`RouterInferenceService`).
The router owns routing (local GPU vs cloud, model selection, queue
priority); KB passes `capability` via RequestContext and drains the
server-stream into a single string.

Legacy positional params (`max_tier`, `model`, `priority`,
`route_decision`) are accepted but ignored — the router resolves them
from client tier + request metadata.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Optional

import grpc.aio

from app.core.config import settings
from jervis.common import enums_pb2, types_pb2
from jervis.router import inference_pb2, inference_pb2_grpc
from jervis_contracts.interceptors import prepare_context

logger = logging.getLogger(__name__)

_GRPC_MAX_MSG_BYTES = 32 * 1024 * 1024

_channel: Optional[grpc.aio.Channel] = None
_stub: Optional[inference_pb2_grpc.RouterInferenceServiceStub] = None


def _router_target() -> str:
    url = settings.OLLAMA_INGEST_BASE_URL.rstrip("/")
    if "://" in url:
        url = url.split("://", 1)[1]
    host = url.split("/")[0].split(":")[0]
    return f"{host}:5501"


def _get_stub() -> inference_pb2_grpc.RouterInferenceServiceStub:
    global _channel, _stub
    if _stub is None:
        _channel = grpc.aio.insecure_channel(
            _router_target(),
            options=[
                ("grpc.max_send_message_length", _GRPC_MAX_MSG_BYTES),
                ("grpc.max_receive_message_length", _GRPC_MAX_MSG_BYTES),
                ("grpc.keepalive_time_ms", 30_000),
                ("grpc.keepalive_timeout_ms", 10_000),
                ("grpc.keepalive_permit_without_calls", 1),
            ],
        )
        _stub = inference_pb2_grpc.RouterInferenceServiceStub(_channel)
        logger.info("RouterInferenceService gRPC channel opened to %s", _router_target())
    return _stub


def _build_ctx(
    capability: int,
    intent: str,
    client_id: str | None,
) -> types_pb2.RequestContext:
    ctx = types_pb2.RequestContext(
        scope=types_pb2.Scope(client_id=client_id or ""),
        priority=enums_pb2.PRIORITY_BACKGROUND,
        capability=capability,
        intent=intent,
    )
    prepare_context(ctx)
    return ctx


async def llm_generate(
    prompt: str,
    max_tier: str = "NONE",           # ignored — router resolves from client tier
    model: str | None = None,         # ignored — router picks model
    num_ctx: int = 8192,
    priority: int | None = None,      # ignored — router manages queue priority
    temperature: float = 0,
    format_json: bool = True,
    client_id: str | None = None,
) -> str:
    """Background extraction call (KB indexing, graph build, summarisation).

    `format_json` is honored by adding an instruction to the prompt. The
    router's native Ollama/OpenRouter path doesn't carry the Ollama-only
    `format` flag through proto — we inline the requirement instead.
    """
    final_prompt = prompt
    if format_json:
        final_prompt = (
            prompt.rstrip() + "\n\nRespond with ONLY a single valid JSON object. "
            "No prose, no code fences."
        )

    request = inference_pb2.GenerateRequest(
        ctx=_build_ctx(
            enums_pb2.CAPABILITY_EXTRACTION, "kb-extract", client_id,
        ),
        prompt=final_prompt,
        options=inference_pb2.ChatOptions(
            temperature=temperature,
            num_ctx=num_ctx,
            num_predict=8192,
        ),
    )

    stub = _get_stub()
    max_retries = 3
    last_err: Exception | None = None
    for attempt in range(1 + max_retries):
        try:
            parts: list[str] = []
            async for chunk in stub.Generate(request):
                if chunk.response_delta:
                    parts.append(chunk.response_delta)
            return "".join(parts)
        except grpc.aio.AioRpcError as e:
            last_err = e
            if e.code() in (
                grpc.StatusCode.UNAVAILABLE,
                grpc.StatusCode.DEADLINE_EXCEEDED,
                grpc.StatusCode.RESOURCE_EXHAUSTED,
            ) and attempt < max_retries:
                wait = 5 * (attempt + 1)
                logger.warning(
                    "llm_generate: %s (attempt %d/%d), retrying in %ds",
                    e.code(), attempt + 1, max_retries + 1, wait,
                )
                await asyncio.sleep(wait)
                continue
            raise

    raise RuntimeError(f"llm_generate exhausted retries: {last_err}")


async def llm_generate_vision(
    image_bytes: bytes,
    prompt: str,
    max_tier: str = "NONE",
    priority: int | None = None,
    client_id: str | None = None,
) -> str:
    """VLM call for image understanding. Capability is `visual`."""
    request = inference_pb2.GenerateRequest(
        ctx=_build_ctx(
            enums_pb2.CAPABILITY_VISUAL, "kb-vlm", client_id,
        ),
        prompt=prompt,
        images=[image_bytes],
        options=inference_pb2.ChatOptions(temperature=0.0, num_predict=4096),
    )

    stub = _get_stub()
    last_err: Exception | None = None
    for attempt in range(3):
        try:
            parts: list[str] = []
            async for chunk in stub.Generate(request):
                if chunk.response_delta:
                    parts.append(chunk.response_delta)
            return "".join(parts)
        except grpc.aio.AioRpcError as e:
            last_err = e
            backoff = 5 * (2 ** attempt)
            logger.warning(
                "VLM call failed (attempt %d/3): %s — retrying in %ds",
                attempt + 1, e.code(), backoff,
            )
            await asyncio.sleep(backoff)
        except Exception as e:
            last_err = e
            backoff = 5 * (2 ** attempt)
            logger.warning(
                "VLM call failed (attempt %d/3): %s — retrying in %ds",
                attempt + 1, e, backoff,
            )
            await asyncio.sleep(backoff)

    raise RuntimeError(f"VLM failed after 3 attempts: {last_err}")
