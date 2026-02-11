"""Tool executor for the respond node's agentic loop.

Executes tool calls returned by the LLM and returns results as strings.
Never raises exceptions — errors are returned as descriptive strings so
the LLM can decide how to proceed.
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

_TIMEOUT_WEB_SEARCH = 15.0  # seconds
_TIMEOUT_KB_SEARCH = 10.0   # seconds


async def execute_tool(
    tool_name: str,
    arguments: dict,
    client_id: str,
    project_id: str | None,
) -> str:
    """Execute a tool call and return the result as a string.

    Args:
        tool_name: Name of the tool to execute ("web_search" or "kb_search").
        arguments: Parsed JSON arguments from the LLM's tool_call.
        client_id: Tenant client ID for KB scoping.
        project_id: Tenant project ID for KB scoping.

    Returns:
        Formatted result string (never raises).
    """
    try:
        if tool_name == "web_search":
            return await _execute_web_search(
                query=arguments.get("query", ""),
                max_results=arguments.get("max_results", 5),
            )
        elif tool_name == "kb_search":
            return await _execute_kb_search(
                query=arguments.get("query", ""),
                max_results=arguments.get("max_results", 5),
                client_id=client_id,
                project_id=project_id,
            )
        else:
            return f"Error: Unknown tool '{tool_name}'. Available tools: web_search, kb_search."
    except Exception as e:
        logger.exception("Tool execution failed: %s", tool_name)
        return f"Error executing {tool_name}: {str(e)[:300]}"


async def _execute_web_search(query: str, max_results: int = 5) -> str:
    """Search the internet via SearXNG.

    Calls GET {searxng_url}/search?q=...&format=json and formats top results.
    """
    if not query.strip():
        return "Error: Empty search query."

    url = f"{settings.searxng_url}/search"
    params = {
        "q": query,
        "format": "json",
        "engines": "google,bing",
        "pageno": 1,
    }

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_WEB_SEARCH) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            data = resp.json()
    except httpx.TimeoutException:
        return f"Error: Web search timed out after {_TIMEOUT_WEB_SEARCH}s for query: {query}"
    except httpx.HTTPStatusError as e:
        return f"Error: SearXNG returned HTTP {e.response.status_code} for query: {query}"
    except Exception as e:
        return f"Error: Web search failed: {str(e)[:200]}"

    results = data.get("results", [])
    if not results:
        return f"No web results found for: {query}"

    lines = [f"Web search results for: {query}\n"]
    for i, r in enumerate(results[:max_results], 1):
        title = r.get("title", "Untitled")
        url_str = r.get("url", "")
        content = r.get("content", "")[:400]
        lines.append(f"## Result {i}: {title}")
        lines.append(f"URL: {url_str}")
        if content:
            lines.append(content)
        lines.append("")

    return "\n".join(lines)


async def _execute_kb_search(
    query: str,
    max_results: int = 5,
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Search the Knowledge Base via POST /api/v1/retrieve.

    Reuses the same pattern as app/kb/prefetch.py.
    """
    if not query.strip():
        return "Error: Empty KB search query."

    url = f"{settings.knowledgebase_url}/api/v1/retrieve"
    payload = {
        "query": query,
        "clientId": client_id,
        "projectId": project_id,
        "maxResults": max_results,
        "minConfidence": 0.5,
        "expandGraph": True,
    }

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_KB_SEARCH) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
    except httpx.TimeoutException:
        return f"Error: KB search timed out after {_TIMEOUT_KB_SEARCH}s for query: {query}"
    except httpx.HTTPStatusError as e:
        return f"Error: Knowledge Base returned HTTP {e.response.status_code} for query: {query}"
    except Exception as e:
        return f"Error: KB search failed: {str(e)[:200]}"

    items = data.get("items", [])
    if not items:
        return f"No Knowledge Base results found for: {query}"

    lines = [f"Knowledge Base results for: {query}\n"]
    for i, item in enumerate(items[:max_results], 1):
        source = item.get("sourceUrn", "unknown")
        content = item.get("content", "")[:500]
        score = item.get("score", 0)
        kind = item.get("kind", "")
        lines.append(f"## Result {i} (score: {score:.2f}, kind: {kind})")
        lines.append(f"Source: {source}")
        if content:
            lines.append(content)
        lines.append("")

    return "\n".join(lines)
