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


def _ingest_request(
    *,
    caller: str,
    client_id: str,
    project_id: str,
    group_id: str,
    source_urn: str,
    kind: str,
    content: str,
    metadata: Optional[dict],
    observed_at_iso: str,
    max_tier: str,
    credibility: str,
    branch_scope: str,
    branch_role: str,
):
    from jervis.knowledgebase import ingest_pb2

    return ingest_pb2.IngestRequest(
        ctx=build_request_context(caller=caller, client_id=client_id),
        client_id=client_id,
        project_id=project_id,
        group_id=group_id,
        source_urn=source_urn,
        kind=kind,
        content=content,
        metadata={str(k): str(v) for k, v in (metadata or {}).items()},
        observed_at_iso=observed_at_iso,
        max_tier=max_tier,
        credibility=credibility,
        branch_scope=branch_scope,
        branch_role=branch_role,
    )


def _ingest_result_to_dict(result) -> dict:
    return {
        "status": result.status,
        "chunks_count": result.chunks_count,
        "nodes_created": result.nodes_created,
        "edges_created": result.edges_created,
        "chunk_ids": list(result.chunk_ids),
        "entity_keys": list(result.entity_keys),
    }


async def ingest(
    *,
    caller: str,
    source_urn: str,
    content: str,
    client_id: str = "",
    project_id: str = "",
    group_id: str = "",
    kind: str = "note",
    metadata: Optional[dict] = None,
    observed_at_iso: str = "",
    max_tier: str = "NONE",
    credibility: str = "",
    branch_scope: str = "",
    branch_role: str = "",
    immediate: bool = False,
    queue: bool = False,
    timeout: float = 60.0,
) -> dict:
    """Dial KnowledgeIngestService.{Ingest,IngestImmediate,IngestQueue}.

    - queue=True → fire-and-forget; returns {'status': 'accepted', ...}.
    - immediate=True → synchronous RAG + LLM extraction.
    - default → async LLM extraction (RAG synchronous, extraction queued).
    """
    stub = ingest_stub()
    req = _ingest_request(
        caller=caller,
        client_id=client_id,
        project_id=project_id,
        group_id=group_id,
        source_urn=source_urn,
        kind=kind,
        content=content,
        metadata=metadata,
        observed_at_iso=observed_at_iso,
        max_tier=max_tier,
        credibility=credibility,
        branch_scope=branch_scope,
        branch_role=branch_role,
    )
    if queue:
        ack = await stub.IngestQueue(req, timeout=timeout)
        return {"status": "accepted" if ack.ok else "error", "queue_id": ack.queue_id}
    if immediate:
        return _ingest_result_to_dict(await stub.IngestImmediate(req, timeout=timeout))
    return _ingest_result_to_dict(await stub.Ingest(req, timeout=timeout))


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


async def graph_search(
    *,
    caller: str,
    query: str = "",
    client_id: str = "",
    project_id: str = "",
    group_id: str = "",
    node_type: str = "",
    branch_name: str = "",
    max_results: int = 20,
    timeout: float = 15.0,
) -> list[dict]:
    """Dial KnowledgeGraphService.SearchNodes and return nodes as dicts
    mirroring the legacy REST GraphNode shape (id / key / label / properties).
    properties values arrive as strings — callers reparse JSON when needed.
    """
    from jervis.knowledgebase import graph_pb2

    stub = graph_stub()
    resp = await stub.SearchNodes(
        graph_pb2.SearchNodesRequest(
            ctx=build_request_context(caller=caller, client_id=client_id),
            query=query,
            client_id=client_id,
            project_id=project_id,
            group_id=group_id,
            max_results=max_results,
            node_type=node_type,
            branch_name=branch_name,
        ),
        timeout=timeout,
    )
    return [
        {
            "id": n.id,
            "key": n.key,
            "label": n.label,
            "properties": dict(n.properties),
        }
        for n in resp.nodes
    ]


async def get_graph_node(
    *,
    caller: str,
    node_key: str,
    client_id: str = "",
    project_id: str = "",
    group_id: str = "",
    timeout: float = 10.0,
) -> Optional[dict]:
    """Dial KnowledgeGraphService.GetNode. Returns None when not found
    (the proto reply is an empty GraphNode — we distinguish by empty `key`)."""
    from jervis.knowledgebase import graph_pb2

    stub = graph_stub()
    resp = await stub.GetNode(
        graph_pb2.GetNodeRequest(
            ctx=build_request_context(caller=caller, client_id=client_id),
            node_key=node_key,
            client_id=client_id,
            project_id=project_id,
            group_id=group_id,
        ),
        timeout=timeout,
    )
    if not resp.key:
        return None
    return {
        "id": resp.id,
        "key": resp.key,
        "label": resp.label,
        "properties": dict(resp.properties),
    }


async def get_node_evidence(
    *,
    caller: str,
    node_key: str,
    client_id: str = "",
    timeout: float = 15.0,
) -> list[dict]:
    """Dial KnowledgeGraphService.GetNodeEvidence — returns RAG chunks
    behind a graph node as dicts matching the retrieve() return shape."""
    from jervis.knowledgebase import graph_pb2

    stub = graph_stub()
    resp = await stub.GetNodeEvidence(
        graph_pb2.GetNodeRequest(
            ctx=build_request_context(caller=caller, client_id=client_id),
            node_key=node_key,
            client_id=client_id,
        ),
        timeout=timeout,
    )
    return [
        {
            "content": it.content,
            "score": it.score,
            "sourceUrn": it.source_urn,
            "metadata": dict(it.metadata),
        }
        for it in resp.items
    ]


async def thought_traverse(
    *,
    caller: str,
    query: str,
    client_id: str = "",
    project_id: str = "",
    group_id: str = "",
    max_results: int = 20,
    floor: float = 0.0,
    max_depth: int = 2,
    entry_top_k: int = 5,
    timeout: float = 15.0,
) -> dict:
    """Dial KnowledgeGraphService.ThoughtTraverse (spreading activation).
    Returns a dict with keys `thoughts`, `knowledge`, `activated_thought_ids`,
    `activated_edge_ids` so callers mirror the legacy REST JSON shape
    (the REST handler already projected into this same structure).
    """
    from jervis.knowledgebase import graph_pb2

    stub = graph_stub()
    resp = await stub.ThoughtTraverse(
        graph_pb2.ThoughtTraversalRequest(
            ctx=build_request_context(caller=caller, client_id=client_id),
            query=query,
            client_id=client_id,
            project_id=project_id,
            group_id=group_id,
            max_results=max_results,
            floor=floor,
            max_depth=max_depth,
            entry_top_k=entry_top_k,
        ),
        timeout=timeout,
    )

    def _entry(t: "graph_pb2.ThoughtEntry") -> dict:
        # Reconstruct the nested shape consumers expect (REST handler returned
        # {node: {...}, pathWeight, isEntryPoint} per item). Flat proto fields
        # are projected back into the nested dict so existing formatters stay
        # source-compatible.
        return {
            "node": {
                "_id": t.id,
                "id": t.id,
                "key": t.id,
                "label": t.label,
                "summary": t.summary,
                "description": t.description,
                "type": t.node_type,
                "activationScore": t.activation,
                "metadata": dict(t.metadata),
            },
            "pathWeight": t.path_weight,
            "isEntryPoint": t.is_entry_point,
        }

    return {
        "thoughts": [_entry(t) for t in resp.thoughts],
        "knowledge": [_entry(k) for k in resp.knowledge],
        "activated_thought_ids": list(resp.activated_thought_ids),
        "activated_edge_ids": list(resp.activated_edge_ids),
    }


async def thought_reinforce(
    *,
    caller: str,
    thought_keys: list[str],
    edge_keys: list[str],
    timeout: float = 10.0,
) -> bool:
    from jervis.knowledgebase import graph_pb2

    stub = graph_stub()
    resp = await stub.ThoughtReinforce(
        graph_pb2.ThoughtReinforceRequest(
            ctx=build_request_context(caller=caller),
            thought_keys=list(thought_keys),
            edge_keys=list(edge_keys),
        ),
        timeout=timeout,
    )
    return bool(resp.ok)


async def thought_create(
    *,
    caller: str,
    thoughts: list[dict],
    client_id: str = "",
    project_id: str = "",
    group_id: str = "",
    timeout: float = 30.0,
) -> tuple[bool, list[str]]:
    """Accepts legacy dict shape {label, summary, type, related_entities}."""
    from jervis.knowledgebase import graph_pb2

    seeds = [
        graph_pb2.ThoughtSeed(
            label=str(t.get("label", "") or ""),
            summary=str(t.get("summary", "") or ""),
            thought_type=str(t.get("type", "") or t.get("thought_type", "") or "topic"),
            related_entities=list(t.get("related_entities", []) or []),
        )
        for t in (thoughts or [])
    ]
    stub = graph_stub()
    resp = await stub.ThoughtCreate(
        graph_pb2.ThoughtCreateRequest(
            ctx=build_request_context(caller=caller, client_id=client_id),
            client_id=client_id,
            project_id=project_id,
            group_id=group_id,
            thoughts=seeds,
        ),
        timeout=timeout,
    )
    keys = [k for k in (resp.detail or "").split(",") if k]
    return bool(resp.ok), keys


async def list_chunks_by_kind(
    *,
    caller: str,
    kind: str,
    client_id: str = "",
    project_id: str = "",
    max_results: int = 50,
    timeout: float = 15.0,
) -> list[dict]:
    """Dial KnowledgeRetrieveService.ListChunksByKind."""
    from jervis.knowledgebase import retrieve_pb2

    stub = retrieve_stub()
    resp = await stub.ListChunksByKind(
        retrieve_pb2.ListByKindRequest(
            ctx=build_request_context(caller=caller, client_id=client_id),
            client_id=client_id,
            project_id=project_id,
            kind=kind,
            max_results=max_results,
        ),
        timeout=timeout,
    )
    return [
        {
            "id": it.id,
            "content": it.content,
            "sourceUrn": it.source_urn,
            "kind": it.kind,
            "metadata": dict(it.metadata),
        }
        for it in resp.items
    ]


async def joern_scan(
    *,
    caller: str,
    scan_type: str,
    client_id: str = "",
    project_id: str = "",
    workspace_path: str = "",
    timeout: float = 300.0,
) -> dict:
    """Dial KnowledgeRetrieveService.JoernScan. Returns
    {status, scan_type, output, warnings, exit_code} dict."""
    from jervis.knowledgebase import retrieve_pb2

    stub = retrieve_stub()
    resp = await stub.JoernScan(
        retrieve_pb2.JoernScanRequest(
            ctx=build_request_context(caller=caller, client_id=client_id),
            scan_type=scan_type,
            client_id=client_id,
            project_id=project_id,
            workspace_path=workspace_path,
        ),
        timeout=timeout,
    )
    return {
        "status": resp.status,
        "scan_type": resp.scan_type,
        "output": resp.output,
        "warnings": resp.warnings,
        "exit_code": resp.exit_code,
    }


async def crawl(
    *,
    caller: str,
    url: str,
    max_depth: int = 1,
    allow_external_domains: bool = False,
    client_id: str = "",
    project_id: str = "",
    group_id: str = "",
    timeout: float = 300.0,
) -> dict:
    """Dial KnowledgeIngestService.Crawl. Returns ingest-result dict."""
    from jervis.knowledgebase import ingest_pb2

    stub = ingest_stub()
    resp = await stub.Crawl(
        ingest_pb2.CrawlRequest(
            ctx=build_request_context(caller=caller, client_id=client_id),
            url=url,
            max_depth=max_depth,
            allow_external_domains=allow_external_domains,
            client_id=client_id,
            project_id=project_id,
            group_id=group_id,
        ),
        timeout=timeout,
    )
    return _ingest_result_to_dict(resp)


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
