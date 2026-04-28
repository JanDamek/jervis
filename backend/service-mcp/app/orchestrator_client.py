"""gRPC client for the orchestrator (MCP service).

Same lazy-channel pattern as `grpc_clients.py`, but targets the
orchestrator's gRPC surface (:5501) rather than the Kotlin server.
"""

from __future__ import annotations

import logging
from typing import Optional

import grpc.aio

from app.config import settings
from jervis.orchestrator import control_pb2_grpc, proposal_pb2_grpc

logger = logging.getLogger("mcp.orchestrator_client")

_channel: Optional[grpc.aio.Channel] = None
_control_stub: Optional[control_pb2_grpc.OrchestratorControlServiceStub] = None
_proposal_stub: Optional[proposal_pb2_grpc.OrchestratorProposalServiceStub] = None


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


def orchestrator_proposal_stub() -> proposal_pb2_grpc.OrchestratorProposalServiceStub:
    """Cached stub for the Claude CLI proposal lifecycle.

    The MCP server calls this — never the Kotlin server directly — so
    the embed + dedup logic lives in one place (orchestrator).
    """
    global _proposal_stub
    if _proposal_stub is None:
        _proposal_stub = proposal_pb2_grpc.OrchestratorProposalServiceStub(_get_channel())
    return _proposal_stub
