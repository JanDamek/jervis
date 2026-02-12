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
        tool_name: Name of the tool to execute.
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
        elif tool_name == "get_indexed_items":
            return await _execute_get_indexed_items(
                item_type=arguments.get("item_type", "all"),
                limit=arguments.get("limit", 10),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_kb_stats":
            return await _execute_get_kb_stats(
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "list_project_files":
            return await _execute_list_project_files(
                branch=arguments.get("branch"),
                file_pattern=arguments.get("file_pattern"),
                limit=arguments.get("limit", 50),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_repository_info":
            return await _execute_get_repository_info(
                client_id=client_id,
                project_id=project_id,
            )
        else:
            return f"Error: Unknown tool '{tool_name}'."
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


async def _execute_get_indexed_items(
    item_type: str = "all",
    limit: int = 10,
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Get indexed items summary from KB via GET /api/v1/chunks/by-kind."""
    url = f"{settings.knowledgebase_url}/api/v1/chunks/by-kind"
    payload = {
        "clientId": client_id,
        "projectId": project_id,
        "limit": limit,
    }

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
    except Exception as e:
        return f"Error fetching indexed items: {str(e)[:200]}"

    if not data:
        return "No indexed items found in Knowledge Base for this project."

    lines = ["## Indexed Items Summary\n"]
    for kind, items in data.items():
        count = len(items)
        lines.append(f"### {kind.upper()}: {count} items")
        for item in items[:5]:  # Show first 5 examples
            source = item.get("sourceUrn", "?")
            lines.append(f"  - {source}")
        if count > 5:
            lines.append(f"  ... and {count - 5} more")
        lines.append("")

    return "\n".join(lines)


async def _execute_get_kb_stats(
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Get KB statistics via graph search for various node types."""
    url = f"{settings.knowledgebase_url}/api/v1/graph/search"

    stats = {}
    node_types = ["repository", "branch", "file", "class", "function", "commit"]

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            for node_type in node_types:
                params = {
                    "query": "",
                    "nodeType": node_type,
                    "clientId": client_id,
                    "limit": 100,
                }
                if project_id:
                    params["projectId"] = project_id

                resp = await client.get(url, params=params)
                resp.raise_for_status()
                nodes = resp.json()
                stats[node_type] = len(nodes)
    except Exception as e:
        return f"Error fetching KB stats: {str(e)[:200]}"

    lines = ["## Knowledge Base Statistics\n"]
    lines.append(f"Repositories: {stats.get('repository', 0)}")
    lines.append(f"Branches: {stats.get('branch', 0)}")
    lines.append(f"Files: {stats.get('file', 0)}")
    lines.append(f"Classes: {stats.get('class', 0)}")
    lines.append(f"Functions: {stats.get('function', 0)}")
    lines.append(f"Commits: {stats.get('commit', 0)}")

    return "\n".join(lines)


async def _execute_list_project_files(
    branch: str | None = None,
    file_pattern: str | None = None,
    limit: int = 50,
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """List files from KB graph."""
    url = f"{settings.knowledgebase_url}/api/v1/graph/search"
    params = {
        "query": file_pattern or "",
        "nodeType": "file",
        "clientId": client_id,
        "limit": limit,
    }
    if project_id:
        params["projectId"] = project_id
    if branch:
        params["branchName"] = branch

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            files = resp.json()
    except Exception as e:
        return f"Error listing files: {str(e)[:200]}"

    if not files:
        return "No files found in Knowledge Base for this project."

    lines = [f"## Project Files ({len(files)} total)\n"]
    for f in files:
        label = f.get("label", "?")
        props = f.get("properties", {})
        lang = props.get("language", "")
        branch_name = props.get("branchName", "")
        line = f"- {label}"
        if lang:
            line += f" ({lang})"
        if branch_name and not branch:  # Only show branch if not filtering by it
            line += f" [branch: {branch_name}]"
        lines.append(line)

    return "\n".join(lines)


async def _execute_get_repository_info(
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Get repository overview from KB graph."""
    url = f"{settings.knowledgebase_url}/api/v1/graph/search"

    # Get repositories
    params = {
        "query": "",
        "nodeType": "repository",
        "clientId": client_id,
        "limit": 10,
    }
    if project_id:
        params["projectId"] = project_id

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            repos = resp.json()

            # Get branches
            params["nodeType"] = "branch"
            params["limit"] = 20
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            branches = resp.json()
    except Exception as e:
        return f"Error fetching repository info: {str(e)[:200]}"

    if not repos:
        return "No repository information found in Knowledge Base."

    lines = ["## Repository Information\n"]
    for repo in repos:
        label = repo.get("label", "?")
        props = repo.get("properties", {})
        tech = props.get("techStack", "")
        default_br = props.get("defaultBranch", "")
        lines.append(f"### {label}")
        if tech:
            lines.append(f"Technology: {tech}")
        if default_br:
            lines.append(f"Default branch: {default_br}")
        lines.append("")

    if branches:
        lines.append("### Branches:")
        for b in branches:
            label = b.get("label", "?")
            props = b.get("properties", {})
            is_default = props.get("isDefault", False)
            file_count = props.get("fileCount", 0)
            marker = " (default)" if is_default else ""
            lines.append(f"- {label}{marker} [{file_count} files]")

    return "\n".join(lines)
