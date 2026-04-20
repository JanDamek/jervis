"""Cached gRPC stubs used by the o365 browser pool.

Two channels:
  * `kotlin_server` — Kotlin app server (session / meeting / resources).
  * `ollama_router` — local LLM / VLM / embedding inference.

Both dial port 5501 (h2c). Channel instances are created lazily + cached
for the pod's lifetime.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.router import inference_pb2_grpc
from jervis.server import (
    meeting_attend_pb2_grpc,
    meeting_recording_bridge_pb2_grpc,
    o365_resources_pb2_grpc,
    o365_session_pb2_grpc,
)

logger = logging.getLogger(__name__)

# WebM video chunks are multi-MiB — bump the inbound/outbound caps to 64 MiB
# so a single chunk fits in one request (matches the Kotlin server's Netty
# channel configuration for ServerMeetingRecordingBridgeService).
_GRPC_MAX_MSG_BYTES = 64 * 1024 * 1024

_channel: Optional[grpc.aio.Channel] = None
_user_activity_stub: Optional[o365_resources_pb2_grpc.ServerUserActivityServiceStub] = None
_discovered_stub: Optional[o365_resources_pb2_grpc.ServerO365DiscoveredResourcesServiceStub] = None
_meeting_attend_stub: Optional[meeting_attend_pb2_grpc.ServerMeetingAttendServiceStub] = None
_o365_session_stub: Optional[o365_session_pb2_grpc.ServerO365SessionServiceStub] = None
_meeting_recording_stub: Optional[
    meeting_recording_bridge_pb2_grpc.ServerMeetingRecordingBridgeServiceStub
] = None


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
        _channel = grpc.aio.insecure_channel(
            target,
            options=[
                ("grpc.max_send_message_length", _GRPC_MAX_MSG_BYTES),
                ("grpc.max_receive_message_length", _GRPC_MAX_MSG_BYTES),
            ],
        )
        logger.debug("kotlin-server gRPC channel opened to %s", target)
    return _channel


def server_user_activity_stub() -> o365_resources_pb2_grpc.ServerUserActivityServiceStub:
    global _user_activity_stub
    if _user_activity_stub is None:
        _user_activity_stub = o365_resources_pb2_grpc.ServerUserActivityServiceStub(_get_channel())
    return _user_activity_stub


def server_discovered_resources_stub() -> o365_resources_pb2_grpc.ServerO365DiscoveredResourcesServiceStub:
    global _discovered_stub
    if _discovered_stub is None:
        _discovered_stub = o365_resources_pb2_grpc.ServerO365DiscoveredResourcesServiceStub(_get_channel())
    return _discovered_stub


def server_meeting_attend_stub() -> meeting_attend_pb2_grpc.ServerMeetingAttendServiceStub:
    global _meeting_attend_stub
    if _meeting_attend_stub is None:
        _meeting_attend_stub = meeting_attend_pb2_grpc.ServerMeetingAttendServiceStub(_get_channel())
    return _meeting_attend_stub


def server_o365_session_stub() -> o365_session_pb2_grpc.ServerO365SessionServiceStub:
    global _o365_session_stub
    if _o365_session_stub is None:
        _o365_session_stub = o365_session_pb2_grpc.ServerO365SessionServiceStub(_get_channel())
    return _o365_session_stub


def server_meeting_recording_stub() -> (
    meeting_recording_bridge_pb2_grpc.ServerMeetingRecordingBridgeServiceStub
):
    global _meeting_recording_stub
    if _meeting_recording_stub is None:
        _meeting_recording_stub = (
            meeting_recording_bridge_pb2_grpc.ServerMeetingRecordingBridgeServiceStub(
                _get_channel()
            )
        )
    return _meeting_recording_stub


# ── Ollama-router inference channel ──────────────────────────────────

_router_channel: Optional[grpc.aio.Channel] = None
_router_inference_stub: Optional[inference_pb2_grpc.RouterInferenceServiceStub] = None


def _router_grpc_target() -> str:
    """Strip scheme / port from OLLAMA_ROUTER_URL and target :5501 gRPC."""
    url = settings.ollama_router_url.rstrip("/")
    if "://" in url:
        url = url.split("://", 1)[1]
    host = url.split("/")[0].split(":")[0]
    return f"{host}:5501"


def _get_router_channel() -> grpc.aio.Channel:
    global _router_channel
    if _router_channel is None:
        target = _router_grpc_target()
        _router_channel = grpc.aio.insecure_channel(
            target,
            options=[
                ("grpc.max_send_message_length", _GRPC_MAX_MSG_BYTES),
                ("grpc.max_receive_message_length", _GRPC_MAX_MSG_BYTES),
                ("grpc.keepalive_time_ms", 30_000),
                ("grpc.keepalive_timeout_ms", 10_000),
                ("grpc.keepalive_permit_without_calls", 1),
            ],
        )
        logger.debug("ollama-router gRPC channel opened to %s", target)
    return _router_channel


def router_inference_stub() -> inference_pb2_grpc.RouterInferenceServiceStub:
    global _router_inference_stub
    if _router_inference_stub is None:
        _router_inference_stub = inference_pb2_grpc.RouterInferenceServiceStub(
            _get_router_channel(),
        )
    return _router_inference_stub
