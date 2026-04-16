"""Cached gRPC stubs for the Kotlin server's meeting recording bridge."""

from __future__ import annotations

import logging
import os
from typing import Optional

import grpc.aio

from jervis.server import meeting_recording_bridge_pb2_grpc

logger = logging.getLogger(__name__)

_channel: Optional[grpc.aio.Channel] = None
_bridge_stub: Optional[
    meeting_recording_bridge_pb2_grpc.ServerMeetingRecordingBridgeServiceStub
] = None


def _kotlin_server_grpc_target() -> str:
    url = os.getenv("JERVIS_SERVER_URL", "http://jervis-server:5500").rstrip("/")
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


def server_meeting_recording_bridge_stub() -> meeting_recording_bridge_pb2_grpc.ServerMeetingRecordingBridgeServiceStub:
    global _bridge_stub
    if _bridge_stub is None:
        _bridge_stub = meeting_recording_bridge_pb2_grpc.ServerMeetingRecordingBridgeServiceStub(
            _get_channel()
        )
    return _bridge_stub
