"""VLM client — RouterInferenceService.Generate gRPC.

Sends screenshots to the VLM via `jervis-ollama-router:5501` gRPC. No
REST. The router picks local VLM vs cloud based on RequestContext
(capability=VISUAL, client tier, priority).
"""

from __future__ import annotations

import asyncio
import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.common import enums_pb2, types_pb2
from jervis.router import inference_pb2, inference_pb2_grpc
from jervis_contracts.interceptors import prepare_context

logger = logging.getLogger("whatsapp-browser.vlm")

_RETRY_DELAYS = [5, 10, 20]
_GRPC_MAX_MSG_BYTES = 32 * 1024 * 1024

_channel: Optional[grpc.aio.Channel] = None
_stub: Optional[inference_pb2_grpc.RouterInferenceServiceStub] = None


def _router_target() -> str:
    url = settings.ollama_router_url.rstrip("/")
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
    return _stub


_PRIORITY_TO_ENUM = {
    "FOREGROUND": enums_pb2.PRIORITY_FOREGROUND,
    "BACKGROUND": enums_pb2.PRIORITY_BACKGROUND,
}


async def analyze_screenshot(
    image_bytes: bytes,
    prompt: str,
    *,
    processing_mode: str,
    max_tier: str,
    client_id: str = "",
) -> str:
    """Send a screenshot to VLM and return the text analysis.

    Args:
        image_bytes: JPEG/PNG image content.
        prompt: VLM prompt.
        processing_mode: "FOREGROUND" or "BACKGROUND" — priority hint.
        max_tier: "NONE", "FREE", "PAID", or "PREMIUM" — passed through
            RequestContext for the router's tier-based routing.
        client_id: tenant — lets the router resolve cloud tier via the
            CloudModelPolicy cache. Empty string = router default.
    """
    stub = _get_stub()

    ctx = types_pb2.RequestContext(
        scope=types_pb2.Scope(client_id=client_id or ""),
        priority=_PRIORITY_TO_ENUM.get(processing_mode, enums_pb2.PRIORITY_BACKGROUND),
        capability=enums_pb2.CAPABILITY_VISUAL,
        intent="whatsapp-pod-vlm",
    )
    prepare_context(ctx)

    request = inference_pb2.GenerateRequest(
        ctx=ctx,
        model_hint="qwen3-vl-tool:latest",
        prompt=prompt,
        images=[image_bytes],
        options=inference_pb2.ChatOptions(temperature=0.0, num_predict=2048),
    )

    for attempt, delay in enumerate(_RETRY_DELAYS):
        try:
            response_parts: list[str] = []
            async for chunk in stub.Generate(request):
                if chunk.response_delta:
                    response_parts.append(chunk.response_delta)
            result = "".join(response_parts)
            logger.info("VLM: response_len=%d", len(result))
            return result
        except Exception as e:
            logger.warning(
                "VLM call attempt %d failed: %s: %s",
                attempt + 1, type(e).__name__, str(e) or repr(e),
            )
            if attempt < len(_RETRY_DELAYS) - 1:
                await asyncio.sleep(delay)

    raise RuntimeError("VLM call failed after all retries")
