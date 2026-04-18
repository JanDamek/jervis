"""Shared KB gRPC client helpers for Python pods.

Every Python service that talks to service-knowledgebase dials the same
target (KB write deployment on :5501). This helper centralises the
channel + stub construction so pods do not each reimplement it.

Resolution order for the KB host:
1. `KB_GRPC_HOST` env var — explicit override.
2. `KNOWLEDGEBASE_WRITE_URL` env var — existing configmap key, parsed.
3. `KNOWLEDGEBASE_URL` env var — fallback for read-only services.
4. Literal `jervis-knowledgebase-write` — matches cluster service name.

Port resolution: `KB_GRPC_PORT` env var, default `5501`.
"""

from __future__ import annotations

import logging
import os
from typing import Optional

import grpc.aio

from jervis.common import types_pb2
from jervis.knowledgebase import graph_pb2_grpc
from jervis.knowledgebase import ingest_pb2_grpc
from jervis.knowledgebase import maintenance_pb2_grpc
from jervis.knowledgebase import queue_pb2_grpc
from jervis.knowledgebase import retrieve_pb2_grpc

logger = logging.getLogger("jervis_contracts.kb_client")


_channel: Optional[grpc.aio.Channel] = None
_ingest_stub: Optional[ingest_pb2_grpc.KnowledgeIngestServiceStub] = None
_maintenance_stub: Optional[maintenance_pb2_grpc.KnowledgeMaintenanceServiceStub] = None
_queue_stub: Optional[queue_pb2_grpc.KnowledgeQueueServiceStub] = None
_graph_stub: Optional[graph_pb2_grpc.KnowledgeGraphServiceStub] = None
_retrieve_stub: Optional[retrieve_pb2_grpc.KnowledgeRetrieveServiceStub] = None


def _kb_host() -> str:
    host = os.environ.get("KB_GRPC_HOST")
    if host:
        return host
    for key in ("KNOWLEDGEBASE_WRITE_URL", "KNOWLEDGEBASE_URL"):
        url = os.environ.get(key, "")
        if url:
            # Strip scheme + path to get host.
            if "://" in url:
                url = url.split("://", 1)[1]
            return url.split("/")[0].split(":")[0]
    return "jervis-knowledgebase-write"


def _kb_port() -> int:
    try:
        return int(os.environ.get("KB_GRPC_PORT", "5501"))
    except ValueError:
        return 5501


def _target() -> str:
    return f"{_kb_host()}:{_kb_port()}"


def get_channel() -> grpc.aio.Channel:
    global _channel
    if _channel is None:
        target = _target()
        _channel = grpc.aio.insecure_channel(target)
        logger.debug("kb gRPC channel opened → %s", target)
    return _channel


def ingest_stub() -> ingest_pb2_grpc.KnowledgeIngestServiceStub:
    global _ingest_stub
    if _ingest_stub is None:
        _ingest_stub = ingest_pb2_grpc.KnowledgeIngestServiceStub(get_channel())
    return _ingest_stub


def maintenance_stub() -> maintenance_pb2_grpc.KnowledgeMaintenanceServiceStub:
    global _maintenance_stub
    if _maintenance_stub is None:
        _maintenance_stub = maintenance_pb2_grpc.KnowledgeMaintenanceServiceStub(get_channel())
    return _maintenance_stub


def queue_stub() -> queue_pb2_grpc.KnowledgeQueueServiceStub:
    global _queue_stub
    if _queue_stub is None:
        _queue_stub = queue_pb2_grpc.KnowledgeQueueServiceStub(get_channel())
    return _queue_stub


def graph_stub() -> graph_pb2_grpc.KnowledgeGraphServiceStub:
    global _graph_stub
    if _graph_stub is None:
        _graph_stub = graph_pb2_grpc.KnowledgeGraphServiceStub(get_channel())
    return _graph_stub


def retrieve_stub() -> retrieve_pb2_grpc.KnowledgeRetrieveServiceStub:
    global _retrieve_stub
    if _retrieve_stub is None:
        _retrieve_stub = retrieve_pb2_grpc.KnowledgeRetrieveServiceStub(get_channel())
    return _retrieve_stub


def build_request_context(caller: str, client_id: str = "") -> types_pb2.RequestContext:
    """Minimal RequestContext for pod → KB calls.

    Uses a Scope with client_id when provided (KB write-side RPCs rely on
    it for tenant scoping); trace.caller records which service initiated
    the call for log correlation.
    """
    ctx = types_pb2.RequestContext(
        request_id="",
        trace={"caller": caller},
    )
    if client_id:
        ctx.scope.client_id = client_id
    return ctx


async def retrieve(
    *,
    caller: str,
    query: str,
    client_id: str = "",
    project_id: str = "",
    group_id: str = "",
    max_results: int = 5,
    min_confidence: float = 0.0,
    expand_graph: bool = True,
    kinds: Optional[list[str]] = None,
    timeout: float = 30.0,
    simple: bool = False,
) -> list[dict]:
    """Dial KnowledgeRetrieveService.{Retrieve,RetrieveSimple} and return
    items as plain dicts matching the legacy REST JSON shape.

    Every call site used to handcraft a RetrievalRequest + httpx call +
    project `items` out of the response dict; this helper captures that
    boilerplate so the migration stays mechanical.
    """
    from jervis.knowledgebase import retrieve_pb2

    stub = retrieve_stub()
    req = retrieve_pb2.RetrievalRequest(
        ctx=build_request_context(caller=caller, client_id=client_id),
        query=query,
        client_id=client_id,
        project_id=project_id,
        group_id=group_id,
        max_results=max_results,
        min_confidence=min_confidence,
        expand_graph=expand_graph,
        kinds=list(kinds or []),
    )
    method = stub.RetrieveSimple if simple else stub.Retrieve
    pack = await method(req, timeout=timeout)
    return [
        {
            "content": it.content,
            "score": it.score,
            "sourceUrn": it.source_urn,
            "credibility": it.credibility,
            "branchScope": it.branch_scope,
            "metadata": dict(it.metadata),
        }
        for it in pack.items
    ]


async def close() -> None:
    """Shut down the cached channel. Safe to call multiple times."""
    global _channel, _ingest_stub, _maintenance_stub, _queue_stub, _graph_stub, _retrieve_stub
    if _channel is not None:
        try:
            await _channel.close()
        except Exception:
            pass
        _channel = None
        _ingest_stub = None
        _maintenance_stub = None
        _queue_stub = None
        _graph_stub = None
        _retrieve_stub = None
