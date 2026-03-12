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

    # Priority 1 = ORCHESTRATOR_EMBEDDING (co-located with CRITICAL on GPU)
    headers = {"X-Ollama-Priority": "0"}
    payload = {
        "query": query,
        "clientId": cid,
        "projectId": pid,
        "maxResults": max_results,
        "minConfidence": min_confidence,
        "expandGraph": True,
    }
    if group_id:
        payload["groupId"] = group_id
    async with httpx.AsyncClient(timeout=120, headers=headers) as client:
        resp = await client.post(
            f"{settings.knowledgebase_url}/api/v1/retrieve",
            json=payload,
        )
        resp.raise_for_status()
        data = resp.json()
        results = []
        for item in data.get("items", []):
            conf = item.get("confidence", 0)
            source = item.get("sourceUrn", "?")
            content = item.get("content", "")[:500]
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

    headers = {"X-Ollama-Priority": "0"}
    payload = {
        "query": query,
        "clientId": cid,
        "projectId": pid,
        "maxResults": max_results,
    }
    if group_id:
        payload["groupId"] = group_id
    async with httpx.AsyncClient(timeout=120, headers=headers) as client:
        resp = await client.post(
            f"{settings.knowledgebase_url}/api/v1/retrieve/simple",
            json=payload,
        )
        resp.raise_for_status()
        data = resp.json()
        results = []
        for item in data.get("items", []):
            source = item.get("sourceUrn", "?")
            content = item.get("content", "")[:500]
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
    cid = client_id or settings.default_client_id
    async with httpx.AsyncClient(timeout=120) as client:
        resp = await client.get(
            f"{settings.knowledgebase_url}/api/v1/alias/resolve",
            params={"alias": alias, "clientId": cid},
        )
        resp.raise_for_status()
        data = resp.json()
        return f"'{data.get('alias')}' -> canonical: '{data.get('canonical')}'"


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
    file_path: str = "",
    file_content: str = "",
    file_name: str = "",
    client_id: str = "",
    project_id: str = "",
    title: str = "",
    description: str = "",
    category: str = "OTHER",
    tags: str = "",
) -> str:
    """Upload a document to the Knowledge Base.

    Stores the file on shared FS, extracts text (via VLM/Tika), and indexes
    the content into RAG + knowledge graph. Supports PDF, DOCX, TXT, images, etc.

    Two ways to provide the file:
      A) file_path — absolute path on Jervis PVC (server-side upload)
      B) file_content (base64) + file_name — direct upload from client
         Use this when a user attaches a file in the chat.

    Args:
        file_path: Absolute path to the file on local disk or shared PVC
        file_content: Base64-encoded file content (alternative to file_path)
        file_name: Original filename when using file_content (e.g. "smlouva.pdf")
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

    # Resolve file bytes from either file_path or file_content+file_name
    if file_path:
        if not os.path.exists(file_path):
            return f"Error: File not found: {file_path}"
        filename = os.path.basename(file_path)
        mime_type, _ = mimetypes.guess_type(file_path)
        mime_type = mime_type or "application/octet-stream"
        with open(file_path, "rb") as f:
            file_bytes = f.read()
    elif file_content and file_name:
        # Validate filename extension (whitelist)
        allowed_extensions = {
            '.pdf', '.docx', '.doc', '.xlsx', '.xls',
            '.txt', '.csv', '.png', '.jpg', '.jpeg',
            '.md', '.json', '.xml', '.html',
        }
        ext = os.path.splitext(file_name)[1].lower()
        if ext not in allowed_extensions:
            return f"Error: File extension '{ext}' not allowed. Supported: {', '.join(sorted(allowed_extensions))}"

        try:
            file_bytes = base64.b64decode(file_content)
        except Exception as e:
            return f"Error: Invalid base64 content: {e}"

        max_size = 20 * 1024 * 1024  # 20 MB
        if len(file_bytes) > max_size:
            return f"Error: File too large ({len(file_bytes)} bytes). Max: {max_size} bytes (20 MB)"

        filename = file_name
        mime_type, _ = mimetypes.guess_type(file_name)
        mime_type = mime_type or "application/octet-stream"
    else:
        return "Error: Provide either file_path OR (file_content + file_name)"

    # Upload to KB service
    async with httpx.AsyncClient(timeout=300) as client:
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
        f"Document uploaded successfully.\n"
        f"  ID: {doc_id}\n"
        f"  Filename: {filename}\n"
        f"  State: {state}\n"
        f"  Size: {len(file_bytes)} bytes"
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


# ── MongoDB Tools ────────────────────────────────────────────────────────

from datetime import datetime
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
        state: Filter by state (NEW, PENDING, QUALIFYING, QUEUED, PROCESSING, DONE, FAILED)
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
    from bson import ObjectId as BsonObjectId

    db = await get_db()
    task_id = BsonObjectId()
    correlation_id = str(BsonObjectId())
    now = datetime.now(tz=None)

    task_doc = {
        "_id": task_id,
        "type": "USER_INPUT_PROCESSING",
        "taskName": task_name or query[:60],
        "content": query,
        "projectId": BsonObjectId(project_id) if project_id else None,
        "clientId": BsonObjectId(client_id),
        "createdAt": now,
        "state": "QUEUED",
        "processingMode": processing_mode,
        "correlationId": correlation_id,
        "sourceUrn": "chat:coding-agent",
        "qualificationRetries": 0,
        "dispatchRetryCount": 0,
    }

    await db["tasks"].insert_one(task_doc)
    return f"Task created: id={task_id}, state=QUEUED, mode={processing_mode}"


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

    Creates a SCHEDULED_TASK that BackgroundEngine will pick up at the scheduled time.
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

    db = await get_db()
    task_id = str(uuid.uuid4())[:24]
    now = datetime.now(tz=None)

    if scheduled_at_iso:
        try:
            scheduled_at = datetime.fromisoformat(scheduled_at_iso.replace("Z", "+00:00"))
            if scheduled_at.tzinfo:
                scheduled_at = scheduled_at.replace(tzinfo=None)
        except ValueError:
            return f"Error: Invalid ISO datetime format: {scheduled_at_iso}"
    else:
        scheduled_at = now

    task_doc = {
        "_id": task_id,
        "type": "SCHEDULED_TASK",
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
    """List scheduled tasks (type=SCHEDULED_TASK) with optional filters.

    Args:
        client_id: Filter by client ID
        project_id: Filter by project ID
        include_done: Include completed/failed tasks (default: only active)
        limit: Maximum results (default 20)
    """
    db = await get_db()
    query: dict = {"type": "SCHEDULED_TASK"}
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

    Only works on tasks that haven't started processing yet (state in NEW, INDEXING, QUALIFYING).

    Args:
        task_id: The task ID to cancel
    """
    db = await get_db()
    result = await db["tasks"].update_one(
        {
            "_id": task_id,
            "type": "SCHEDULED_TASK",
            "state": {"$in": ["NEW", "INDEXING", "QUALIFYING", "QUEUED"]},
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
    if doc.get("type") != "SCHEDULED_TASK":
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
    params = {}
    if client_id:
        params["clientId"] = client_id
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.kotlin_server_url}/internal/environments",
            params=params,
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        envs = resp.json()
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
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()

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
    body = {
        "clientId": client_id,
        "name": name,
        "tier": tier.upper(),
        "description": description or None,
        "agentInstructions": agent_instructions or None,
        "storageSizeGi": storage_size_gi,
    }
    if namespace:
        body["namespace"] = namespace

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments",
            json=body,
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
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
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.delete(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
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
    body: dict = {
        "name": name,
        "type": component_type.upper(),
    }
    if image:
        body["image"] = image
    if version:
        body["version"] = version
    try:
        extra_env = json.loads(env_vars) if env_vars and env_vars != "{}" else None
        if extra_env:
            body["envVars"] = extra_env
    except json.JSONDecodeError:
        return f"Error: Invalid JSON for env_vars: {env_vars}"
    if source_repo:
        body["sourceRepo"] = source_repo
    if source_branch:
        body["sourceBranch"] = source_branch
    if dockerfile_path:
        body["dockerfilePath"] = dockerfile_path

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/components",
            json=body,
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
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
    body: dict = {}
    if image:
        body["image"] = image
    try:
        extra_env = json.loads(env_vars) if env_vars and env_vars != "{}" else None
        if extra_env:
            body["envVars"] = extra_env
    except json.JSONDecodeError:
        return f"Error: Invalid JSON for env_vars: {env_vars}"
    if cpu_limit:
        body["cpuLimit"] = cpu_limit
    if memory_limit:
        body["memoryLimit"] = memory_limit
    if source_repo:
        body["sourceRepo"] = source_repo
    if source_branch:
        body["sourceBranch"] = source_branch
    if dockerfile_path:
        body["dockerfilePath"] = dockerfile_path

    if not body:
        return "Error: No configuration changes provided."

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.put(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/components/{component_name}",
            json=body,
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        return f"Component '{component_name}' updated."


@mcp.tool
async def environment_deploy(environment_id: str) -> str:
    """Provision/deploy an environment to Kubernetes.

    Creates K8s namespace, PVC, and deploys infrastructure components.
    Environment must be in PENDING, STOPPED, or ERROR state.

    Args:
        environment_id: The environment ID to deploy
    """
    async with httpx.AsyncClient(timeout=120) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/deploy",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
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
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/stop",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
        return f"Environment stopped: {env['name']} (state={env['state']})"


@mcp.tool
async def environment_status(environment_id: str) -> str:
    """Get deployment status of an environment and its components.

    Shows per-component readiness, replica counts, and any error messages.

    Args:
        environment_id: The environment ID
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/status",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        status = resp.json()

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
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/sync",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
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
    body: dict = {"newName": new_name}
    if new_namespace:
        body["newNamespace"] = new_namespace
    if new_tier:
        body["newTier"] = new_tier.upper()
    if target_client_id:
        body["targetClientId"] = target_client_id
    if target_group_id:
        body["targetGroupId"] = target_group_id
    if target_project_id:
        body["targetProjectId"] = target_project_id

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/clone",
            json=body,
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
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
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/property-mappings",
            json={
                "projectComponentId": project_component,
                "propertyName": property_name,
                "targetComponentId": target_component,
                "valueTemplate": value_template,
            },
        )
        if resp.status_code == 409:
            return f"Mapping for '{property_name}' already exists."
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
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
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/property-mappings/auto-suggest",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
        added = resp.headers.get("X-Mappings-Added", "?")
        total = len(env.get("propertyMappings", []))
        return (
            f"Auto-suggested mappings for '{env['name']}'.\n"
            f"Added: {added}, Total: {total}.\n"
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
    async with httpx.AsyncClient(timeout=60) as client:
        payload = {"namespace": namespace, "clientId": client_id, "tier": tier}
        if name:
            payload["name"] = name
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments/discover",
            json=payload,
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
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
    async with httpx.AsyncClient(timeout=120) as client:
        payload: dict = {"newName": new_name}
        if new_namespace:
            payload["newNamespace"] = new_namespace
        if new_tier:
            payload["newTier"] = new_tier
        if target_client_id:
            payload["targetClientId"] = target_client_id
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/replicate",
            json=payload,
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
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
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/environments/{environment_id}/sync-from-k8s",
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        env = resp.json()
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
    base = f"{settings.kotlin_server_url}/internal/environment/{namespace}"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(f"{base}/resources", params={"type": resource_type})
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        if not data.get("ok"):
            return f"Error: {data.get('error', 'unknown')}"

        resources = data.get("data", {})
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
    base = f"{settings.kotlin_server_url}/internal/environment/{namespace}"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{base}/pods/{pod_name}/logs",
            params={"tail": min(tail_lines, 1000)},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        return resp.text


@mcp.tool
async def get_deployment_status(namespace: str, name: str) -> str:
    """Get detailed status of a deployment including conditions and recent events.

    Args:
        namespace: K8s namespace
        name: Deployment name
    """
    base = f"{settings.kotlin_server_url}/internal/environment/{namespace}"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(f"{base}/deployments/{name}")
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        if not data.get("ok"):
            return f"Error: {data.get('error', 'unknown')}"

        d = data.get("data", {})
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
    base = f"{settings.kotlin_server_url}/internal/environment/{namespace}"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{base}/deployments/{name}/scale",
            json={"replicas": replicas},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return data.get("message", "Scaled successfully") if data.get("ok") else f"Error: {data.get('error')}"


@mcp.tool
async def restart_deployment(namespace: str, name: str) -> str:
    """Trigger a rolling restart of a deployment.

    Args:
        namespace: K8s namespace
        name: Deployment name
    """
    base = f"{settings.kotlin_server_url}/internal/environment/{namespace}"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(f"{base}/deployments/{name}/restart")
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return data.get("message", "Restart triggered") if data.get("ok") else f"Error: {data.get('error')}"


@mcp.tool
async def get_namespace_status(namespace: str) -> str:
    """Get overall health status of a K8s namespace.

    Returns pod counts, deployment readiness, and identifies any crashing pods.

    Args:
        namespace: K8s namespace to inspect
    """
    base = f"{settings.kotlin_server_url}/internal/environment/{namespace}"
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(f"{base}/status")
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        if not data.get("ok"):
            return f"Error: {data.get('error', 'unknown')}"

        s = data.get("data", {})
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

    Args:
        name: Client name (must be unique)
        description: Optional description
    """
    body = {"name": name}
    if description:
        body["description"] = description

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/clients",
            json=body,
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return f"Client created: {data.get('name', name)} (id={data.get('id', '?')})"


@mcp.tool
async def create_project(
    client_id: str,
    name: str,
    description: str = "",
) -> str:
    """Create a new project within a client.

    Args:
        client_id: ID of the client that owns this project
        name: Project name
        description: Optional project description
    """
    body = {"clientId": client_id, "name": name}
    if description:
        body["description"] = description

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/projects",
            json=body,
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return (
            f"Project created: {data.get('name', name)} "
            f"(id={data.get('id', '?')}, clientId={client_id})"
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
        provider: Service provider: GITHUB, GITLAB, ATLASSIAN, AZURE_DEVOPS, GENERIC_EMAIL, etc.
        auth_type: Authentication type: NONE, BASIC, BEARER, OAUTH2
        base_url: API base URL (leave empty for cloud providers)
        bearer_token: Bearer/personal access token (for BEARER auth)
        is_cloud: Whether to use provider's default cloud URL
    """
    body: dict = {
        "name": name,
        "provider": provider,
        "protocol": "HTTP",
        "authType": auth_type,
        "isCloud": is_cloud,
    }
    if base_url:
        body["baseUrl"] = base_url
    if bearer_token:
        body["bearerToken"] = bearer_token

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/connections",
            json=body,
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return (
            f"Connection created: {data.get('name', name)} "
            f"(id={data.get('id', '?')}, provider={data.get('provider', provider)})"
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
    body: dict = {
        "clientId": client_id,
        "name": name,
        "isPrivate": is_private,
    }
    if description:
        body["description"] = description
    if connection_id:
        body["connectionId"] = connection_id

    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/git/repos",
            json=body,
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        return (
            f"Repository created: {data.get('fullName', name)}\n"
            f"  Clone URL: {data.get('cloneUrl', '?')}\n"
            f"  Web URL: {data.get('htmlUrl', '?')}\n"
            f"  Provider: {data.get('provider', '?')}"
        )


@mcp.tool
async def init_workspace(project_id: str) -> str:
    """Initialize (clone) the workspace for a project.

    Triggers an async git clone for the project's repository.
    The workspace will be available once cloning completes.

    Args:
        project_id: ID of the project to initialize workspace for
    """
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/git/init-workspace",
            json={"projectId": project_id},
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        if data.get("ok"):
            return f"Workspace initialization triggered for project {project_id}"
        return f"Error: {data.get('error', 'unknown')}"


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
    body: dict = {
        "clientId": client_id,
        "projectId": project_id,
        "title": title,
    }
    if description:
        body["description"] = description
    if labels:
        body["labels"] = [l.strip() for l in labels.split(",") if l.strip()]

    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/issues/create",
            json=body,
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        if data.get("ok"):
            return (
                f"Issue created: {data.get('key', '?')}\n"
                f"  URL: {data.get('url', '?')}"
            )
        return f"Error: {data.get('error', 'unknown')}"


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
    # Normalize issue key to include #
    key = issue_key if issue_key.startswith("#") else f"#{issue_key}"
    body: dict = {
        "clientId": client_id,
        "projectId": project_id,
        "issueKey": key,
        "comment": comment,
    }

    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{settings.kotlin_server_url}/internal/issues/comment",
            json=body,
        )
        if resp.status_code not in (200, 201):
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        if data.get("ok"):
            url_info = f"\n  URL: {data['url']}" if data.get("url") else ""
            return f"Comment added to issue {key}{url_info}"
        return f"Error: {data.get('error', 'unknown')}"


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
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.get(
            f"{settings.kotlin_server_url}/internal/issues/list",
            params={"clientId": client_id, "projectId": project_id},
        )
        if resp.status_code != 200:
            return f"Error ({resp.status_code}): {resp.text}"
        data = resp.json()
        if not data.get("ok"):
            return f"Error: {data.get('error', 'unknown')}"

        issues = data.get("issues", [])
        if not issues:
            return "No issues found."

        lines = [f"Found {len(issues)} issue(s):"]
        for issue in issues:
            lines.append(
                f"  {issue['key']} [{issue['state']}] {issue['title']}\n"
                f"    URL: {issue['url']}"
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
