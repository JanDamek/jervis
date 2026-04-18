"""Jervis MCP Server – main entry point.

Exposes Jervis platform capabilities (KB, MongoDB, Orchestrator) as MCP tools
over Streamable HTTP transport with Bearer token authentication.

Designed to run:
  1. Inside K8s cluster (internal access for Claude Code agents)
  2. Behind public ingress at jervis-mcp.damek-soft.eu (Claude Desktop / cloud)

Priority: CRITICAL (same as orchestrator) – KB queries must not wait in queue.

Uses FastMCP for clean Streamable HTTP transport + BearerTokenAuth.
Supports both legacy Bearer tokens and OAuth 2.1 tokens (for Claude.ai / iOS).
"""

from __future__ import annotations

import logging
import os

from fastmcp import FastMCP
from fastmcp.server.auth import StaticTokenVerifier, TokenVerifier, AccessToken
from starlette.requests import Request
from starlette.responses import JSONResponse

from app.config import settings
from app.db import close_db, get_db
from app.oauth_provider import validate_oauth_token

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
)
logger = logging.getLogger("jervis-mcp")


# ── Auth setup (dual-mode: static tokens + OAuth) ────────────────────────


class HybridTokenVerifier(TokenVerifier):
    """Accepts internal (no-auth), static Bearer tokens, and OAuth tokens.

    Internal K8s access: no Authorization header → allowed (internal client).
    External access: valid Bearer token or OAuth token required.
    """

    def __init__(self, static_tokens: dict[str, dict] | None = None) -> None:
        super().__init__()
        self._static = (
            StaticTokenVerifier(tokens=static_tokens) if static_tokens else None
        )

    async def verify_token(self, token: str) -> AccessToken | None:
        # 0. No token → internal K8s access (service-to-service, no auth needed)
        if not token:
            return AccessToken(
                token="internal",
                client_id="k8s-internal",
                scopes=[],
            )

        # 1. Try static token
        if self._static:
            result = await self._static.verify_token(token)
            if result:
                return result

        # 2. Try OAuth-issued token
        oauth_data = validate_oauth_token(token)
        if oauth_data:
            return AccessToken(
                token=token,
                client_id=oauth_data.get("client_id", "oauth"),
                scopes=[],
                expires_at=oauth_data.get("expires_at"),
            )

        return None


tokens = list(settings.valid_tokens)
static_map = {t: {"client_id": "jervis", "scopes": []} for t in tokens} if tokens else None
auth = HybridTokenVerifier(static_tokens=static_map)
if tokens:
    logger.info("Bearer token auth enabled (%d static tokens + OAuth)", len(tokens))
else:
    logger.info("Bearer token auth enabled (OAuth only, no static tokens)")

# ── MCP Server ───────────────────────────────────────────────────────────

mcp = FastMCP(
    "jervis-mcp",
    auth=auth,
)

# ── KB Tools ─────────────────────────────────────────────────────────────

import httpx
import json


@mcp.tool
async def kb_search(
    query: str,
    client_id: str = "",
    project_id: str = "",
    group_id: str = "",
    scope: str = "auto",
    max_results: int = 10,
    min_confidence: float = 0.6,
) -> str:
    """Search the Knowledge Base using RAG + knowledge graph expansion.

    Combines vector search with graph traversal for comprehensive results.
    When group_id is provided, returns results from all projects in the group.

    Args:
        query: Natural language search query
        client_id: Client ID (leave empty to use server default)
        project_id: Project ID (leave empty to use server default)
        group_id: Group ID for cross-project visibility (leave empty for single project)
        scope: "auto" (client+project), "global", "client" (no project filter)
        max_results: Maximum number of results (default 10)
        min_confidence: Minimum confidence threshold (0.0-1.0)
    """
    cid = client_id or settings.default_client_id
    pid = project_id or settings.default_project_id or None
    if scope == "global":
        cid, pid = "", None
    elif scope == "client":
        pid = None

    from jervis_contracts import kb_client

    items = await kb_client.retrieve(
        caller="service-mcp.kb_search",
        query=query,
        client_id=cid,
        project_id=pid or "",
        group_id=group_id or "",
        max_results=max_results,
        min_confidence=min_confidence,
        expand_graph=True,
        timeout=120.0,
    )
    results = []
    for item in items:
        conf = item.get("score", 0)
        source = item.get("sourceUrn", "?")
        content = (item.get("content", "") or "")[:500]
        results.append(f"[{conf:.2f}] {source}: {content}")
    return "\n---\n".join(results) if results else "No results found."


@mcp.tool
async def kb_search_simple(
    query: str,
    client_id: str = "",
    project_id: str = "",
    group_id: str = "",
    max_results: int = 5,
) -> str:
    """Quick RAG-only search without graph expansion. Faster but less comprehensive.

    Args:
        query: Natural language search query
        client_id: Client ID (leave empty for default)
        project_id: Project ID (leave empty for default)
        group_id: Group ID for cross-project visibility (leave empty for single project)
        max_results: Maximum results
    """
    cid = client_id or settings.default_client_id
    pid = project_id or settings.default_project_id or None

    from jervis_contracts import kb_client

    items = await kb_client.retrieve(
        caller="service-mcp.kb_search_simple",
        query=query,
        client_id=cid,
        project_id=pid or "",
        group_id=group_id or "",
        max_results=max_results,
        simple=True,
        timeout=120.0,
    )
    results = []
    for item in items:
        source = item.get("sourceUrn", "?")
        content = (item.get("content", "") or "")[:500]
        results.append(f"{source}: {content}")
    return "\n---\n".join(results) if results else "No results found."


@mcp.tool
async def kb_traverse(
    start_node: str,
    client_id: str = "",
    group_id: str = "",
    direction: str = "outbound",
    max_hops: int = 2,
) -> str:
    """Traverse the knowledge graph starting from a node.

    Args:
        start_node: Node key or label to start traversal from
        client_id: Client ID (leave empty for default)
        group_id: Group ID for cross-project visibility (leave empty for single-project)
        direction: "outbound", "inbound", or "any"
        max_hops: Maximum traversal depth (1-3 recommended)
    """
    cid = client_id or settings.default_client_id
    headers = {"X-Ollama-Priority": "0"}
    payload = {
        "startNodeKey": start_node,
        "direction": direction,
        "maxDepth": max_hops,
        "clientId": cid,
    }
    if group_id:
        payload["groupId"] = group_id
    async with httpx.AsyncClient(timeout=120, headers=headers) as client:
        resp = await client.post(
            f"{settings.knowledgebase_url}/api/v1/traverse",
            json=payload,
        )
        resp.raise_for_status()
        nodes = resp.json()
        if not nodes:
            return f"No graph nodes found for '{start_node}'."
        lines = []
        for node in nodes:
            node_type = node.get("type", "?")
            label = node.get("label", "?")
            key = node.get("key", "?")
            lines.append(f"[{node_type}] {label} (key={key})")
            if node.get("properties"):
                for k, v in node["properties"].items():
                    lines.append(f"  {k}: {v}")
        return "\n".join(lines)


@mcp.tool
async def kb_graph_search(
    query: str,
    client_id: str = "",
    group_id: str = "",
    node_type: str = "",
    limit: int = 20,
) -> str:
    """Search for nodes in the knowledge graph by label.

    Args:
        query: Search query for node labels
        client_id: Client ID (leave empty for default)
        group_id: Group ID for cross-project visibility (leave empty for single-project)
        node_type: Filter by node type (e.g., "jira_issue", "file", "class", "method", "commit")
        limit: Maximum number of results
    """
    cid = client_id or settings.default_client_id
    params: dict = {"query": query, "clientId": cid, "limit": limit}
    if node_type:
        params["nodeType"] = node_type
    if group_id:
        params["groupId"] = group_id
    headers = {"X-Ollama-Priority": "0"}
    async with httpx.AsyncClient(timeout=120, headers=headers) as client:
        resp = await client.get(f"{settings.knowledgebase_url}/api/v1/graph/search", params=params)
        resp.raise_for_status()
        nodes = resp.json()
        if not nodes:
            return f"No graph nodes matching '{query}'."
        return "\n".join(
            f"[{n.get('type', '?')}] {n.get('label', '?')} (key={n.get('key', '?')})"
            for n in nodes
        )


@mcp.tool
async def kb_get_evidence(node_key: str, client_id: str = "") -> str:
    """Get RAG chunks that support a specific knowledge graph node.

    Args:
        node_key: The key of the graph node to get evidence for
        client_id: Client ID (leave empty for default)
    """
    cid = client_id or settings.default_client_id
    async with httpx.AsyncClient(timeout=120) as client:
        resp = await client.get(
            f"{settings.knowledgebase_url}/api/v1/graph/node/{node_key}/evidence",
            params={"clientId": cid},
        )
        resp.raise_for_status()
        data = resp.json()
        chunks = data.get("chunks", [])
        if not chunks:
            return f"No evidence found for node '{node_key}'."
        return "\n---\n".join(
            f"{c.get('sourceUrn', '?')}: {c.get('content', '')[:500]}"
            for c in chunks
        )


@mcp.tool
async def kb_resolve_alias(alias: str, client_id: str = "") -> str:
    """Resolve an entity alias to its canonical key.

    Use this when you encounter different names for possibly the same entity.
    Example: 'UserSvc' -> 'UserService', 'auth module' -> 'authentication-service'

    Args:
        alias: The alias or alternate name to resolve
        client_id: Client ID (leave empty for default)
    """
    from jervis.knowledgebase import graph_pb2
    from jervis_contracts import kb_client
    import grpc

    cid = client_id or settings.default_client_id
    stub = kb_client.graph_stub()
    try:
        result = await stub.ResolveAlias(
            graph_pb2.ResolveAliasRequest(
                ctx=kb_client.build_request_context(caller="service-mcp", client_id=cid),
                alias=alias,
                client_id=cid,
            ),
            timeout=120.0,
        )
    except grpc.aio.AioRpcError as e:
        return f"Error resolving alias ({e.code().name}): {e.details() or ''}"

    canonical = result.canonical_key or alias
    return f"'{alias}' -> canonical: '{canonical}'"


@mcp.tool
async def kb_store(
    content: str,
    client_id: str = "",
    project_id: str = "",
    group_id: str = "",
    kind: str = "finding",
    source_urn: str = "agent://claude-mcp",
    metadata: str = "{}",
) -> str:
    """Store new knowledge in the Knowledge Base.

    Use for findings, decisions, patterns, bugs, or conventions discovered during work.

    Scoping rules:
    - client_id + project_id → project-scoped (visible only to that project)
    - client_id only → client-scoped (visible to all projects of that client)
    - "GLOBAL" as client_id → global data (visible everywhere, no client/project)
    - empty client_id → uses server default (MCP_DEFAULT_CLIENT_ID)

    Args:
        content: The knowledge content to store
        client_id: Client ID. Use "GLOBAL" for global data visible to all clients.
        project_id: Project ID (leave empty for default)
        group_id: Group ID for cross-project visibility (leave empty for single-project)
        kind: Type: "finding", "decision", "pattern", "bug", "convention"
        source_urn: Source identifier
        metadata: Additional metadata as JSON string
    """
    # Handle GLOBAL keyword — explicit global storage (empty clientId in KB)
    if client_id.upper() == "GLOBAL":
        cid = ""
        pid = None  # Global data cannot have projectId
    else:
        cid = client_id or settings.default_client_id
        pid = project_id or settings.default_project_id or None

    # Validate: projectId requires clientId
    if pid and not cid:
        return (
            "Error: project_id was provided but client_id is empty and no default is configured. "
            "Knowledge scoped to a project MUST have a client_id. "
            "Either provide client_id explicitly, use 'GLOBAL' for global data, "
            "or configure MCP_DEFAULT_CLIENT_ID."
        )

    # Fire-and-forget: KB queues processing (embedding + extraction) in background
    headers = {"X-Ollama-Priority": "0"}
    payload = {
        "clientId": cid,
        "projectId": pid,
        "sourceUrn": source_urn,
        "kind": kind,
        "content": content,
        "metadata": json.loads(metadata) if metadata else {},
    }
    if group_id:
        payload["groupId"] = group_id
    async with httpx.AsyncClient(timeout=30, headers=headers) as client:
        try:
            resp = await client.post(
                f"{settings.knowledgebase_write_url}/api/v1/ingest-queue",
                json=payload,
            )
            resp.raise_for_status()
        except httpx.HTTPStatusError as e:
            detail = e.response.text[:500] if e.response else str(e)
            return f"Error storing to KB (HTTP {e.response.status_code}): {detail}"
        return f"Queued for processing (clientId={cid}, projectId={pid or 'none'})."


# ── KB Document Tools ───────────────────────────────────────────────────

import base64


@mcp.tool
async def kb_document_upload(
    file_content: str,
    file_name: str,
    client_id: str = "",
    project_id: str = "",
    title: str = "",
    description: str = "",
    category: str = "OTHER",
    tags: str = "",
) -> str:
    """Upload a document to the Knowledge Base.

    Accepts base64-encoded file content. Extracts text (via VLM for images/scans,
    pymupdf for PDFs, etc.) and indexes into RAG + knowledge graph.
    Supports PDF, DOCX, DOC, XLSX, XLS, TXT, CSV, images, and more.

    Returns immediately — extraction and indexing run in background.

    Args:
        file_content: Base64-encoded file content (the raw document bytes)
        file_name: Original filename with extension (e.g. "faktura.pdf", "report.docx")
        client_id: Client ID (leave empty for default)
        project_id: Project ID (leave empty for default)
        title: Human-readable document title (defaults to filename)
        description: Optional description / notes about the document
        category: TECHNICAL, BUSINESS, LEGAL, PROCESS, MEETING_NOTES, REPORT, SPECIFICATION, OTHER
        tags: Comma-separated tags for filtering (e.g. "api,design,v2")
    """
    import os
    import mimetypes

    cid = client_id or settings.default_client_id
    pid = project_id or settings.default_project_id or None

    if not file_content or not file_name:
        return "Error: Both file_content (base64) and file_name are required."

    # Validate filename extension
    allowed_extensions = {
        '.pdf', '.docx', '.doc', '.xlsx', '.xls', '.pptx',
        '.txt', '.csv', '.png', '.jpg', '.jpeg', '.webp',
        '.md', '.json', '.xml', '.html', '.htm',
        '.odt', '.ods', '.odp', '.rtf', '.epub',
        '.msg', '.eml',
    }
    ext = os.path.splitext(file_name)[1].lower()
    if ext not in allowed_extensions:
        return f"Error: File extension '{ext}' not allowed. Supported: {', '.join(sorted(allowed_extensions))}"

    try:
        file_bytes = base64.b64decode(file_content)
    except Exception as e:
        return f"Error: Invalid base64 content: {e}"

    max_size = 50 * 1024 * 1024  # 50 MB
    if len(file_bytes) > max_size:
        return f"Error: File too large ({len(file_bytes)} bytes). Max: {max_size} bytes (50 MB)"

    filename = file_name
    mime_type, _ = mimetypes.guess_type(file_name)
    mime_type = mime_type or "application/octet-stream"

    # Upload to KB service — returns immediately, extraction runs in background
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{settings.knowledgebase_write_url}/api/v1/documents/upload",
            files={"file": (filename, file_bytes, mime_type)},
            data={
                "clientId": cid,
                "projectId": pid or "",
                "filename": filename,
                "mimeType": mime_type,
                "storagePath": "",
                "title": title or filename,
                "description": description,
                "category": category,
                "tags": tags,
            },
        )
        resp.raise_for_status()
        result = resp.json()

    state = result.get("state", "UNKNOWN")
    doc_id = result.get("id", "")
    return (
        f"Document uploaded and queued for processing.\n"
        f"  ID: {doc_id}\n"
        f"  Filename: {filename}\n"
        f"  State: {state} (extraction + indexing runs in background)\n"
        f"  Size: {len(file_bytes)} bytes\n"
        f"Use kb_document_list to check processing status."
    )


@mcp.tool
async def kb_document_list(
    client_id: str = "",
    project_id: str = "",
) -> str:
    """List all documents in the Knowledge Base.

    Args:
        client_id: Client ID (leave empty for default)
        project_id: Project ID (leave empty for default, empty = all projects)
    """
    cid = client_id or settings.default_client_id
    pid = project_id or settings.default_project_id or None

    url = f"{settings.knowledgebase_url}/api/v1/documents?clientId={cid}"
    if pid:
        url += f"&projectId={pid}"

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(url)
        resp.raise_for_status()
        docs = resp.json()

    if not docs:
        return "No documents found."

    lines = [f"Found {len(docs)} document(s):\n"]
    for doc in docs:
        state_icon = {"INDEXED": "+", "EXTRACTED": "~", "UPLOADED": ".", "FAILED": "!"}
        icon = state_icon.get(doc.get("state", ""), "?")
        size_kb = doc.get("sizeBytes", 0) / 1024
        lines.append(
            f"  [{icon}] {doc.get('id', '')[:8]}  "
            f"{doc.get('title') or doc.get('filename', 'unknown')}  "
            f"({size_kb:.0f} KB, {doc.get('state', '?')}, {doc.get('category', '?')})"
        )
    return "\n".join(lines)


@mcp.tool
async def kb_document_delete(doc_id: str) -> str:
    """Delete a document from the Knowledge Base.

    Removes the file from storage, purges RAG chunks, and deletes the graph node.

    Args:
        doc_id: Document ID (from kb_document_list)
    """
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.delete(
            f"{settings.knowledgebase_write_url}/api/v1/documents/{doc_id}"
        )
        if resp.status_code == 404:
            return f"Document not found: {doc_id}"
        resp.raise_for_status()
        return f"Document {doc_id} deleted successfully."


@mcp.tool
async def kb_delete_by_source(
    source_urn: str,
    client_id: str = "",
) -> str:
    """Delete all KB data (RAG chunks + graph nodes/edges) for a given source_urn.

    Use this to clean up duplicates or outdated kb_store entries.
    Removes ALL chunks matching the source_urn, cleans graph references,
    and deletes orphaned nodes/edges.  Also cleans up ThoughtAnchors.

    Args:
        source_urn: Source identifier to purge (e.g., "infra://credentials", "agent://claude-code/finding")
        client_id: Optional client ID filter (empty = match any client)
    """
    from jervis.knowledgebase import ingest_pb2
    from jervis_contracts import kb_client
    import grpc

    stub = kb_client.ingest_stub()
    try:
        result = await stub.Purge(
            ingest_pb2.PurgeRequest(
                ctx=kb_client.build_request_context(caller="service-mcp", client_id=client_id),
                source_urn=source_urn,
                client_id=client_id,
            ),
            timeout=120.0,
        )
    except grpc.aio.AioRpcError as e:
        return f"Error purging KB ({e.code().name}): {e.details() or ''}"

    return (
        f"Purged source_urn='{source_urn}':\n"
        f"  RAG chunks deleted: {result.chunks_deleted}\n"
        f"  Graph nodes cleaned: {result.nodes_cleaned}\n"
        f"  Graph nodes deleted: {result.nodes_deleted}\n"
        f"  Graph edges cleaned: {result.edges_cleaned}\n"
        f"  Graph edges deleted: {result.edges_deleted}"
    )


# ── MongoDB Tools ────────────────────────────────────────────────────────

from datetime import datetime, timedelta, timezone
from bson import ObjectId


def _fmt_doc(doc: dict, max_content: int = 1000) -> str:
    """Format a MongoDB document for display."""
    lines = []
    for k, v in doc.items():
        if k == "_id":
            lines.append(f"id: {v}")
        elif isinstance(v, datetime):
            lines.append(f"{k}: {v.isoformat()}")
        elif isinstance(v, list) and len(v) > 5:
            lines.append(f"{k}: [{len(v)} items]")
        elif isinstance(v, str) and len(v) > max_content:
            lines.append(f"{k}: {v[:max_content]}...")
        elif isinstance(v, dict) and len(str(v)) > max_content:
            lines.append(f"{k}: {{...complex object...}}")
        else:
            lines.append(f"{k}: {v}")
    return "\n".join(lines)


@mcp.tool
async def list_clients() -> str:
    """List all clients in the system. Returns client IDs, names, and basic info."""
    db = await get_db()
    clients = []
    async for doc in db["clients"].find(
        {"archived": {"$ne": True}},
        {"name": 1, "description": 1, "defaultLanguageEnum": 1},
    ).sort("name", 1):
        clients.append(
            f"- {doc['name']} (id={doc['_id']})"
            + (f" – {doc.get('description', '')}" if doc.get("description") else "")
        )
    return "\n".join(clients) if clients else "No clients found."


@mcp.tool
async def get_client(client_id: str) -> str:
    """Get detailed information about a specific client.

    Args:
        client_id: The client ID to look up
    """
    db = await get_db()
    doc = await db["clients"].find_one({"_id": client_id})
    if not doc:
        return f"Client '{client_id}' not found."
    return _fmt_doc(doc)


@mcp.tool
async def list_projects(client_id: str = "") -> str:
    """List projects, optionally filtered by client.

    Args:
        client_id: Filter by client ID (leave empty for all projects)
    """
    db = await get_db()
    query = {}
    if client_id:
        query["clientId"] = client_id
    projects = []
    async for doc in db["projects"].find(
        query,
        {"name": 1, "clientId": 1, "description": 1, "groupId": 1, "workspaceStatus": 1},
    ).sort("name", 1):
        line = f"- {doc['name']} (id={doc['_id']}, client={doc.get('clientId', '?')})"
        if doc.get("description"):
            line += f" – {doc['description'][:100]}"
        if doc.get("groupId"):
            line += f" [group={doc['groupId']}]"
        projects.append(line)
    return "\n".join(projects) if projects else "No projects found."


@mcp.tool
async def get_project(project_id: str) -> str:
    """Get detailed information about a specific project.

    Args:
        project_id: The project ID to look up
    """
    db = await get_db()
    doc = await db["projects"].find_one({"_id": project_id})
    if not doc:
        return f"Project '{project_id}' not found."
    return _fmt_doc(doc)


@mcp.tool
async def list_meetings(
    client_id: str = "",
    project_id: str = "",
    state: str = "",
    limit: int = 20,
) -> str:
    """List meetings with optional filters.

    Args:
        client_id: Filter by client ID
        project_id: Filter by project ID
        state: Filter by state (RECORDING, UPLOADED, TRANSCRIBED, CORRECTED, INDEXED, FAILED)
        limit: Maximum results (default 20)
    """
    db = await get_db()
    query: dict = {"deleted": {"$ne": True}}
    if client_id:
        query["clientId"] = client_id
    if project_id:
        query["projectId"] = project_id
    if state:
        query["state"] = state

    meetings = []
    async for doc in db["meetings"].find(
        query,
        {"title": 1, "clientId": 1, "projectId": 1, "state": 1, "startedAt": 1, "durationSeconds": 1},
    ).sort("startedAt", -1).limit(limit):
        title = doc.get("title") or "Untitled"
        started = doc.get("startedAt", "?")
        if isinstance(started, datetime):
            started = started.strftime("%Y-%m-%d %H:%M")
        dur = doc.get("durationSeconds")
        dur_str = f" ({dur // 60}m{dur % 60}s)" if dur else ""
        meetings.append(
            f"- {title} (id={doc['_id']}) [{doc.get('state', '?')}] {started}{dur_str}"
        )
    return "\n".join(meetings) if meetings else "No meetings found."


@mcp.tool
async def get_meeting(meeting_id: str) -> str:
    """Get detailed information about a specific meeting, including transcript.

    Args:
        meeting_id: The meeting ID (ObjectId string)
    """
    db = await get_db()
    try:
        doc = await db["meetings"].find_one({"_id": ObjectId(meeting_id)})
    except Exception:
        doc = await db["meetings"].find_one({"_id": meeting_id})
    if not doc:
        return f"Meeting '{meeting_id}' not found."
    return _fmt_doc(doc, max_content=3000)


@mcp.tool
async def get_meeting_transcript(meeting_id: str, corrected: bool = True) -> str:
    """Get the transcript text of a meeting.

    Args:
        meeting_id: The meeting ID
        corrected: If True, return corrected transcript (if available), otherwise raw
    """
    db = await get_db()
    try:
        doc = await db["meetings"].find_one(
            {"_id": ObjectId(meeting_id)},
            {"transcriptText": 1, "correctedTranscriptText": 1, "transcriptSegments": 1,
             "correctedTranscriptSegments": 1, "title": 1, "state": 1},
        )
    except Exception:
        doc = await db["meetings"].find_one(
            {"_id": meeting_id},
            {"transcriptText": 1, "correctedTranscriptText": 1, "transcriptSegments": 1,
             "correctedTranscriptSegments": 1, "title": 1, "state": 1},
        )
    if not doc:
        return f"Meeting '{meeting_id}' not found."

    title = doc.get("title") or "Untitled"
    state = doc.get("state", "?")
    header = f"Meeting: {title} (state={state})\n{'=' * 40}\n"

    if corrected and doc.get("correctedTranscriptText"):
        return header + doc["correctedTranscriptText"]
    elif doc.get("transcriptText"):
        return header + doc["transcriptText"]

    segments = (doc.get("correctedTranscriptSegments") if corrected else None) or doc.get("transcriptSegments")
    if segments:
        lines = []
        for seg in segments:
            start = seg.get("startSec", 0)
            speaker = seg.get("speaker", "")
            text = seg.get("text", "")
            prefix = f"[{int(start // 60):02d}:{int(start % 60):02d}]"
            if speaker:
                prefix += f" {speaker}:"
            lines.append(f"{prefix} {text}")
        return header + "\n".join(lines)

    return header + "(No transcript available yet)"


# ── Meeting Attend (approval flow) ──────────────────────────────────────


@mcp.tool
async def meetings_upcoming(
    client_id: str = "",
    project_id: str = "",
    hours_ahead: int = 24,
    limit: int = 50,
) -> str:
    """List upcoming online meetings awaiting attend approval.

    Returns meeting tasks (type=CALENDAR_PROCESSING) whose scheduledAt falls
    within the next `hours_ahead` hours and have meetingMetadata. These are
    candidates for `meeting_attend_approve` / `meeting_attend_deny`.

    Args:
        client_id: Filter by client ID
        project_id: Filter by project ID
        hours_ahead: Look-ahead window in hours (default 24)
        limit: Maximum results (default 50)
    """
    params = {"hours_ahead": str(hours_ahead), "limit": str(limit)}
    if client_id:
        params["client_id"] = client_id
    if project_id:
        params["project_id"] = project_id
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.kotlin_server_url}/internal/meetings/upcoming",
            params=params,
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        items = resp.json()
        if not items:
            return "No upcoming meetings in the requested window."
        lines = []
        for m in items:
            join = f" join={m['joinUrl']}" if m.get("joinUrl") else ""
            org = f" by {m['organizer']}" if m.get("organizer") else ""
            lines.append(
                f"- {m['title']} (id={m['taskId']}) [{m['provider']}]\n"
                f"  start={m['startTime']} end={m['endTime']}{org}{join}\n"
                f"  client={m['clientId']} project={m['projectId'] or '-'}"
            )
        return "\n".join(lines)


@mcp.tool
async def meeting_attend_approve(task_id: str) -> str:
    """Approve a meeting-attend request.

    Marks the ApprovalQueue entry as APPROVED, writes a USER decision message
    into the meeting's chat thread, and cancels the in-app notification across
    devices. The task stays NEW so the (future) recording pipeline can pick it
    up via its scheduledAt window. Read-only first version: no audio out, no
    messages sent into the meeting.

    Args:
        task_id: TaskDocument ID of the CALENDAR_PROCESSING task
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/meetings/attend/approve",
            json={"taskId": task_id},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return f"Meeting approved: taskId={data['taskId']} state={data.get('state', '?')}"


@mcp.tool
async def meeting_attend_deny(task_id: str, reason: str = "") -> str:
    """Deny a meeting-attend request.

    Marks the ApprovalQueue entry as DENIED, transitions the task to DONE, and
    records the reason in the chat bubble. Jervis will not ask again about this
    meeting instance.

    Args:
        task_id: TaskDocument ID of the CALENDAR_PROCESSING task
        reason: Optional human-readable reason (shown in the chat bubble)
    """
    body: dict = {"taskId": task_id}
    if reason:
        body["reason"] = reason
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/meetings/attend/deny",
            json=body,
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return f"Meeting denied: taskId={data['taskId']} state={data.get('state', '?')}"


@mcp.tool
async def meeting_attend_status(task_id: str) -> str:
    """Get the current approval status of a meeting-attend task.

    Reads the approval_queue collection directly to show PENDING / APPROVED /
    DENIED / EXPIRED status, plus respondedAt and the original preview/context.

    Args:
        task_id: TaskDocument ID of the CALENDAR_PROCESSING task
    """
    db = await get_db()
    queue_doc = await db["approval_queue"].find_one({"taskId": task_id})
    if not queue_doc:
        return f"No approval queue entry for task '{task_id}'."
    lines = [
        f"Task: {task_id}",
        f"Action: {queue_doc.get('action', '?')}",
        f"Status: {queue_doc.get('status', '?')}",
        f"Risk: {queue_doc.get('riskLevel', '?')}",
        f"Created: {queue_doc.get('createdAt', '?')}",
    ]
    if queue_doc.get("respondedAt"):
        lines.append(f"Responded: {queue_doc['respondedAt']}")
    if queue_doc.get("preview"):
        lines.append(f"Preview: {queue_doc['preview']}")
    if queue_doc.get("payload"):
        payload = queue_doc["payload"]
        if payload.get("startTime"):
            lines.append(f"Start: {payload['startTime']}")
        if payload.get("endTime"):
            lines.append(f"End: {payload['endTime']}")
        if payload.get("provider"):
            lines.append(f"Provider: {payload['provider']}")
        if payload.get("joinUrl"):
            lines.append(f"Join URL: {payload['joinUrl']}")
    return "\n".join(lines)


# ── User-joined meeting alone-check (product §10a) ─────────────────────


@mcp.tool
async def meeting_alone_leave(meeting_id: str, reason: str = "user_asked_to_leave") -> str:
    """Leave a meeting the user is alone in.

    Resolves the `meeting_alone_check` push when the user reacts "Odejít"
    (bubble button) or says "vypadni z meetingu" / "ten meeting už je
    prázdný". Server dispatches `leave_meeting` to the pod agent via
    `/instruction/{connectionId}`; the pod stops recording, clicks Leave,
    and reports presence=false.

    Args:
        meeting_id: MeetingDocument id from the alone-check push.
        reason: Short reason stored with the leave (default
            'user_asked_to_leave').
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/meetings/{meeting_id}/leave",
            json={"reason": reason},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return (
            f"Leave dispatched: meetingId={meeting_id} "
            f"state={data.get('state', '?')} reason={reason}"
        )


@mcp.tool
async def meeting_alone_stay(meeting_id: str, suppress_minutes: int = 30) -> str:
    """Keep Jervis in the meeting even though the user is alone.

    Resolves the `meeting_alone_check` push when the user reacts "Zůstat"
    or says "nech to ještě běžet". Server stops further alone-check pushes
    for `suppress_minutes` (default 30) so the pod's watcher does not spam.

    Args:
        meeting_id: MeetingDocument id from the alone-check push.
        suppress_minutes: How long to suppress further alone-check pushes
            (default 30, clamped 1..180).
    """
    try:
        from app.grpc_clients import server_meeting_alone_stub
        from jervis.server import meeting_alone_pb2
        from jervis.common import types_pb2
        from jervis_contracts.interceptors import prepare_context

        ctx = types_pb2.RequestContext()
        prepare_context(ctx)
        resp = await server_meeting_alone_stub().Stay(
            meeting_alone_pb2.StayRequest(
                ctx=ctx,
                meeting_id=meeting_id,
                suppress_minutes=suppress_minutes,
            ),
            timeout=30.0,
        )
        return (
            f"Stay acknowledged: meetingId={resp.meeting_id} "
            f"suppressMinutes={resp.suppress_minutes}"
        )
    except Exception as e:
        return f"Error: {e}"


# ── Off-hours relogin approval (product §18) ───────────────────────────


@mcp.tool
async def connection_approve_relogin(connection_id: str) -> str:
    """Approve an off-hours relogin for an O365 / Teams connection.

    Called when the user answers "ano přihlas to znova" to an `auth_request`
    push outside work hours. Server posts
    `/instruction/{connectionId} approve_relogin` to the pod agent, which
    resumes the AUTHENTICATING flow (credential fill → MFA) even though the
    work-hours / user-activity gate would normally block it (product §18).

    Args:
        connection_id: ConnectionDocument id from the auth_request push.
    """
    try:
        from app.grpc_clients import server_connection_stub
        from jervis.server import connection_pb2
        from jervis.common import types_pb2
        from jervis_contracts.interceptors import prepare_context

        ctx = types_pb2.RequestContext()
        prepare_context(ctx)
        resp = await server_connection_stub().ApproveRelogin(
            connection_pb2.ApproveReloginRequest(ctx=ctx, connection_id=connection_id),
            timeout=30.0,
        )
        return f"Relogin approved: connectionId={resp.connection_id} state={resp.state}"
    except Exception as e:
        return f"Error: {e}"


@mcp.tool
async def list_tasks(
    client_id: str = "",
    project_id: str = "",
    state: str = "",
    processing_mode: str = "",
    limit: int = 20,
) -> str:
    """List tasks (work items / conversations) with optional filters.

    Args:
        client_id: Filter by client ID
        project_id: Filter by project ID
        state: Filter by state (NEW, INDEXING, QUEUED, PROCESSING, DONE, ERROR)
        processing_mode: Filter by mode (FOREGROUND, BACKGROUND)
        limit: Maximum results (default 20)
    """
    db = await get_db()
    query: dict = {}
    if client_id:
        query["clientId"] = client_id
    if project_id:
        query["projectId"] = project_id
    if state:
        query["state"] = state
    if processing_mode:
        query["processingMode"] = processing_mode

    tasks = []
    async for doc in db["tasks"].find(
        query,
        {"taskName": 1, "content": 1, "clientId": 1, "projectId": 1, "state": 1,
         "processingMode": 1, "createdAt": 1, "type": 1},
    ).sort("createdAt", -1).limit(limit):
        name = doc.get("taskName") or "Unnamed"
        content_preview = (doc.get("content") or "")[:80]
        created = doc.get("createdAt", "?")
        if isinstance(created, datetime):
            created = created.strftime("%Y-%m-%d %H:%M")
        tasks.append(
            f"- {name} (id={doc['_id']}) [{doc.get('state', '?')}/{doc.get('processingMode', '?')}] "
            f"{created}\n  {content_preview}"
        )
    return "\n".join(tasks) if tasks else "No tasks found."


@mcp.tool
async def get_task(task_id: str) -> str:
    """Get detailed information about a specific task.

    Args:
        task_id: The task ID
    """
    db = await get_db()
    doc = await db["tasks"].find_one({"_id": task_id})
    if not doc:
        return f"Task '{task_id}' not found."
    return _fmt_doc(doc, max_content=2000)


@mcp.tool
async def get_chat_history(
    task_id: str,
    limit: int = 50,
    role: str = "",
) -> str:
    """Get chat messages for a task/conversation.

    Args:
        task_id: The task ID to get messages for
        limit: Maximum messages to return (default 50)
        role: Filter by role (USER, ASSISTANT, SYSTEM) – empty for all
    """
    db = await get_db()
    query: dict = {"taskId": task_id}
    if role:
        query["role"] = role

    messages = []
    async for doc in db["chat_messages"].find(query).sort("sequence", 1).limit(limit):
        ts = doc.get("timestamp", "?")
        if isinstance(ts, datetime):
            ts = ts.strftime("%H:%M:%S")
        r = doc.get("role", "?")
        content = doc.get("content", "")[:500]
        messages.append(f"[{ts}] {r}: {content}")
    return "\n\n".join(messages) if messages else "No messages found."


@mcp.tool
async def list_connections(provider: str = "", state: str = "") -> str:
    """List configured connections (GitHub, GitLab, Atlassian, etc.).

    Args:
        provider: Filter by provider (GITHUB, GITLAB, ATLASSIAN, GOOGLE, etc.)
        state: Filter by state (ACTIVE, FAILED, EXPIRED, NEW)
    """
    db = await get_db()
    query: dict = {}
    if provider:
        query["provider"] = provider
    if state:
        query["state"] = state

    conns = []
    async for doc in db["connections"].find(
        query,
        {"name": 1, "provider": 1, "state": 1, "baseUrl": 1, "availableCapabilities": 1},
    ).sort("name", 1):
        caps = ", ".join(doc.get("availableCapabilities", []))
        conns.append(
            f"- {doc['name']} (id={doc['_id']}) [{doc.get('provider', '?')}/{doc.get('state', '?')}]"
            + (f"\n  URL: {doc.get('baseUrl', '?')}" if doc.get("baseUrl") else "")
            + (f"\n  Capabilities: {caps}" if caps else "")
        )
    return "\n".join(conns) if conns else "No connections found."


@mcp.tool
async def mongo_query(
    collection: str,
    filter_json: str = "{}",
    projection_json: str = "{}",
    sort_json: str = "{}",
    limit: int = 20,
) -> str:
    """Execute a custom MongoDB read query on any collection.

    Use this for advanced queries not covered by other tools. Read-only.

    Args:
        collection: Collection name (clients, projects, meetings, tasks, chat_messages, connections, project_groups)
        filter_json: MongoDB filter as JSON (e.g., '{"state": "INDEXED"}')
        projection_json: Fields to include/exclude as JSON (e.g., '{"name": 1, "state": 1}')
        sort_json: Sort specification as JSON (e.g., '{"createdAt": -1}')
        limit: Maximum documents to return
    """
    db = await get_db()
    try:
        filt = json.loads(filter_json)
        proj = json.loads(projection_json) or None
        sort_spec = json.loads(sort_json)
    except json.JSONDecodeError as e:
        return f"Invalid JSON: {e}"

    sort_list = list(sort_spec.items()) if sort_spec else [("_id", -1)]
    docs = []
    cursor = db[collection].find(filt, proj).sort(sort_list).limit(limit)
    async for doc in cursor:
        docs.append(_fmt_doc(doc))
    return "\n---\n".join(docs) if docs else "No documents found."


# ── Orchestrator Tools ───────────────────────────────────────────────────

@mcp.tool
async def orchestrator_health() -> str:
    """Check the health/status of Jervis services (Kotlin server + orchestrator)."""
    results = []
    async with httpx.AsyncClient(timeout=10) as client:
        try:
            resp = await client.get(f"{settings.kotlin_server_url}/")
            results.append(f"Kotlin Server: {resp.text.strip()}")
        except Exception as e:
            results.append(f"Kotlin Server: unreachable ({e})")

        try:
            resp = await client.get("http://jervis-orchestrator:8090/health")
            resp.raise_for_status()
            results.append(f"Orchestrator: {resp.json()}")
        except Exception as e:
            results.append(f"Orchestrator: unreachable ({e})")

    return "\n".join(results)


@mcp.tool
async def orchestrator_status(thread_id: str) -> str:
    """Get the status of a running orchestration thread.

    Args:
        thread_id: The orchestrator thread ID (from task's orchestratorThreadId)
    """
    async with httpx.AsyncClient(timeout=15) as client:
        try:
            resp = await client.get(f"http://jervis-orchestrator:8090/status/{thread_id}")
            resp.raise_for_status()
            data = resp.json()
            lines = [
                f"Thread: {thread_id}",
                f"Status: {data.get('status', '?')}",
                f"Summary: {data.get('summary', 'N/A')}",
            ]
            if data.get("error"):
                lines.append(f"Error: {data['error']}")
            if data.get("branch"):
                lines.append(f"Branch: {data['branch']}")
            return "\n".join(lines)
        except Exception as e:
            return f"Cannot reach orchestrator: {e}"


@mcp.tool
async def submit_task(
    client_id: str,
    project_id: str,
    query: str,
    task_name: str = "",
    processing_mode: str = "BACKGROUND",
) -> str:
    """Submit a new task to Jervis for processing.

    Creates a task document that will be picked up by BackgroundEngine.

    Args:
        client_id: Client ID for the task
        project_id: Project ID for the task
        query: The task description / user query
        task_name: Display name for the task (auto-generated if empty)
        processing_mode: FOREGROUND (interactive) or BACKGROUND (autonomous)
    """
    from app.grpc_clients import server_task_api_stub
    from jervis.common import types_pb2
    from jervis.server import task_api_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_task_api_stub().CreateTask(
            task_api_pb2.CreateTaskRequest(
                ctx=ctx,
                client_id=client_id,
                project_id=project_id,
                title=task_name or "",
                query=query,
                created_by="mcp-submit",
                priority=1,
                skip_indexing=True,
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error creating task: {str(e)[:300]}"
    return f"Task created: id={resp.task_id}, state={resp.state}, name={resp.name}"


# ── Scheduled Task Tools ─────────────────────────────────────────────────

@mcp.tool
async def schedule_task(
    client_id: str,
    project_id: str,
    query: str,
    task_name: str = "",
    scheduled_at_iso: str = "",
    cron_expression: str = "",
) -> str:
    """Schedule a task for future or recurring execution.

    Creates a SCHEDULED task that BackgroundEngine will pick up at the scheduled time.
    For one-time tasks, provide scheduled_at_iso. For recurring, provide cron_expression.

    Args:
        client_id: Client ID for the task
        project_id: Project ID for the task
        query: The task description / instruction for the agent
        task_name: Display name for the task (auto-generated if empty)
        scheduled_at_iso: ISO datetime when to run (e.g. "2026-02-18T09:00:00"). Empty = now.
        cron_expression: Cron expression for recurring tasks (e.g. "0 9 * * MON"). Empty = one-time.
    """
    import uuid
    from datetime import timezone
    from bson import ObjectId

    db = await get_db()
    # Use MongoDB ObjectId (24-char hex) — matches Kotlin server expectations.
    # UUID truncated to 24 chars would contain dashes → invalid ObjectId → Spring Data crashes.
    task_id = ObjectId()
    now = datetime.now(tz=timezone.utc)

    if scheduled_at_iso:
        try:
            scheduled_at = datetime.fromisoformat(scheduled_at_iso.replace("Z", "+00:00"))
            # Ensure UTC — convert if timezone-aware, assume UTC if naive
            if scheduled_at.tzinfo:
                scheduled_at = scheduled_at.astimezone(timezone.utc)
            else:
                scheduled_at = scheduled_at.replace(tzinfo=timezone.utc)
        except ValueError:
            return f"Error: Invalid ISO datetime format: {scheduled_at_iso}"
    else:
        scheduled_at = now

    task_doc = {
        "_id": task_id,
        "type": "SCHEDULED",
        "taskName": task_name or query[:60],
        "content": query,
        "projectId": project_id or None,
        "clientId": client_id,
        "createdAt": now,
        "state": "NEW",
        "processingMode": "BACKGROUND",
        "correlationId": str(uuid.uuid4()),
        "sourceUrn": f"mcp://scheduled/{task_name or 'task'}",
        "qualificationRetries": 0,
        "dispatchRetryCount": 0,
        "scheduledAt": scheduled_at,
    }
    if cron_expression:
        task_doc["cronExpression"] = cron_expression
        # Store timezone at creation time — cron is always evaluated in this timezone
        task_doc["cronTimezone"] = "Europe/Prague"  # MCP tasks use default; chat tasks pass client tz

    await db["tasks"].insert_one(task_doc)
    sched_str = scheduled_at.strftime("%Y-%m-%d %H:%M")
    cron_str = f", cron={cron_expression}" if cron_expression else ""
    return f"Scheduled task created: id={task_id}, scheduledAt={sched_str}{cron_str}"


@mcp.tool
async def list_scheduled_tasks(
    client_id: str = "",
    project_id: str = "",
    include_done: bool = False,
    limit: int = 20,
) -> str:
    """List scheduled tasks (type=SCHEDULED) with optional filters.

    Args:
        client_id: Filter by client ID
        project_id: Filter by project ID
        include_done: Include completed/failed tasks (default: only active)
        limit: Maximum results (default 20)
    """
    db = await get_db()
    query: dict = {"type": "SCHEDULED"}
    if client_id:
        query["clientId"] = client_id
    if project_id:
        query["projectId"] = project_id
    if not include_done:
        query["state"] = {"$nin": ["DONE", "ERROR"]}

    tasks = []
    async for doc in db["tasks"].find(
        query,
        {"taskName": 1, "content": 1, "clientId": 1, "projectId": 1, "state": 1,
         "scheduledAt": 1, "cronExpression": 1, "createdAt": 1},
    ).sort("scheduledAt", 1).limit(limit):
        name = doc.get("taskName") or "Unnamed"
        content_preview = (doc.get("content") or "")[:80]
        sched = doc.get("scheduledAt", "?")
        if isinstance(sched, datetime):
            sched = sched.strftime("%Y-%m-%d %H:%M")
        cron = doc.get("cronExpression")
        cron_str = f" cron={cron}" if cron else ""
        tasks.append(
            f"- {name} (id={doc['_id']}) [{doc.get('state', '?')}] "
            f"scheduled={sched}{cron_str}\n  {content_preview}"
        )
    return "\n".join(tasks) if tasks else "No scheduled tasks found."


@mcp.tool
async def cancel_scheduled_task(task_id: str) -> str:
    """Cancel a scheduled task by setting its state to ERROR with cancellation message.

    Only works on tasks that haven't started processing yet (state in NEW, INDEXING, QUEUED).

    Args:
        task_id: The task ID to cancel
    """
    db = await get_db()
    result = await db["tasks"].update_one(
        {
            "_id": task_id,
            "type": "SCHEDULED",
            "state": {"$in": ["NEW", "INDEXING", "QUEUED"]},
        },
        {
            "$set": {
                "state": "ERROR",
                "errorMessage": "Cancelled via MCP",
            },
        },
    )
    if result.modified_count == 1:
        return f"Task {task_id} cancelled."
    # Check why it failed
    doc = await db["tasks"].find_one({"_id": task_id})
    if not doc:
        return f"Error: Task {task_id} not found."
    if doc.get("type") != "SCHEDULED":
        return f"Error: Task {task_id} is not a scheduled task (type={doc.get('type')})."
    return f"Error: Cannot cancel task in state={doc.get('state')} (already processing or finished)."


# ── Environment Management Tools (CRUD) ──────────────────────────────────
# Phase 4: Chat-first environment management.
# Agents can create, configure, deploy, and inspect environments via MCP.
# These call the Kotlin backend's /internal/environments/* REST endpoints.


@mcp.tool
async def environment_list(client_id: str = "") -> str:
    """List all environments, optionally filtered by client.

    Returns environment IDs, names, namespaces, states, and component counts.

    Args:
        client_id: Filter by client ID (leave empty for all environments)
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().ListEnvironments(
            environment_pb2.ListEnvironmentsRequest(ctx=ctx, client_id=client_id or ""),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    envs = json.loads(resp.items_json)
    if not envs:
        return "No environments found."
    lines = []
    for env in envs:
        components = env.get("components", [])
        infra = sum(1 for c in components if c.get("type") != "PROJECT")
        apps = sum(1 for c in components if c.get("type") == "PROJECT")
        tier = env.get("tier", "DEV")
        lines.append(
            f"- {env['name']} [{tier}] (id={env['id']})\n"
            f"  namespace={env['namespace']}, state={env['state']}\n"
            f"  components: {len(components)} ({infra} infra, {apps} app)"
        )
    return "\n".join(lines)


@mcp.tool
async def environment_get(environment_id: str) -> str:
    """Get detailed information about a specific environment.

    Shows all configuration including components, links, property mappings,
    agent instructions, and current state.

    Args:
        environment_id: The environment ID
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().GetEnvironment(
            environment_pb2.GetEnvironmentRequest(ctx=ctx, environment_id=environment_id),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    lines = [
        f"Environment: {env['name']}",
        f"ID: {env['id']}",
        f"Tier: {env.get('tier', 'DEV')}",
        f"Namespace: {env['namespace']}",
        f"State: {env['state']}",
        f"Client: {env['clientId']}",
    ]
    if env.get("groupId"):
        lines.append(f"Group: {env['groupId']}")
    if env.get("projectId"):
        lines.append(f"Project: {env['projectId']}")
    if env.get("description"):
        lines.append(f"Description: {env['description']}")
    lines.append(f"Storage: {env.get('storageSizeGi', 5)}Gi")

    components = env.get("components", [])
    if components:
        lines.append(f"\nComponents ({len(components)}):")
        for comp in components:
            state_str = f" [{comp.get('componentState', 'PENDING')}]" if comp.get("componentState") else ""
            img = comp.get("image") or "(no image)"
            lines.append(f"  - {comp['name']} ({comp['type']}){state_str}")
            lines.append(f"    image: {img}")
            if comp.get("ports"):
                ports_str = ", ".join(
                    f"{p['containerPort']}" + (f":{p['servicePort']}" if p.get('servicePort') else "")
                    for p in comp["ports"]
                )
                lines.append(f"    ports: {ports_str}")
            if comp.get("envVars"):
                lines.append(f"    env: {len(comp['envVars'])} vars")
            if comp.get("sourceRepo"):
                lines.append(f"    source: {comp['sourceRepo']}@{comp.get('sourceBranch', 'main')}")
            if comp.get("dockerfilePath"):
                lines.append(f"    dockerfile: {comp['dockerfilePath']}")

    if env.get("agentInstructions"):
        lines.append(f"\nAgent Instructions:\n{env['agentInstructions']}")

    return "\n".join(lines)


@mcp.tool
async def environment_create(
    client_id: str,
    name: str,
    namespace: str = "",
    tier: str = "DEV",
    description: str = "",
    agent_instructions: str = "",
    storage_size_gi: int = 5,
) -> str:
    """Create a new environment definition.

    Creates the environment in DB (state=PENDING). Use environment_deploy to
    actually provision K8s resources.

    Args:
        client_id: Client ID this environment belongs to
        name: Human-readable environment name
        namespace: K8s namespace (auto-generated from name if empty)
        tier: Environment tier: DEV, STAGING, or PROD (default DEV)
        description: Optional description
        agent_instructions: Free-text instructions for agents about this environment
        storage_size_gi: PVC storage size in Gi (default 5)
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().CreateEnvironment(
            environment_pb2.CreateEnvironmentRequest(
                ctx=ctx,
                client_id=client_id,
                name=name,
                namespace=namespace or "",
                tier=tier.upper(),
                description=description or "",
                agent_instructions=agent_instructions or "",
                storage_size_gi=storage_size_gi,
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    return (
        f"Environment created: {env['name']} (id={env['id']})\n"
        f"Namespace: {env['namespace']}\n"
        f"State: {env['state']}\n"
        f"Use environment_add_component to add infrastructure, then environment_deploy to provision."
    )


@mcp.tool
async def environment_delete(environment_id: str) -> str:
    """Delete an environment and deprovision its K8s resources.

    WARNING: This will delete the K8s namespace and all resources in it.

    Args:
        environment_id: The environment ID to delete
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        await server_environment_stub().DeleteEnvironment(
            environment_pb2.DeleteEnvironmentRequest(ctx=ctx, environment_id=environment_id),
            timeout=60.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    return f"Environment {environment_id} deleted."


@mcp.tool
async def environment_add_component(
    environment_id: str,
    name: str,
    component_type: str,
    image: str = "",
    version: str = "",
    env_vars: str = "{}",
    source_repo: str = "",
    source_branch: str = "",
    dockerfile_path: str = "",
) -> str:
    """Add a component to an environment.

    Component types: POSTGRESQL, MONGODB, REDIS, RABBITMQ, KAFKA, ELASTICSEARCH,
    ORACLE, MYSQL, MINIO, CUSTOM_INFRA, PROJECT.

    Infrastructure components get default images, ports, and env vars from templates.
    Use version parameter to pick a specific version (e.g., "17" for PostgreSQL 17).

    For PROJECT type, provide source_repo and source_branch for build pipeline.

    Args:
        environment_id: The environment ID
        name: Component name (also used as K8s service/deployment name)
        component_type: One of the supported component types
        image: Docker image override (uses template default if empty)
        version: Version hint to pick from template (e.g., "17", "8.0")
        env_vars: Additional env vars as JSON string (merged with defaults)
        source_repo: Git repo URL (for PROJECT type)
        source_branch: Git branch (for PROJECT type)
        dockerfile_path: Path to Dockerfile in repo (for PROJECT type)
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    try:
        extra_env = json.loads(env_vars) if env_vars and env_vars != "{}" else {}
    except json.JSONDecodeError:
        return f"Error: Invalid JSON for env_vars: {env_vars}"

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().AddComponent(
            environment_pb2.AddComponentRequest(
                ctx=ctx,
                environment_id=environment_id,
                name=name,
                type=component_type.upper(),
                image=image or "",
                version=version or "",
                env_vars=extra_env,
                source_repo=source_repo or "",
                source_branch=source_branch or "",
                dockerfile_path=dockerfile_path or "",
                start_order_auto=True,
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    comp = next((c for c in env.get("components", []) if c["name"] == name), None)
    if comp:
        return (
            f"Component added: {comp['name']} ({comp['type']})\n"
            f"Image: {comp.get('image', '(none)')}\n"
            f"Ports: {', '.join(str(p['containerPort']) for p in comp.get('ports', []))}\n"
            f"Total components: {len(env['components'])}"
        )
    return f"Component added. Total components: {len(env.get('components', []))}"


@mcp.tool
async def environment_configure(
    environment_id: str,
    component_name: str,
    image: str = "",
    env_vars: str = "{}",
    cpu_limit: str = "",
    memory_limit: str = "",
    source_repo: str = "",
    source_branch: str = "",
    dockerfile_path: str = "",
) -> str:
    """Update configuration of an existing component.

    Only provided fields are updated; others remain unchanged.

    Args:
        environment_id: The environment ID
        component_name: Name or ID of the component to configure
        image: New Docker image
        env_vars: Additional env vars as JSON string (merged with existing)
        cpu_limit: K8s CPU limit (e.g., "500m", "1")
        memory_limit: K8s memory limit (e.g., "512Mi", "1Gi")
        source_repo: Git repository URL
        source_branch: Git branch
        dockerfile_path: Path to Dockerfile
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    try:
        extra_env = json.loads(env_vars) if env_vars and env_vars != "{}" else {}
        has_env_vars = bool(extra_env)
    except json.JSONDecodeError:
        return f"Error: Invalid JSON for env_vars: {env_vars}"
    if not any([image, has_env_vars, cpu_limit, memory_limit, source_repo, source_branch, dockerfile_path]):
        return "Error: No configuration changes provided."

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        await server_environment_stub().ConfigureComponent(
            environment_pb2.ConfigureComponentRequest(
                ctx=ctx,
                environment_id=environment_id,
                component_name=component_name,
                image=image or "",
                env_vars=extra_env,
                has_env_vars=has_env_vars,
                cpu_limit=cpu_limit or "",
                memory_limit=memory_limit or "",
                source_repo=source_repo or "",
                source_branch=source_branch or "",
                dockerfile_path=dockerfile_path or "",
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    return f"Component '{component_name}' updated."


@mcp.tool
async def environment_deploy(environment_id: str) -> str:
    """Provision/deploy an environment to Kubernetes.

    Creates K8s namespace, PVC, and deploys infrastructure components.
    Environment must be in PENDING, STOPPED, or ERROR state.

    Args:
        environment_id: The environment ID to deploy
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().DeployEnvironment(
            environment_pb2.EnvironmentIdRequest(ctx=ctx, environment_id=environment_id),
            timeout=120.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    return (
        f"Environment deployed: {env['name']}\n"
        f"Namespace: {env['namespace']}\n"
        f"State: {env['state']}\n"
        f"Use get_namespace_status('{env['namespace']}') to check health."
    )


@mcp.tool
async def environment_stop(environment_id: str) -> str:
    """Stop/deprovision an environment (tear down K8s resources).

    Stops all deployments. The environment definition is preserved in DB
    and can be re-deployed with environment_deploy.

    Args:
        environment_id: The environment ID to stop
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().StopEnvironment(
            environment_pb2.EnvironmentIdRequest(ctx=ctx, environment_id=environment_id),
            timeout=60.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    return f"Environment stopped: {env['name']} (state={env['state']})"


@mcp.tool
async def environment_status(environment_id: str) -> str:
    """Get deployment status of an environment and its components.

    Shows per-component readiness, replica counts, and any error messages.

    Args:
        environment_id: The environment ID
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().GetEnvironmentStatus(
            environment_pb2.EnvironmentIdRequest(ctx=ctx, environment_id=environment_id),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    status = json.loads(resp.body_json)
    lines = [
        f"Environment Status:",
        f"Namespace: {status['namespace']}",
        f"State: {status['state']}",
    ]
    for comp in status.get("componentStatuses", []):
        ready = "READY" if comp.get("ready") else "NOT READY"
        avail = comp.get("availableReplicas", 0)
        total = comp.get("replicas", 0)
        lines.append(f"  - {comp['name']}: {ready} ({avail}/{total})")
        if comp.get("message"):
            lines.append(f"    {comp['message']}")
    return "\n".join(lines)


@mcp.tool
async def environment_sync(environment_id: str) -> str:
    """Sync environment resources — re-apply K8s manifests from DB.

    Use after modifying component configuration to apply changes to
    running deployments. Environment must be in RUNNING state.

    Args:
        environment_id: The environment ID to sync
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().SyncEnvironment(
            environment_pb2.EnvironmentIdRequest(ctx=ctx, environment_id=environment_id),
            timeout=60.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    return f"Environment synced: {env['name']} (state={env['state']})"


@mcp.tool
async def environment_clone(
    environment_id: str,
    new_name: str,
    new_namespace: str = "",
    new_tier: str = "",
    target_client_id: str = "",
    target_group_id: str = "",
    target_project_id: str = "",
) -> str:
    """Clone an environment to a new scope (different client, group, or project).

    Creates a fresh copy with PENDING state, new namespace, and reset runtime state.
    All components and property mappings are copied but resolved values are cleared.
    Use environment_deploy on the clone to provision it.

    Args:
        environment_id: Source environment ID to clone from
        new_name: Name for the cloned environment
        new_namespace: K8s namespace for the clone (auto-generated from name if empty)
        new_tier: Tier for the clone: DEV, STAGING, PROD (inherits from source if empty)
        target_client_id: Move clone to a different client (uses source client if empty)
        target_group_id: Assign clone to a group (uses source group if empty)
        target_project_id: Assign clone to a project (uses source project if empty)
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().CloneEnvironment(
            environment_pb2.CloneEnvironmentRequest(
                ctx=ctx,
                environment_id=environment_id,
                new_name=new_name,
                new_namespace=new_namespace or "",
                new_tier=(new_tier or "").upper(),
                target_client_id=target_client_id or "",
                target_group_id=target_group_id or "",
                target_project_id=target_project_id or "",
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    return (
        f"Environment cloned: {env['name']} (id={env['id']})\n"
        f"Tier: {env.get('tier', 'DEV')}, Namespace: {env['namespace']}\n"
        f"State: {env['state']} — use environment_deploy to provision."
    )


@mcp.tool
async def environment_add_property_mapping(
    environment_id: str,
    project_component: str,
    property_name: str,
    target_component: str,
    value_template: str,
) -> str:
    """Add an environment variable mapping from an infrastructure component to a project component.

    Maps an ENV var in the project to a value derived from an infrastructure component.
    Use template placeholders: {host}, {port}, {name}, {env:VAR_NAME}.

    Example: property_name="DATABASE_URL", target_component="postgresql",
             value_template="postgresql://postgres:{env:POSTGRES_PASSWORD}@{host}:{port}/mydb"

    Args:
        environment_id: The environment ID
        project_component: ID of the project component (receiving the env var)
        property_name: ENV var name (e.g., DATABASE_URL, SPRING_DATASOURCE_URL)
        target_component: ID of the infrastructure component (source of connection info)
        value_template: Value template with placeholders ({host}, {port}, {env:VAR_NAME})
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().AddPropertyMapping(
            environment_pb2.AddPropertyMappingRequest(
                ctx=ctx,
                environment_id=environment_id,
                project_component_id=project_component,
                property_name=property_name,
                target_component_id=target_component,
                value_template=value_template,
            ),
            timeout=30.0,
        )
    except Exception as e:
        msg = str(e)
        if "already exists" in msg:
            return f"Mapping for '{property_name}' already exists."
        return f"Error: {msg[:300]}"
    env = json.loads(resp.body_json)
    count = len(env.get("propertyMappings", []))
    return f"Property mapping added: {property_name} → {target_component}. Total mappings: {count}."


@mcp.tool
async def environment_auto_suggest_mappings(environment_id: str) -> str:
    """Auto-generate property mappings for all PROJECT x INFRA component pairs.

    Uses predefined templates (JDBC URLs, Redis URIs, etc.) to create mappings.
    Skips mappings that already exist. Run this after adding all components to
    quickly set up standard connection env vars.

    Args:
        environment_id: The environment ID
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().AutoSuggestPropertyMappings(
            environment_pb2.EnvironmentIdRequest(ctx=ctx, environment_id=environment_id),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    total = len(env.get("propertyMappings", []))
    return (
        f"Auto-suggested mappings for '{env['name']}'.\n"
        f"Added: {resp.mappings_added}, Total: {total}.\n"
        f"Use environment_deploy to provision and resolve values."
    )


@mcp.tool
async def environment_discover_namespace(
    namespace: str,
    client_id: str,
    name: str = "",
    tier: str = "DEV",
) -> str:
    """Discover existing K8s namespace and create a Jervis Environment from running resources.

    Reads deployments, services, and configmaps to auto-populate environment components.
    Use this to import an existing K8s namespace into Jervis.

    Args:
        namespace: K8s namespace to discover (must already exist)
        client_id: Client ID to scope the environment
        name: Optional human-readable name (defaults to env-{namespace})
        tier: Environment tier — DEV, STAGING, PROD (default: DEV)
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().DiscoverNamespace(
            environment_pb2.DiscoverNamespaceRequest(
                ctx=ctx,
                namespace=namespace,
                client_id=client_id,
                name=name or "",
                tier=tier,
            ),
            timeout=60.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    comp_count = len(env.get("components", []))
    return (
        f"Discovered namespace '{namespace}' → environment '{env['name']}' (id={env['id']}).\n"
        f"Found {comp_count} components. State: {env.get('state', '?')}.\n"
        f"Agent RBAC configured for namespace."
    )


@mcp.tool
async def environment_replicate(
    environment_id: str,
    new_name: str,
    new_namespace: str = "",
    new_tier: str = "",
    target_client_id: str = "",
) -> str:
    """Clone an environment and deploy it to a new K8s namespace in one step.

    Creates a copy of the environment with all components and property mappings,
    then provisions the new K8s namespace and deploys infrastructure.

    Args:
        environment_id: Source environment ID to clone from
        new_name: Name for the new environment
        new_namespace: K8s namespace for the clone (defaults to sanitized new_name)
        new_tier: Override tier (DEV/STAGING/PROD, defaults to source tier)
        target_client_id: Optional different client ID for the clone
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().ReplicateEnvironment(
            environment_pb2.ReplicateEnvironmentRequest(
                ctx=ctx,
                environment_id=environment_id,
                new_name=new_name,
                new_namespace=new_namespace or "",
                new_tier=new_tier or "",
                target_client_id=target_client_id or "",
            ),
            timeout=120.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    comp_count = len(env.get("components", []))
    return (
        f"Replicated environment → '{env['name']}' (id={env['id']}).\n"
        f"Namespace: {env.get('namespace')}, {comp_count} components deployed.\n"
        f"State: {env.get('state', '?')}."
    )


@mcp.tool
async def environment_sync_from_k8s(environment_id: str) -> str:
    """Sync K8s state back to Jervis DB (K8s is source of truth).

    Reads current K8s resources (deployments, services, configmaps) and updates
    the Jervis environment to match. Use this after making kubectl changes to
    ensure Jervis DB reflects the actual K8s state.

    Args:
        environment_id: The environment ID to sync
    """
    from app.grpc_clients import server_environment_stub
    from jervis.common import types_pb2
    from jervis.server import environment_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_stub().SyncFromK8s(
            environment_pb2.EnvironmentIdRequest(ctx=ctx, environment_id=environment_id),
            timeout=60.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    env = json.loads(resp.body_json)
    comp_count = len(env.get("components", []))
    running = sum(1 for c in env.get("components", []) if c.get("componentState") == "RUNNING")
    return (
        f"Synced from K8s: '{env['name']}' (ns={env.get('namespace')}).\n"
        f"{comp_count} components total, {running} running.\n"
        f"Jervis DB now matches K8s state."
    )


# ── Environment / K8s Tools ──────────────────────────────────────────────
# These tools call the Kotlin backend's internal REST endpoints which perform
# the actual fabric8 K8s operations. K8s credentials stay server-side.
# Namespace is passed as a parameter (not from env var like the stdio version).


@mcp.tool
async def list_namespace_resources(namespace: str, resource_type: str = "all") -> str:
    """List resources in a K8s namespace.

    Returns pods, deployments, services, and secrets in the namespace.

    Args:
        namespace: K8s namespace to inspect
        resource_type: Filter by type — "all", "pods", "deployments", "services", "secrets"
    """
    from app.grpc_clients import server_environment_k8s_stub
    from jervis.common import types_pb2
    from jervis.server import environment_k8s_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_k8s_stub().ListNamespaceResources(
            environment_k8s_pb2.ListNamespaceResourcesRequest(
                ctx=ctx, namespace=namespace, type=resource_type,
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    if not resp.ok:
        return f"Error: {resp.error or 'unknown'}"
    resources = json.loads(resp.data_json) if resp.data_json else {}
    lines = [f"Namespace: {namespace}", ""]
    for rtype, items in resources.items():
        lines.append(f"## {rtype.title()} ({len(items)})")
        for item in items:
            name = item.get("name", "?")
            if rtype == "pods":
                phase = item.get("phase", "?")
                ready = "READY" if item.get("ready") else "NOT READY"
                restarts = item.get("restartCount", 0)
                lines.append(f"  - {name}: {phase} ({ready}, restarts={restarts})")
            elif rtype == "deployments":
                avail = item.get("availableReplicas", 0)
                total = item.get("replicas", 0)
                image = item.get("image", "?")
                lines.append(f"  - {name}: {avail}/{total} ready ({image})")
            elif rtype == "services":
                svc_type = item.get("type", "?")
                ports = item.get("ports", [])
                lines.append(f"  - {name}: {svc_type} ({', '.join(ports) if ports else 'no ports'})")
            elif rtype == "secrets":
                keys = item.get("keys", [])
                lines.append(f"  - {name}: keys={keys}")
            else:
                lines.append(f"  - {name}")
        lines.append("")
    return "\n".join(lines)


@mcp.tool
async def get_pod_logs(namespace: str, pod_name: str, tail_lines: int = 100) -> str:
    """Get recent logs from a pod.

    Args:
        namespace: K8s namespace
        pod_name: Name of the pod
        tail_lines: Number of recent log lines to return (max 1000)
    """
    from app.grpc_clients import server_environment_k8s_stub
    from jervis.common import types_pb2
    from jervis.server import environment_k8s_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_k8s_stub().GetPodLogs(
            environment_k8s_pb2.GetPodLogsRequest(
                ctx=ctx, namespace=namespace, pod_name=pod_name,
                tail_lines=min(tail_lines, 1000),
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    if not resp.ok:
        return f"Error: {resp.error or 'unknown'}"
    return resp.logs


@mcp.tool
async def get_deployment_status(namespace: str, name: str) -> str:
    """Get detailed status of a deployment including conditions and recent events.

    Args:
        namespace: K8s namespace
        name: Deployment name
    """
    from app.grpc_clients import server_environment_k8s_stub
    from jervis.common import types_pb2
    from jervis.server import environment_k8s_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_k8s_stub().GetDeploymentStatus(
            environment_k8s_pb2.GetDeploymentStatusRequest(
                ctx=ctx, namespace=namespace, deployment_name=name,
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    if not resp.ok:
        return f"Error: {resp.error or 'unknown'}"
    d = json.loads(resp.data_json) if resp.data_json else {}
    lines = [
        f"Deployment: {d.get('name', '?')}",
        f"Namespace: {d.get('namespace', '?')}",
        f"Image: {d.get('image', '?')}",
        f"Replicas: {d.get('availableReplicas', 0)}/{d.get('replicas', 0)} ready",
        f"Status: {'HEALTHY' if d.get('ready') else 'UNHEALTHY'}",
        "",
    ]
    conditions = d.get("conditions", [])
    if conditions:
        lines.append("Conditions:")
        for c in conditions:
            lines.append(f"  - {c.get('type')}: {c.get('status')} ({c.get('reason', '')})")
            if c.get("message"):
                lines.append(f"    {c['message']}")
        lines.append("")
    events = d.get("events", [])
    if events:
        lines.append("Recent Events:")
        for ev in events:
            lines.append(f"  [{ev.get('type', '?')}] {ev.get('reason', '?')}: {ev.get('message', '')}")
    return "\n".join(lines)


@mcp.tool
async def scale_deployment(namespace: str, name: str, replicas: int) -> str:
    """Scale a deployment to the specified number of replicas.

    Args:
        namespace: K8s namespace
        name: Deployment name
        replicas: Target number of replicas (0-10)
    """
    if replicas < 0 or replicas > 10:
        return "Error: replicas must be between 0 and 10"
    from app.grpc_clients import server_environment_k8s_stub
    from jervis.common import types_pb2
    from jervis.server import environment_k8s_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_k8s_stub().ScaleDeployment(
            environment_k8s_pb2.ScaleDeploymentRequest(
                ctx=ctx, namespace=namespace, deployment_name=name, replicas=replicas,
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    if not resp.ok:
        return f"Error: {resp.error or 'unknown'}"
    return resp.message or "Scaled successfully"


@mcp.tool
async def restart_deployment(namespace: str, name: str) -> str:
    """Trigger a rolling restart of a deployment.

    Args:
        namespace: K8s namespace
        name: Deployment name
    """
    from app.grpc_clients import server_environment_k8s_stub
    from jervis.common import types_pb2
    from jervis.server import environment_k8s_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_k8s_stub().RestartDeployment(
            environment_k8s_pb2.RestartDeploymentRequest(
                ctx=ctx, namespace=namespace, deployment_name=name,
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    if not resp.ok:
        return f"Error: {resp.error or 'unknown'}"
    return resp.message or "Restart triggered"


@mcp.tool
async def get_namespace_status(namespace: str) -> str:
    """Get overall health status of a K8s namespace.

    Returns pod counts, deployment readiness, and identifies any crashing pods.

    Args:
        namespace: K8s namespace to inspect
    """
    from app.grpc_clients import server_environment_k8s_stub
    from jervis.common import types_pb2
    from jervis.server import environment_k8s_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_environment_k8s_stub().GetNamespaceStatus(
            environment_k8s_pb2.GetNamespaceStatusRequest(ctx=ctx, namespace=namespace),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error: {str(e)[:300]}"
    if not resp.ok:
        return f"Error: {resp.error or 'unknown'}"
    s = json.loads(resp.data_json) if resp.data_json else {}
    healthy = s.get("healthy", False)
    pods = s.get("pods", {})
    deps = s.get("deployments", {})
    svcs = s.get("services", {})
    lines = [
        f"Namespace: {s.get('namespace', '?')}",
        f"Overall: {'HEALTHY' if healthy else 'UNHEALTHY'}",
        "",
        f"Pods: {pods.get('running', 0)}/{pods.get('total', 0)} running",
        f"Deployments: {deps.get('ready', 0)}/{deps.get('total', 0)} ready",
        f"Services: {svcs.get('total', 0)}",
    ]
    crashing = pods.get("crashing", [])
    if crashing:
        lines.extend(["", "CRASHING PODS:"])
        for pod in crashing:
            lines.append(f"  - {pod}")
        lines.append("")
        lines.append("Use get_pod_logs(namespace, pod_name) to inspect logs from crashing pods.")
    return "\n".join(lines)


# ── Project Management Tools ─────────────────────────────────────────────
# Create clients, projects, connections via Kotlin internal API.


@mcp.tool
async def create_client(name: str, description: str = "") -> str:
    """Create a new client (organization / workspace).

    IMPORTANT: Only use when explicitly requested. Before creating, always search
    existing clients with list_clients — the name is likely just spelled differently
    (abbreviation, typo, different language). 99% of mentions refer to existing clients.

    Args:
        name: Client name (must be unique)
        description: Optional description
    """
    from app.grpc_clients import server_project_management_stub
    from jervis.common import types_pb2
    from jervis.server import project_management_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_project_management_stub().CreateClient(
            project_management_pb2.CreateClientRequest(
                ctx=ctx,
                name=name,
                description=description or "",
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error creating client: {str(e)[:300]}"
    return f"Client created: {resp.name} (id={resp.id})"


@mcp.tool
async def create_project(
    client_id: str,
    name: str,
    description: str = "",
) -> str:
    """Create a new project within a client.

    IMPORTANT: Only use when explicitly requested. Before creating, always search
    existing projects with list_projects(client_id) — the name is likely just spelled
    differently, abbreviated, or in another language. 99% of mentions refer to existing projects.

    Args:
        client_id: ID of the client that owns this project
        name: Project name
        description: Optional project description
    """
    from app.grpc_clients import server_project_management_stub
    from jervis.common import types_pb2
    from jervis.server import project_management_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_project_management_stub().CreateProject(
            project_management_pb2.CreateProjectRequest(
                ctx=ctx,
                client_id=client_id,
                name=name,
                description=description or "",
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error creating project: {str(e)[:300]}"
    return (
        f"Project created: {resp.name} "
        f"(id={resp.id}, clientId={resp.client_id})"
    )


@mcp.tool
async def create_connection(
    name: str,
    provider: str,
    auth_type: str = "BEARER",
    base_url: str = "",
    bearer_token: str = "",
    is_cloud: bool = False,
) -> str:
    """Create a new external service connection (GitHub, GitLab, Atlassian, etc.).

    Args:
        name: Connection name (must be unique)
        provider: Service provider: GITHUB, GITLAB, ATLASSIAN, GOOGLE_WORKSPACE, GENERIC_EMAIL, SLACK, MICROSOFT_TEAMS, DISCORD.
        auth_type: Authentication type: NONE, BASIC, BEARER, OAUTH2
        base_url: API base URL (leave empty for cloud providers)
        bearer_token: Bearer/personal access token (for BEARER auth)
        is_cloud: Whether to use provider's default cloud URL
    """
    from app.grpc_clients import server_project_management_stub
    from jervis.common import types_pb2
    from jervis.server import project_management_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_project_management_stub().CreateConnection(
            project_management_pb2.CreateConnectionRequest(
                ctx=ctx,
                name=name,
                provider=provider,
                protocol="HTTP",
                auth_type=auth_type,
                base_url=base_url or "",
                is_cloud=is_cloud,
                bearer_token=bearer_token or "",
            ),
            timeout=30.0,
        )
    except Exception as e:
        return f"Error creating connection: {str(e)[:300]}"
    return (
        f"Connection created: {resp.name} "
        f"(id={resp.id}, provider={resp.provider})"
    )


@mcp.tool
async def create_git_repository(
    client_id: str,
    name: str,
    description: str = "",
    connection_id: str = "",
    is_private: bool = True,
) -> str:
    """Create a new git repository on GitHub or GitLab via the client's connection.

    Uses the client's REPOSITORY connection to call the provider API.
    Supports GitHub (POST /user/repos) and GitLab (POST /api/v4/projects).

    Args:
        client_id: Client ID whose connection will be used
        name: Repository name
        description: Optional repository description
        connection_id: Specific connection ID to use (empty = auto-detect)
        is_private: Whether the repository should be private (default: true)
    """
    try:
        import json as _json

        from app.grpc_clients import server_git_stub
        from jervis.server import git_pb2
        from jervis.common import types_pb2
        from jervis_contracts.interceptors import prepare_context

        ctx = types_pb2.RequestContext()
        prepare_context(ctx)
        resp = await server_git_stub().CreateRepository(
            git_pb2.CreateRepositoryRequest(
                ctx=ctx,
                client_id=client_id,
                connection_id=connection_id or "",
                name=name,
                description=description or "",
                is_private=is_private,
            ),
            timeout=60.0,
        )
        data = _json.loads(resp.body_json) if resp.body_json else {}
        return (
            f"Repository created: {data.get('fullName', name)}\n"
            f"  Clone URL: {data.get('cloneUrl', '?')}\n"
            f"  Web URL: {data.get('htmlUrl', '?')}\n"
            f"  Provider: {data.get('provider', '?')}"
        )
    except Exception as e:
        return f"Error: {e}"


@mcp.tool
async def init_workspace(project_id: str) -> str:
    """Initialize (clone) the workspace for a project.

    Triggers an async git clone for the project's repository.
    The workspace will be available once cloning completes.

    Args:
        project_id: ID of the project to initialize workspace for
    """
    try:
        from app.grpc_clients import server_git_stub
        from jervis.server import git_pb2
        from jervis.common import types_pb2
        from jervis_contracts.interceptors import prepare_context

        ctx = types_pb2.RequestContext()
        prepare_context(ctx)
        resp = await server_git_stub().InitWorkspace(
            git_pb2.InitWorkspaceRequest(ctx=ctx, project_id=project_id),
            timeout=30.0,
        )
        if resp.ok:
            return f"Workspace initialization triggered for project {project_id}"
        return f"Error: {resp.error or 'unknown'}"
    except Exception as e:
        return f"Error: {e}"


# ── O365 Teams Tools ────────────────────────────────────────────────────


@mcp.tool
async def o365_teams_list_chats(
    client_id: str,
    top: int = 20,
) -> str:
    """List recent Teams chats for a client.

    Returns chat list with topic, type, and last message preview.
    Requires an active O365 session for the client.

    Args:
        client_id: Client ID (JERVIS client)
        top: Number of chats to return (max 50, default 20)
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/chats/{client_id}",
            params={"top": min(top, 50)},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        chats = resp.json()
        if not chats:
            return "No chats found."
        lines = []
        for c in chats:
            topic = c.get("topic") or "(no topic)"
            chat_type = c.get("chatType", "?")
            chat_id = c.get("id", "?")
            preview = ""
            mp = c.get("lastMessagePreview")
            if mp and mp.get("body"):
                preview_text = mp["body"].get("content", "")[:100]
                from_user = ""
                if mp.get("from") and mp["from"].get("user"):
                    from_user = mp["from"]["user"].get("displayName", "")
                preview = f" | {from_user}: {preview_text}"
            lines.append(f"[{chat_type}] {topic} (id={chat_id}){preview}")
        return "\n".join(lines)


@mcp.tool
async def o365_teams_read_chat(
    client_id: str,
    chat_id: str,
    top: int = 20,
) -> str:
    """Read messages from a specific Teams chat.

    Args:
        client_id: Client ID
        chat_id: Chat ID (from o365_teams_list_chats)
        top: Number of messages to return (default 20)
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/chats/{client_id}/{chat_id}/messages",
            params={"top": top},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        messages = resp.json()
        if not messages:
            return "No messages found."
        lines = []
        for m in messages:
            sender = "?"
            if m.get("from"):
                if m["from"].get("user"):
                    sender = m["from"]["user"].get("displayName", "?")
                elif m["from"].get("application"):
                    sender = m["from"]["application"].get("displayName", "bot")
            body = ""
            if m.get("body"):
                body = m["body"].get("content", "")[:500]
            ts = m.get("createdDateTime", "")
            lines.append(f"[{ts}] {sender}: {body}")
        return "\n---\n".join(lines)


@mcp.tool
async def o365_teams_send_message(
    client_id: str,
    chat_id: str,
    content: str,
    content_type: str = "text",
) -> str:
    """Send a message to a Teams chat.

    Args:
        client_id: Client ID
        chat_id: Chat ID (from o365_teams_list_chats)
        content: Message content
        content_type: "text" or "html" (default "text")
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.o365_gateway_url}/api/o365/chats/{client_id}/{chat_id}/messages",
            json={"contentType": content_type, "content": content},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return f"Message sent (id={data.get('id', '?')})"


@mcp.tool
async def o365_teams_list_teams(
    client_id: str,
) -> str:
    """List teams the user is a member of.

    Args:
        client_id: Client ID
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/teams/{client_id}",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        teams = resp.json()
        if not teams:
            return "No teams found."
        return "\n".join(
            f"{t.get('displayName', '?')} (id={t.get('id', '?')})"
            for t in teams
        )


@mcp.tool
async def o365_teams_list_channels(
    client_id: str,
    team_id: str,
) -> str:
    """List channels in a team.

    Args:
        client_id: Client ID
        team_id: Team ID (from o365_teams_list_teams)
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/teams/{client_id}/{team_id}/channels",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        channels = resp.json()
        if not channels:
            return "No channels found."
        return "\n".join(
            f"{ch.get('displayName', '?')} (id={ch.get('id', '?')}, type={ch.get('membershipType', '?')})"
            for ch in channels
        )


@mcp.tool
async def o365_teams_read_channel(
    client_id: str,
    team_id: str,
    channel_id: str,
    top: int = 20,
) -> str:
    """Read messages from a Teams channel.

    Args:
        client_id: Client ID
        team_id: Team ID
        channel_id: Channel ID (from o365_teams_list_channels)
        top: Number of messages to return (default 20)
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/teams/{client_id}/{team_id}/channels/{channel_id}/messages",
            params={"top": top},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        messages = resp.json()
        if not messages:
            return "No messages found."
        lines = []
        for m in messages:
            sender = "?"
            if m.get("from"):
                if m["from"].get("user"):
                    sender = m["from"]["user"].get("displayName", "?")
                elif m["from"].get("application"):
                    sender = m["from"]["application"].get("displayName", "bot")
            body = ""
            if m.get("body"):
                body = m["body"].get("content", "")[:500]
            ts = m.get("createdDateTime", "")
            lines.append(f"[{ts}] {sender}: {body}")
        return "\n---\n".join(lines)


@mcp.tool
async def o365_teams_send_channel_message(
    client_id: str,
    team_id: str,
    channel_id: str,
    content: str,
) -> str:
    """Send a message to a Teams channel.

    Args:
        client_id: Client ID
        team_id: Team ID
        channel_id: Channel ID
        content: Message content
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.o365_gateway_url}/api/o365/teams/{client_id}/{team_id}/channels/{channel_id}/messages",
            json={"contentType": "text", "content": content},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return f"Channel message sent (id={data.get('id', '?')})"


@mcp.tool
async def o365_session_status(
    client_id: str,
) -> str:
    """Check O365 session status for a client.

    Returns session state, token age, last refresh time, and noVNC URL if login is needed.

    Args:
        client_id: Client ID
    """
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/session/{client_id}",
        )
        if resp.status_code == 404:
            return f"No O365 session for client '{client_id}'. Use browser pool to initialize one."
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        state = data.get("state", "?")
        lines = [f"Session state: {state}"]
        if data.get("lastActivity"):
            lines.append(f"Last activity: {data['lastActivity']}")
        if data.get("lastTokenExtract"):
            lines.append(f"Last token extract: {data['lastTokenExtract']}")
        if data.get("novncUrl"):
            lines.append(f"noVNC URL (for manual login): {data['novncUrl']}")
        return "\n".join(lines)


# ── O365 Mail (Outlook) Tools ───────────────────────────────────────────


@mcp.tool
async def o365_mail_list(
    client_id: str,
    top: int = 20,
    folder: str = "inbox",
) -> str:
    """List recent emails for a client from Outlook.

    Args:
        client_id: Client ID
        top: Number of emails to return (default 20)
        folder: Mail folder — inbox, sentitems, drafts, etc. (default "inbox")
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/mail/{client_id}",
            params={"top": top, "folder": folder},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        messages = resp.json()
        if not messages:
            return f"No emails found in '{folder}'."
        lines = []
        for m in messages:
            subj = m.get("subject", "(no subject)")
            sender = ""
            if m.get("from") and m["from"].get("emailAddress"):
                ea = m["from"]["emailAddress"]
                sender = ea.get("name") or ea.get("address", "?")
            ts = m.get("receivedDateTime", "")
            read = "" if m.get("isRead") else " [UNREAD]"
            attach = " [ATTACH]" if m.get("hasAttachments") else ""
            msg_id = m.get("id", "?")
            preview = (m.get("bodyPreview") or "")[:120]
            lines.append(f"[{ts}] {sender}: {subj}{read}{attach}\n  id={msg_id}\n  {preview}")
        return "\n---\n".join(lines)


@mcp.tool
async def o365_mail_read(
    client_id: str,
    message_id: str,
) -> str:
    """Read a specific email message with full body content.

    Args:
        client_id: Client ID
        message_id: Message ID (from o365_mail_list)
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/mail/{client_id}/{message_id}",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        m = resp.json()
        sender = ""
        if m.get("from") and m["from"].get("emailAddress"):
            ea = m["from"]["emailAddress"]
            sender = f"{ea.get('name', '')} <{ea.get('address', '')}>"
        to_list = []
        for r in m.get("toRecipients") or []:
            if r.get("emailAddress"):
                to_list.append(r["emailAddress"].get("address", ""))
        cc_list = []
        for r in m.get("ccRecipients") or []:
            if r.get("emailAddress"):
                cc_list.append(r["emailAddress"].get("address", ""))
        body_content = ""
        if m.get("body"):
            body_content = m["body"].get("content", "")[:3000]
        parts = [
            f"Subject: {m.get('subject', '(none)')}",
            f"From: {sender}",
            f"To: {', '.join(to_list)}",
        ]
        if cc_list:
            parts.append(f"CC: {', '.join(cc_list)}")
        parts.append(f"Date: {m.get('receivedDateTime', '?')}")
        if m.get("hasAttachments"):
            parts.append("Has attachments: yes")
        parts.append(f"\n{body_content}")
        return "\n".join(parts)


@mcp.tool
async def o365_mail_send(
    client_id: str,
    to: str,
    subject: str,
    body: str,
    cc: str = "",
    content_type: str = "text",
) -> str:
    """Send an email via Outlook.

    Args:
        client_id: Client ID
        to: Comma-separated list of recipient email addresses
        subject: Email subject
        body: Email body content
        cc: Optional comma-separated list of CC email addresses
        content_type: "text" or "html" (default "text")
    """
    to_recipients = [
        {"emailAddress": {"address": addr.strip()}}
        for addr in to.split(",") if addr.strip()
    ]
    cc_recipients = [
        {"emailAddress": {"address": addr.strip()}}
        for addr in cc.split(",") if addr.strip()
    ] if cc else []

    payload = {
        "message": {
            "subject": subject,
            "body": {"contentType": content_type, "content": body},
            "toRecipients": to_recipients,
            "ccRecipients": cc_recipients,
        },
        "saveToSentItems": True,
    }
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.o365_gateway_url}/api/o365/mail/{client_id}/send",
            json=payload,
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        return "Email sent successfully."


# ── O365 Calendar Tools ─────────────────────────────────────────────────


@mcp.tool
async def o365_calendar_events(
    client_id: str,
    top: int = 20,
    start_date_time: str = "",
    end_date_time: str = "",
) -> str:
    """List calendar events for a client.

    If start/end date-time are provided, uses calendarView for that range.
    Otherwise returns upcoming events.

    Args:
        client_id: Client ID
        top: Number of events to return (default 20)
        start_date_time: ISO 8601 start (e.g. "2026-03-12T00:00:00Z"), empty for upcoming
        end_date_time: ISO 8601 end (e.g. "2026-03-19T23:59:59Z"), empty for upcoming
    """
    params: dict = {"top": top}
    if start_date_time:
        params["startDateTime"] = start_date_time
    if end_date_time:
        params["endDateTime"] = end_date_time

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/calendar/{client_id}",
            params=params,
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        events = resp.json()
        if not events:
            return "No events found."
        lines = []
        for ev in events:
            subj = ev.get("subject", "(no subject)")
            start = ""
            if ev.get("start"):
                start = ev["start"].get("dateTime", "?")
            end = ""
            if ev.get("end"):
                end = ev["end"].get("dateTime", "?")
            loc = ""
            if ev.get("location") and ev["location"].get("displayName"):
                loc = f" @ {ev['location']['displayName']}"
            online = " [ONLINE]" if ev.get("isOnlineMeeting") else ""
            all_day = " [ALL DAY]" if ev.get("isAllDay") else ""
            ev_id = ev.get("id", "?")
            attendees = []
            for a in ev.get("attendees") or []:
                if a.get("emailAddress"):
                    attendees.append(a["emailAddress"].get("name") or a["emailAddress"].get("address", ""))
            att_str = f"\n  Attendees: {', '.join(attendees)}" if attendees else ""
            lines.append(
                f"{subj}{all_day}{online}{loc}\n"
                f"  {start} → {end}\n"
                f"  id={ev_id}{att_str}"
            )
        return "\n---\n".join(lines)


@mcp.tool
async def o365_calendar_create(
    client_id: str,
    subject: str,
    start_date_time: str,
    start_time_zone: str,
    end_date_time: str,
    end_time_zone: str,
    location: str = "",
    body: str = "",
    attendees: str = "",
    is_online_meeting: bool = False,
) -> str:
    """Create a calendar event.

    Args:
        client_id: Client ID
        subject: Event subject/title
        start_date_time: Start time in ISO 8601 (e.g. "2026-03-15T10:00:00")
        start_time_zone: IANA timezone (e.g. "Europe/Prague")
        end_date_time: End time in ISO 8601
        end_time_zone: IANA timezone
        location: Optional location name
        body: Optional event body/description
        attendees: Optional comma-separated list of attendee emails
        is_online_meeting: Create as Teams meeting (default false)
    """
    payload: dict = {
        "subject": subject,
        "start": {"dateTime": start_date_time, "timeZone": start_time_zone},
        "end": {"dateTime": end_date_time, "timeZone": end_time_zone},
        "isOnlineMeeting": is_online_meeting,
    }
    if location:
        payload["location"] = {"displayName": location}
    if body:
        payload["body"] = {"contentType": "text", "content": body}
    if attendees:
        payload["attendees"] = [
            {"emailAddress": {"address": addr.strip()}, "type": "required"}
            for addr in attendees.split(",") if addr.strip()
        ]

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.o365_gateway_url}/api/o365/calendar/{client_id}",
            json=payload,
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        ev = resp.json()
        result = f"Event created: {ev.get('subject', subject)} (id={ev.get('id', '?')})"
        if ev.get("onlineMeetingUrl"):
            result += f"\nTeams meeting URL: {ev['onlineMeetingUrl']}"
        if ev.get("webLink"):
            result += f"\nWeb link: {ev['webLink']}"
        return result


# ── O365 OneDrive / SharePoint Tools ────────────────────────────────────


@mcp.tool
async def o365_files_list(
    client_id: str,
    path: str = "root",
    top: int = 50,
) -> str:
    """List files and folders in OneDrive.

    Args:
        client_id: Client ID
        path: Folder path relative to root (e.g. "Documents/Reports"), or "root" for top-level
        top: Max items to return (default 50)
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/drive/{client_id}",
            params={"path": path, "top": top},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        items = resp.json()
        if not items:
            return f"No items found at '{path}'."
        lines = []
        for item in items:
            name = item.get("name", "?")
            is_folder = item.get("folder") is not None
            icon = "[DIR]" if is_folder else "[FILE]"
            size = ""
            if not is_folder and item.get("size"):
                size_kb = item["size"] / 1024
                size = f" ({size_kb:.1f} KB)" if size_kb < 1024 else f" ({size_kb / 1024:.1f} MB)"
            modified = item.get("lastModifiedDateTime", "")
            item_id = item.get("id", "?")
            child_count = ""
            if is_folder and item.get("folder", {}).get("childCount") is not None:
                child_count = f" [{item['folder']['childCount']} items]"
            lines.append(f"{icon} {name}{size}{child_count} (modified={modified}, id={item_id})")
        return "\n".join(lines)


@mcp.tool
async def o365_files_download(
    client_id: str,
    item_id: str,
) -> str:
    """Get download info for a OneDrive file.

    Returns the file metadata including download URL. Use the download URL
    to fetch the actual file content.

    Args:
        client_id: Client ID
        item_id: Drive item ID (from o365_files_list)
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/drive/{client_id}/item/{item_id}",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        item = resp.json()
        parts = [
            f"Name: {item.get('name', '?')}",
            f"Size: {item.get('size', '?')} bytes",
        ]
        if item.get("file"):
            parts.append(f"MIME type: {item['file'].get('mimeType', '?')}")
        if item.get("webUrl"):
            parts.append(f"Web URL: {item['webUrl']}")
        if item.get("@microsoft.graph.downloadUrl"):
            parts.append(f"Download URL: {item['@microsoft.graph.downloadUrl']}")
        return "\n".join(parts)


@mcp.tool
async def o365_files_search(
    client_id: str,
    query: str,
    top: int = 25,
) -> str:
    """Search for files in OneDrive.

    Args:
        client_id: Client ID
        query: Search query (searches file names and content)
        top: Max results (default 25)
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.o365_gateway_url}/api/o365/drive/{client_id}/search",
            params={"q": query, "top": top},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        items = resp.json()
        if not items:
            return f"No files found for query '{query}'."
        lines = []
        for item in items:
            name = item.get("name", "?")
            is_folder = item.get("folder") is not None
            icon = "[DIR]" if is_folder else "[FILE]"
            size = ""
            if not is_folder and item.get("size"):
                size_kb = item["size"] / 1024
                size = f" ({size_kb:.1f} KB)" if size_kb < 1024 else f" ({size_kb / 1024:.1f} MB)"
            path = ""
            if item.get("parentReference") and item["parentReference"].get("path"):
                path = f" in {item['parentReference']['path']}"
            item_id = item.get("id", "?")
            lines.append(f"{icon} {name}{size}{path} (id={item_id})")
        return "\n".join(lines)


# ── Issue / Bug Tracker tools ────────────────────────────────────────────


@mcp.tool
async def create_issue(
    client_id: str,
    project_id: str,
    title: str,
    description: str = "",
    labels: str = "",
) -> str:
    """Create a new issue on the project's bug tracker (GitHub Issues or GitLab Issues).

    Uses the project's BUGTRACKER connection to create an issue via the provider API.

    Args:
        client_id: Client ID that owns the project
        project_id: Project ID (must have a BUGTRACKER or REPOSITORY connection)
        title: Issue title
        description: Issue body/description (markdown supported)
        labels: Comma-separated labels (e.g. "bug,priority:high")
    """
    from app.grpc_clients import server_bug_tracker_stub
    from jervis.common import types_pb2
    from jervis.server import bug_tracker_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    label_list = [l.strip() for l in labels.split(",") if l.strip()] if labels else []
    try:
        resp = await server_bug_tracker_stub().CreateIssue(
            bug_tracker_pb2.CreateIssueRequest(
                ctx=ctx,
                client_id=client_id,
                project_id=project_id,
                title=title,
                description=description or "",
                labels=label_list,
            ),
            timeout=60.0,
        )
    except Exception as e:
        return f"Error creating issue: {str(e)[:300]}"
    if resp.ok:
        return f"Issue created: {resp.key or '?'}\n  URL: {resp.url or '?'}"
    return f"Error: {resp.error or 'unknown'}"


@mcp.tool
async def add_issue_comment(
    client_id: str,
    project_id: str,
    issue_key: str,
    comment: str,
) -> str:
    """Add a comment to an existing issue on the project's bug tracker.

    Args:
        client_id: Client ID that owns the project
        project_id: Project ID
        issue_key: Issue number/key (e.g. "#1", "1", "#42")
        comment: Comment body (markdown supported)
    """
    from app.grpc_clients import server_bug_tracker_stub
    from jervis.common import types_pb2
    from jervis.server import bug_tracker_pb2
    from jervis_contracts.interceptors import prepare_context

    key = issue_key if issue_key.startswith("#") else f"#{issue_key}"
    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_bug_tracker_stub().AddIssueComment(
            bug_tracker_pb2.AddIssueCommentRequest(
                ctx=ctx,
                client_id=client_id,
                project_id=project_id,
                issue_key=key,
                comment=comment,
            ),
            timeout=60.0,
        )
    except Exception as e:
        return f"Error adding comment: {str(e)[:300]}"
    if resp.ok:
        url_info = f"\n  URL: {resp.url}" if resp.url else ""
        return f"Comment added to issue {key}{url_info}"
    return f"Error: {resp.error or 'unknown'}"


@mcp.tool
async def update_issue(
    client_id: str,
    project_id: str,
    issue_key: str,
    title: str = "",
    description: str = "",
    state: str = "",
    labels: str = "",
) -> str:
    """Update an existing issue on the project's bug tracker.

    Can change title, description, state (open/closed), and labels.
    Only provided (non-empty) fields are updated.

    Args:
        client_id: Client ID that owns the project
        project_id: Project ID
        issue_key: Issue number/key (e.g. "#6", "6", "#42")
        title: New title (leave empty to keep current)
        description: New description/body (leave empty to keep current)
        state: New state - "open" or "closed" (leave empty to keep current)
        labels: Comma-separated labels (e.g. "bug,priority:high,analysis"). Replaces all existing labels.
    """
    from app.grpc_clients import server_bug_tracker_stub
    from jervis.common import types_pb2
    from jervis.server import bug_tracker_pb2
    from jervis_contracts.interceptors import prepare_context

    key = issue_key if issue_key.startswith("#") else f"#{issue_key}"
    has_labels = bool(labels)
    label_list = (
        [l.strip() for l in labels.split(",") if l.strip()] if has_labels else []
    )
    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_bug_tracker_stub().UpdateIssue(
            bug_tracker_pb2.UpdateIssueRequest(
                ctx=ctx,
                client_id=client_id,
                project_id=project_id,
                issue_key=key,
                title=title or "",
                description=description or "",
                state=state or "",
                labels=label_list,
                has_labels=has_labels,
            ),
            timeout=60.0,
        )
    except Exception as e:
        return f"Error updating issue: {str(e)[:300]}"
    if resp.ok:
        url_info = f"\n  URL: {resp.url}" if resp.url else ""
        return f"Issue {key} updated successfully{url_info}"
    return f"Error: {resp.error or 'unknown'}"


@mcp.tool
async def list_issues(
    client_id: str,
    project_id: str,
) -> str:
    """List issues from the project's bug tracker (GitHub Issues or GitLab Issues).

    Args:
        client_id: Client ID that owns the project
        project_id: Project ID
    """
    from app.grpc_clients import server_bug_tracker_stub
    from jervis.common import types_pb2
    from jervis.server import bug_tracker_pb2
    from jervis_contracts.interceptors import prepare_context

    ctx = types_pb2.RequestContext()
    prepare_context(ctx)
    try:
        resp = await server_bug_tracker_stub().ListIssues(
            bug_tracker_pb2.ListIssuesRequest(
                ctx=ctx,
                client_id=client_id,
                project_id=project_id,
            ),
            timeout=60.0,
        )
    except Exception as e:
        return f"Error listing issues: {str(e)[:300]}"
    if not resp.ok:
        return f"Error: {resp.error or 'unknown'}"
    if not resp.issues:
        return "No issues found."

    lines = [f"Found {len(resp.issues)} issue(s):"]
    for issue in resp.issues:
        lines.append(
            f"  {issue.key} [{issue.state}] {issue.title}\n"
            f"    URL: {issue.url}"
        )
    return "\n".join(lines)


# ── Health endpoint (custom route) ───────────────────────────────────────

@mcp.custom_route("/health", methods=["GET"])
async def health(request: Request) -> JSONResponse:
    return JSONResponse({"status": "ok", "service": "jervis-mcp"})


# ── Accept header fix middleware ──────────────────────────────────────────
# Claude Code doesn't send both Accept types that FastMCP requires.
# This middleware ensures the Accept header always includes both.

from starlette.middleware import Middleware
from starlette.types import ASGIApp, Receive, Scope, Send


class AcceptHeaderFixMiddleware:
    """Ensures Accept header includes both application/json and text/event-stream."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            headers = dict(scope.get("headers", []))
            accept = headers.get(b"accept", b"").decode()
            needs_fix = (
                "application/json" not in accept
                or "text/event-stream" not in accept
            )
            if needs_fix:
                fixed = "application/json, text/event-stream"
                new_headers = [
                    (k, v) for k, v in scope["headers"] if k != b"accept"
                ]
                new_headers.append((b"accept", fixed.encode()))
                scope = dict(scope, headers=new_headers)
        await self.app(scope, receive, send)


# ── ASGI app for uvicorn ─────────────────────────────────────────────────
# Combines OAuth 2.1 endpoints with FastMCP app using Starlette routing.

from starlette.applications import Starlette
from starlette.routing import Route, Mount
from app.oauth_provider import oauth_routes

_mcp_app = mcp.http_app(path="/mcp", stateless_http=True)

# ── Coding Agent Communication Tools ──────────────────────────────────────
# Tools for coding agents to communicate with JERVIS and the user.


@mcp.tool
async def ask_jervis(
    question: str,
    priority: str = "question",
    context: str = "",
    task_id: str = "",
    client_id: str = "",
    project_id: str = "",
) -> str:
    """Ask JERVIS a question when you need a decision or information.

    JERVIS will first search KB for an answer. If found, returns immediately.
    If not found, escalates to the user and waits for a response.

    Args:
        question: The question you need answered
        priority: "blocking" (wait for user, urgent push), "question" (wait, normal push), "info" (don't wait)
        context: Additional context about what you're working on
        task_id: Your current task ID (for tracking)
        client_id: Client scope
        project_id: Project scope
    """
    db = await get_db()

    # 1. Search KB for auto-answer
    try:
        from jervis_contracts import kb_client

        results = await kb_client.retrieve(
            caller="service-mcp.ask_jervis",
            query=question,
            client_id=client_id or "",
            project_id=project_id or "",
            max_results=3,
            min_confidence=0.7,
            expand_graph=True,
            timeout=30.0,
        )
        if results:
            top_score = results[0].get("score", 0)
            if top_score > 0.75:
                answer_parts = []
                for r in results[:3]:
                    text = (r.get("content", "") or "")[:500]
                    if text:
                        answer_parts.append(text)
                if answer_parts:
                    return f"[KB Auto-Answer (confidence: {top_score:.2f})]\n\n" + "\n---\n".join(answer_parts)
    except Exception as e:
        logger.warning("ask_jervis KB search failed: %s", e)

    # 2. No KB answer — submit to pending questions for user
    from bson import ObjectId as BsonObjectId

    question_id = BsonObjectId()
    now = datetime.now(timezone.utc)
    await db["pending_agent_questions"].insert_one({
        "_id": question_id,
        "taskId": task_id,
        "agentType": "coding",
        "question": question,
        "context": context,
        "priority": priority.upper(),
        "clientId": client_id or None,
        "projectId": project_id or None,
        "state": "PENDING",
        "createdAt": now,
        "expiresAt": now + timedelta(hours=24),
    })

    if priority == "info":
        return f"Question submitted (id={question_id}). Continuing without waiting."

    # 3. Wait for user answer (poll with timeout)
    timeout_s = 3600 if priority == "blocking" else 1800  # 1h for blocking, 30min for question
    poll_interval = 5
    elapsed = 0
    while elapsed < timeout_s:
        import asyncio
        await asyncio.sleep(poll_interval)
        elapsed += poll_interval

        doc = await db["pending_agent_questions"].find_one({"_id": question_id})
        if doc and doc.get("state") == "ANSWERED":
            answer = doc.get("answer", "")
            return f"[User Answer]\n\n{answer}"
        if doc and doc.get("state") == "EXPIRED":
            return "[Timeout] No answer received. Proceeding with best judgment."

    # Timeout — mark as expired
    await db["pending_agent_questions"].update_one(
        {"_id": question_id},
        {"$set": {"state": "EXPIRED"}},
    )
    return "[Timeout] No answer received after waiting. Proceeding with best judgment."


@mcp.tool
async def report_done(
    task_id: str,
    result_summary: str,
    suggested_next_steps: str = "",
    client_id: str = "",
    project_id: str = "",
) -> str:
    """Report that your current task is complete and suggest next steps.

    JERVIS will check KB for conventions about what to do next.
    If a convention exists (e.g., "after PR, run e2e tests"), JERVIS may
    auto-dispatch the next task or ask the user.

    Args:
        task_id: The task ID you completed
        result_summary: Brief summary of what you did and the result
        suggested_next_steps: Your suggestion for what should happen next
        client_id: Client scope
        project_id: Project scope
    """
    db = await get_db()

    # 1. Search KB for post-task conventions
    convention_hint = ""
    try:
        from jervis_contracts import kb_client

        results = await kb_client.retrieve(
            caller="service-mcp.report_done",
            query="convention: what to do after completing a task in this project",
            client_id=client_id or "",
            project_id=project_id or "",
            max_results=2,
            min_confidence=0.7,
            timeout=15.0,
        )
        if results:
            convention_hint = (results[0].get("content", "") or "")[:300]
    except Exception as e:
        logger.warning("report_done KB search failed: %s", e)

    # 2. Push completion as BACKGROUND_RESULT into chat (not as pending question).
    # Completion notifications are informational — user sees them in chat/Tasky.
    # Only actual blocking questions (agent needs decision) go to pending_agent_questions.
    summary_text = result_summary
    if convention_hint:
        summary_text += f"\n\nKonvence z KB: {convention_hint}"
    if suggested_next_steps:
        summary_text += f"\n\nAgent navrhuje: {suggested_next_steps}"

    try:
        from app.grpc_clients import server_task_api_stub
        from jervis.common import types_pb2
        from jervis.server import task_api_pb2
        from jervis_contracts.interceptors import prepare_context

        ctx = types_pb2.RequestContext()
        prepare_context(ctx)
        await server_task_api_stub().PushBackgroundResult(
            task_api_pb2.PushBackgroundResultRequest(
                ctx=ctx,
                task_id=task_id,
                task_title=f"Dokončeno: {result_summary[:80]}",
                summary=summary_text,
                success=True,
                client_id=client_id or "",
                project_id=project_id or "",
            ),
            timeout=10.0,
        )
    except Exception as e:
        logger.warning("report_done: Failed to push background result: %s", e)

    response = f"Completion reported. User will see it in chat."
    if convention_hint:
        response += f"\nKB convention found: {convention_hint[:200]}"
    return response


# Combined app: OAuth routes + MCP (as catch-all mount)
_combined_app = Starlette(
    routes=[
        *oauth_routes,
        Mount("/", app=_mcp_app),
    ],
    lifespan=_mcp_app.lifespan,
)
app = AcceptHeaderFixMiddleware(_combined_app)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=settings.host, port=settings.port)
