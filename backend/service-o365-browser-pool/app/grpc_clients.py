"""Cached gRPC stubs for Kotlin server contracts used by the o365 browser pool."""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.server import meeting_attend_pb2_grpc, o365_resources_pb2_grpc

logger = logging.getLogger(__name__)

_channel: Optional[grpc.aio.Channel] = None
_user_activity_stub: Optional[o365_resources_pb2_grpc.ServerUserActivityServiceStub] = None
_discovered_stub: Optional[o365_resources_pb2_grpc.ServerO365DiscoveredResourcesServiceStub] = None
_meeting_attend_stub: Optional[meeting_attend_pb2_grpc.ServerMeetingAttendServiceStub] = None


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
