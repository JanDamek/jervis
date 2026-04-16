"""Cached gRPC stubs for Kotlin server contracts used by the visual-capture pod."""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.server import visual_capture_pb2_grpc

logger = logging.getLogger(__name__)

_channel: Optional[grpc.aio.Channel] = None
_visual_capture_stub: Optional[visual_capture_pb2_grpc.ServerVisualCaptureServiceStub] = None


def _kotlin_server_grpc_target() -> str:
    url = settings.kotlin_server_url.rstrip("/")
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


def server_visual_capture_stub() -> visual_capture_pb2_grpc.ServerVisualCaptureServiceStub:
    global _visual_capture_stub
    if _visual_capture_stub is None:
        _visual_capture_stub = visual_capture_pb2_grpc.ServerVisualCaptureServiceStub(_get_channel())
    return _visual_capture_stub
