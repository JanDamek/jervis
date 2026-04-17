"""Cached gRPC stubs for the correction service's reverse callbacks.

Only one callback surface is used today — ServerOrchestratorProgressService
for `emit_correction_progress`. Additional stubs can be added lazily as
other Kotlin-server contracts arrive.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.common import types_pb2
from jervis.server import orchestrator_progress_pb2_grpc

logger = logging.getLogger("correction.grpc_client")

_channel: Optional[grpc.aio.Channel] = None
_orchestrator_progress_stub: Optional[
    orchestrator_progress_pb2_grpc.ServerOrchestratorProgressServiceStub
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
        _channel = grpc.aio.insecure_channel(target)
        logger.debug("kotlin-server gRPC channel opened to %s", target)
    return _channel


def server_orchestrator_progress_stub() -> (
    orchestrator_progress_pb2_grpc.ServerOrchestratorProgressServiceStub
):
    global _orchestrator_progress_stub
    if _orchestrator_progress_stub is None:
        _orchestrator_progress_stub = (
            orchestrator_progress_pb2_grpc.ServerOrchestratorProgressServiceStub(
                _get_channel()
            )
        )
    return _orchestrator_progress_stub


def build_request_context() -> types_pb2.RequestContext:
    """Minimal RequestContext for correction → server callbacks."""
    return types_pb2.RequestContext(
        request_id="",
        trace={"caller": "service-correction"},
    )
