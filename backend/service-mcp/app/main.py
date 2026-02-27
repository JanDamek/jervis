"""Jervis MCP Server – main entry point.

Exposes Jervis platform capabilities (KB, MongoDB, Orchestrator) as MCP tools
over Streamable HTTP transport with Bearer token authentication.

Designed to run:
  1. Inside K8s cluster (internal access for Claude Code agents)
  2. Behind public ingress at jervis-mcp.damek-soft.eu (Claude Desktop / cloud)

Priority: CRITICAL (same as orchestrator) – KB queries must not wait in queue.

Uses FastMCP for clean Streamable HTTP transport + BearerTokenAuth.
"""

from __future__ import annotations

import logging
import os

from fastmcp import FastMCP
from fastmcp.server.auth import StaticTokenVerifier
from starlette.requests import Request
from starlette.responses import JSONResponse

from app.config import settings
from app.db import close_db, get_db

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
)
logger = logging.getLogger("jervis-mcp")

# ── Auth setup ───────────────────────────────────────────────────────────

auth = None
tokens = list(settings.valid_tokens)
if tokens:
    token_map = {t: {"client_id": "jervis", "scopes": []} for t in tokens}
    auth = StaticTokenVerifier(tokens=token_map)
    logger.info("Bearer token auth enabled (%d tokens)", len(tokens))
else:
    logger.warning("No MCP_API_TOKENS set – running WITHOUT authentication!")

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
    scope: str = "auto",
    max_results: int = 10,
    min_confidence: float = 0.6,
) -> str:
    """Search the Knowledge Base using RAG + knowledge graph expansion.

    Combines vector search with graph traversal for comprehensive results.

    Args:
        query: Natural language search query
        client_id: Client ID (leave empty to use server default)
        project_id: Project ID (leave empty to use server default)
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
    async with httpx.AsyncClient(timeout=120, headers=headers) as client:
        resp = await client.post(
            f"{settings.knowledgebase_url}/api/v1/retrieve",
            json={
                "query": query,
                "clientId": cid,
                "projectId": pid,
                "maxResults": max_results,
                "minConfidence": min_confidence,
                "expandGraph": True,
            },
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
    max_results: int = 5,
) -> str:
    """Quick RAG-only search without graph expansion. Faster but less comprehensive.

    Args:
        query: Natural language search query
        client_id: Client ID (leave empty for default)
        project_id: Project ID (leave empty for default)
        max_results: Maximum results
    """
    cid = client_id or settings.default_client_id
    pid = project_id or settings.default_project_id or None

    headers = {"X-Ollama-Priority": "0"}
    async with httpx.AsyncClient(timeout=120, headers=headers) as client:
        resp = await client.post(
            f"{settings.knowledgebase_url}/api/v1/retrieve/simple",
            json={
                "query": query,
                "clientId": cid,
                "projectId": pid,
                "maxResults": max_results,
            },
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
    direction: str = "outbound",
    max_hops: int = 2,
) -> str:
    """Traverse the knowledge graph starting from a node.

    Args:
        start_node: Node key or label to start traversal from
        client_id: Client ID (leave empty for default)
        direction: "outbound", "inbound", or "any"
        max_hops: Maximum traversal depth (1-3 recommended)
    """
    cid = client_id or settings.default_client_id
    headers = {"X-Ollama-Priority": "0"}
    async with httpx.AsyncClient(timeout=120, headers=headers) as client:
        resp = await client.post(
            f"{settings.knowledgebase_url}/api/v1/traverse",
            json={
                "startNodeKey": start_node,
                "direction": direction,
                "maxDepth": max_hops,
                "clientId": cid,
            },
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
    node_type: str = "",
    limit: int = 20,
) -> str:
    """Search for nodes in the knowledge graph by label.

    Args:
        query: Search query for node labels
        client_id: Client ID (leave empty for default)
        node_type: Filter by node type (e.g., "jira_issue", "file", "class", "method", "commit")
        limit: Maximum number of results
    """
    cid = client_id or settings.default_client_id
    params: dict = {"query": query, "clientId": cid, "limit": limit}
    if node_type:
        params["nodeType"] = node_type
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
    kind: str = "finding",
    source_urn: str = "agent://claude-mcp",
    metadata: str = "{}",
) -> str:
    """Store new knowledge in the Knowledge Base.

    Use for findings, decisions, patterns, bugs, or conventions discovered during work.

    Args:
        content: The knowledge content to store
        client_id: Client ID (leave empty for default)
        project_id: Project ID (leave empty for default)
        kind: Type: "finding", "decision", "pattern", "bug", "convention"
        source_urn: Source identifier
        metadata: Additional metadata as JSON string
    """
    cid = client_id or settings.default_client_id
    pid = project_id or settings.default_project_id or None

    # Fire-and-forget: KB queues processing (embedding + extraction) in background
    headers = {"X-Ollama-Priority": "0"}
    async with httpx.AsyncClient(timeout=30, headers=headers) as client:
        resp = await client.post(
            f"{settings.knowledgebase_write_url}/api/v1/ingest-queue",
            json={
                "clientId": cid,
                "projectId": pid,
                "sourceUrn": source_urn,
                "kind": kind,
                "content": content,
                "metadata": json.loads(metadata) if metadata else {},
            },
        )
        resp.raise_for_status()
        return f"Queued for processing."


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
        state: Filter by state (NEW, PENDING, QUALIFYING, READY_FOR_GPU, PYTHON_ORCHESTRATING, DONE, FAILED)
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
    import uuid

    db = await get_db()
    task_id = str(uuid.uuid4())[:24]
    now = datetime.now(tz=None)

    task_doc = {
        "_id": task_id,
        "type": "USER_TASK",
        "taskName": task_name or query[:60],
        "content": query,
        "projectId": project_id or None,
        "clientId": client_id,
        "createdAt": now,
        "state": "NEW",
        "processingMode": processing_mode,
        "correlationId": str(uuid.uuid4()),
        "sourceUrn": "mcp://claude-desktop",
        "qualificationRetries": 0,
        "dispatchRetryCount": 0,
    }

    await db["tasks"].insert_one(task_doc)
    return f"Task created: id={task_id}, state=NEW, mode={processing_mode}"


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

    Only works on tasks that haven't started processing yet (state in NEW, READY_FOR_QUALIFICATION, QUALIFYING).

    Args:
        task_id: The task ID to cancel
    """
    db = await get_db()
    result = await db["tasks"].update_one(
        {
            "_id": task_id,
            "type": "SCHEDULED_TASK",
            "state": {"$in": ["NEW", "READY_FOR_QUALIFICATION", "QUALIFYING", "READY_FOR_GPU"]},
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
            lines.append(
                f"- {env['name']} (id={env['id']})\n"
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
        description: Optional description
        agent_instructions: Free-text instructions for agents about this environment
        storage_size_gi: PVC storage size in Gi (default 5)
    """
    body = {
        "clientId": client_id,
        "name": name,
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

_inner_app = mcp.http_app(path="/mcp", stateless_http=True)
app = AcceptHeaderFixMiddleware(_inner_app)


if __name__ == "__main__":
    mcp.run(transport="http", host=settings.host, port=settings.port)
