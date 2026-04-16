"""Cached gRPC stubs for Kotlin server contracts used by MCP tools.

Mirrors `backend/service-orchestrator/app/grpc_server_client.py` — one
insecure_channel to `<kotlin-server>:5501`, stub factories are lazy so
nothing hits the wire at import time.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.server import (
    connection_pb2_grpc,
    git_pb2_grpc,
    meeting_alone_pb2_grpc,
)

logger = logging.getLogger(__name__)

_channel: Optional[grpc.aio.Channel] = None
_connection_stub: Optional[connection_pb2_grpc.ServerConnectionServiceStub] = None
_git_stub: Optional[git_pb2_grpc.ServerGitServiceStub] = None
_meeting_alone_stub: Optional[meeting_alone_pb2_grpc.ServerMeetingAloneServiceStub] = None


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


def server_connection_stub() -> connection_pb2_grpc.ServerConnectionServiceStub:
    global _connection_stub
    if _connection_stub is None:
        _connection_stub = connection_pb2_grpc.ServerConnectionServiceStub(_get_channel())
    return _connection_stub


def server_meeting_alone_stub() -> meeting_alone_pb2_grpc.ServerMeetingAloneServiceStub:
    global _meeting_alone_stub
    if _meeting_alone_stub is None:
        _meeting_alone_stub = meeting_alone_pb2_grpc.ServerMeetingAloneServiceStub(_get_channel())
    return _meeting_alone_stub


def server_git_stub() -> git_pb2_grpc.ServerGitServiceStub:
    global _git_stub
    if _git_stub is None:
        _git_stub = git_pb2_grpc.ServerGitServiceStub(_get_channel())
    return _git_stub
