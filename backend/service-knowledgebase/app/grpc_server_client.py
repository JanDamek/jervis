"""Cached gRPC stubs for Kotlin server contracts used by the KB service.

Today the only surface is ServerKbCallbacksService (reverse callbacks
for progress + completion). Other Kotlin-server contracts can be added
lazily here as they migrate.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.core.config import settings
from jervis.common import types_pb2
from jervis.server import kb_callbacks_pb2_grpc

logger = logging.getLogger("kb.grpc_client")

_channel: Optional[grpc.aio.Channel] = None
_kb_callbacks_stub: Optional[kb_callbacks_pb2_grpc.ServerKbCallbacksServiceStub] = None


def _kotlin_server_grpc_target() -> str:
    url = getattr(settings, "KOTLIN_SERVER_URL", "") or "http://jervis-server:5500"
    url = url.rstrip("/")
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


def server_kb_callbacks_stub() -> kb_callbacks_pb2_grpc.ServerKbCallbacksServiceStub:
    global _kb_callbacks_stub
    if _kb_callbacks_stub is None:
        _kb_callbacks_stub = kb_callbacks_pb2_grpc.ServerKbCallbacksServiceStub(_get_channel())
    return _kb_callbacks_stub


def build_request_context() -> types_pb2.RequestContext:
    """Minimal RequestContext for KB → server callbacks."""
    return types_pb2.RequestContext(
        request_id="",
        trace={"caller": "service-knowledgebase"},
    )
