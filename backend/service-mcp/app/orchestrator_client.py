"""gRPC client for the orchestrator (MCP service).

Same lazy-channel pattern as `grpc_clients.py`, but targets the
orchestrator's gRPC surface (:5501) rather than the Kotlin server.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.orchestrator import control_pb2_grpc

logger = logging.getLogger("mcp.orchestrator_client")

_channel: Optional[grpc.aio.Channel] = None
_control_stub: Optional[control_pb2_grpc.OrchestratorControlServiceStub] = None


def _orchestrator_grpc_target() -> str:
    url = (getattr(settings, "orchestrator_url", None) or "http://jervis-orchestrator:8090").rstrip("/")
    if "://" in url:
        url = url.split("://", 1)[1]
    host = url.split("/")[0].split(":")[0]
    return f"{host}:5501"


def _get_channel() -> grpc.aio.Channel:
    global _channel
    if _channel is None:
        target = _orchestrator_grpc_target()
        _channel = grpc.aio.insecure_channel(target)
        logger.debug("orchestrator gRPC channel opened to %s", target)
    return _channel


def orchestrator_control_stub() -> control_pb2_grpc.OrchestratorControlServiceStub:
    global _control_stub
    if _control_stub is None:
        _control_stub = control_pb2_grpc.OrchestratorControlServiceStub(_get_channel())
    return _control_stub
