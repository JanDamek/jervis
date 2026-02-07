"""MCP Server for Jervis Knowledge Base.

Provides KB access to coding agents (primarily Claude Code) via MCP protocol.
Runs as stdio process – Claude Code natively supports MCP via .claude/mcp.json.

Tenant context is injected via environment variables:
    CLIENT_ID  – required, scopes data to client
    PROJECT_ID – optional, further scopes to project
    KB_URL     – Knowledge Base service URL

Scope hierarchy:
    global (clientId="") → client (clientId=X) → project (clientId=X, projectId=Y)
"""

from __future__ import annotations

import asyncio
import json
import logging
import os

import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Server("jervis-kb")

# Tenant context from environment
CLIENT_ID = os.environ.get("CLIENT_ID", "")
PROJECT_ID = os.environ.get("PROJECT_ID") or None
KB_URL = os.environ.get("KB_URL", "http://jervis-knowledgebase:8080")


def _resolve_scope(scope: str = "auto") -> tuple[str, str | None]:
    """Resolve tenant scope based on parameter or env defaults."""
    if scope == "global":
        return "", None
    elif scope == "client":
        return CLIENT_ID, None
    else:  # "auto" or "project"
        return CLIENT_ID, PROJECT_ID


@app.tool()
async def kb_search(
    query: str,
    scope: str = "auto",
    max_results: int = 10,
    min_confidence: float = 0.6,
) -> str:
    """Search the Knowledge Base for relevant information.

    Combines vector search (RAG) with knowledge graph expansion.
    Results are scoped to the current client and project.

    Args:
        query: Natural language search query
        scope: "auto" (client+project), "global", "client" (no project filter)
        max_results: Maximum number of results (default 10)
        min_confidence: Minimum confidence threshold (0.0-1.0)
    """
    client_id, project_id = _resolve_scope(scope)
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{KB_URL}/retrieve",
            json={
                "query": query,
                "clientId": client_id,
                "projectId": project_id,
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


@app.tool()
async def kb_search_simple(query: str, max_results: int = 5) -> str:
    """Quick RAG-only search without graph expansion. Faster but less comprehensive."""
    client_id, project_id = _resolve_scope("auto")
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.post(
            f"{KB_URL}/retrieve/simple",
            json={
                "query": query,
                "clientId": client_id,
                "projectId": project_id,
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


@app.tool()
async def kb_traverse(
    start_node: str,
    direction: str = "outbound",
    max_hops: int = 2,
) -> str:
    """Traverse the knowledge graph starting from a node.

    Args:
        start_node: Node key or label to start traversal from
        direction: "outbound", "inbound", or "any"
        max_hops: Maximum traversal depth (1-3 recommended)
    """
    client_id, project_id = _resolve_scope("auto")
    async with httpx.AsyncClient(timeout=20) as client:
        resp = await client.post(
            f"{KB_URL}/traverse",
            json={
                "startNodeKey": start_node,
                "direction": direction,
                "maxDepth": max_hops,
                "clientId": client_id,
                "projectId": project_id,
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


@app.tool()
async def kb_graph_search(
    query: str,
    node_type: str | None = None,
    limit: int = 20,
) -> str:
    """Search for nodes in the knowledge graph by label.

    Args:
        query: Search query for node labels
        node_type: Filter by node type (optional)
        limit: Maximum number of results
    """
    client_id, project_id = _resolve_scope("auto")
    params: dict = {"query": query, "clientId": client_id, "limit": limit}
    if project_id:
        params["projectId"] = project_id
    if node_type:
        params["nodeType"] = node_type
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.get(f"{KB_URL}/graph/search", params=params)
        resp.raise_for_status()
        nodes = resp.json()
        if not nodes:
            return f"No graph nodes matching '{query}'."
        return "\n".join(
            f"[{n.get('type', '?')}] {n.get('label', '?')} (key={n.get('key', '?')})"
            for n in nodes
        )


@app.tool()
async def kb_get_evidence(node_key: str) -> str:
    """Get RAG chunks that support a specific knowledge graph node.

    Args:
        node_key: The key of the graph node to get evidence for
    """
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.get(
            f"{KB_URL}/graph/node/{node_key}/evidence",
            params={"clientId": CLIENT_ID},
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


@app.tool()
async def kb_resolve_alias(alias: str) -> str:
    """Resolve an entity alias to its canonical key.

    Use this when you encounter different names for possibly the same entity.
    Example: 'UserSvc' -> 'UserService', 'auth module' -> 'authentication-service'

    Args:
        alias: The alias or alternate name to resolve
    """
    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.get(
            f"{KB_URL}/alias/resolve",
            params={"alias": alias, "clientId": CLIENT_ID},
        )
        resp.raise_for_status()
        data = resp.json()
        return f"'{data.get('alias')}' -> canonical: '{data.get('canonical')}'"


@app.tool()
async def kb_store(
    content: str,
    kind: str = "finding",
    source_urn: str = "agent://coding-agent",
    metadata: str = "{}",
) -> str:
    """Store new knowledge in the Knowledge Base.

    Use sparingly - only for genuinely useful findings discovered during coding.

    Args:
        content: The knowledge content to store
        kind: Type of knowledge ("finding", "decision", "pattern", "bug", "convention")
        source_urn: Source identifier (auto-set to agent URI)
        metadata: Additional metadata as JSON string
    """
    client_id, project_id = _resolve_scope("auto")
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{KB_URL}/ingest",
            json={
                "clientId": client_id,
                "projectId": project_id,
                "sourceUrn": source_urn,
                "kind": kind,
                "content": content,
                "metadata": json.loads(metadata) if metadata else {},
            },
        )
        resp.raise_for_status()
        data = resp.json()
        return f"Stored: {data.get('status', 'ok')} (chunks: {data.get('chunksCreated', '?')})"


# Entry point
async def main():
    async with stdio_server() as (read_stream, write_stream):
        await app.run(read_stream, write_stream)


if __name__ == "__main__":
    asyncio.run(main())
