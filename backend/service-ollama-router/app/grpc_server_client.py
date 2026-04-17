"""Cached gRPC stubs for the Kotlin server's reverse-callback surface.

The ollama-router dials the Kotlin server on :5501 to fetch OpenRouter
settings and push back periodic stats. The `RequestContext` payload
(no X-* headers) is built with a synthetic sender identifying the
router — same as other pods on the server-side fan-in.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings as app_settings
from jervis.common import types_pb2
from jervis.server import gpu_idle_pb2_grpc, openrouter_settings_pb2_grpc

logger = logging.getLogger("ollama-router.grpc_client")

_channel: Optional[grpc.aio.Channel] = None
_openrouter_stub: Optional[
    openrouter_settings_pb2_grpc.ServerOpenRouterSettingsServiceStub
] = None
_gpu_idle_stub: Optional[gpu_idle_pb2_grpc.ServerGpuIdleServiceStub] = None


def _kotlin_server_grpc_target() -> str:
    # Port 5501 is the Netty gRPC listener on the Kotlin server.
    url = app_settings.kotlin_server_url.rstrip("/")
    if "://" in url:
        url = url.split("://", 1)[1]
    host = url.split("/")[0].split(":")[0]
    return f"{host}:5501"


def _get_channel() -> grpc.aio.Channel:
    global _channel
    if _channel is None:
        target = _kotlin_server_grpc_target()
        _channel = grpc.aio.insecure_channel(target)
        logger.debug("kotlin-server gRPC channel opened to %s", target)
    return _channel


def server_openrouter_stub() -> (
    openrouter_settings_pb2_grpc.ServerOpenRouterSettingsServiceStub
):
    global _openrouter_stub
    if _openrouter_stub is None:
        _openrouter_stub = (
            openrouter_settings_pb2_grpc.ServerOpenRouterSettingsServiceStub(
                _get_channel()
            )
        )
    return _openrouter_stub


def server_gpu_idle_stub() -> gpu_idle_pb2_grpc.ServerGpuIdleServiceStub:
    global _gpu_idle_stub
    if _gpu_idle_stub is None:
        _gpu_idle_stub = gpu_idle_pb2_grpc.ServerGpuIdleServiceStub(_get_channel())
    return _gpu_idle_stub


def build_request_context() -> types_pb2.RequestContext:
    """Minimal RequestContext for router → server reverse calls.

    Correlation id blank → server interceptor fills it; trace carries a
    free-form caller tag so server logs can distinguish router callbacks
    from orchestrator / MCP traffic.
    """
    return types_pb2.RequestContext(
        request_id="",
        trace={"caller": "ollama-router"},
    )


async def close_channel() -> None:
    """Close the channel on shutdown — idempotent."""
    global _channel, _openrouter_stub
    if _channel is not None:
        await _channel.close()
        _channel = None
        _openrouter_stub = None
