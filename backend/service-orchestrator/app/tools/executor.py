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
        elif tool_name == "joern_quick_scan":
            return await _execute_joern_quick_scan(
                scan_type=arguments.get("scan_type", "security"),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "git_branch_list":
            return await _execute_git_branch_list(
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_recent_commits":
            return await _execute_get_recent_commits(
                limit=arguments.get("limit", 10),
                branch=arguments.get("branch"),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_technology_stack":
            return await _execute_get_technology_stack(
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_repository_structure":
            return await _execute_get_repository_structure(
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "code_search":
            return await _execute_code_search(
                query=arguments.get("query", ""),
                language=arguments.get("language"),
                max_results=arguments.get("max_results", 5),
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


async def _execute_joern_quick_scan(
    scan_type: str = "security",
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Run Joern code analysis scan via KB service."""
    if not project_id:
        return "Error: project_id required for Joern scan (no project selected)."

    # Get workspace path from KB graph (repository node has workspacePath property)
    url = f"{settings.knowledgebase_url}/api/v1/graph/search"
    params = {
        "query": "",
        "nodeType": "repository",
        "clientId": client_id,
        "projectId": project_id,
        "limit": 1,
    }

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            repos = resp.json()

        if not repos:
            return "Error: No repository found in Knowledge Base for this project."

        workspace_path = repos[0].get("properties", {}).get("workspacePath")
        if not workspace_path:
            return "Error: Repository node missing workspacePath property. Ensure repository is indexed on shared PVC."

        # Call KB Joern scan endpoint
        scan_url = f"{settings.knowledgebase_url}/api/v1/joern/scan"
        payload = {
            "scanType": scan_type,
            "clientId": client_id,
            "projectId": project_id,
            "workspacePath": workspace_path,
        }

        async with httpx.AsyncClient(timeout=120.0) as client:  # Joern can take time
            resp = await client.post(scan_url, json=payload)
            resp.raise_for_status()
            result = resp.json()

    except httpx.TimeoutException:
        return f"Error: Joern scan timed out after 120s for {scan_type} scan."
    except httpx.HTTPStatusError as e:
        return f"Error: KB Joern endpoint returned HTTP {e.response.status_code} for {scan_type} scan."
    except Exception as e:
        return f"Error: Joern scan failed: {str(e)[:200]}"

    if result.get("status") != "success":
        warnings = result.get("warnings", "Unknown error")
        return f"Joern {scan_type} scan failed:\n{warnings}"

    lines = [f"=== Joern {scan_type.upper()} Scan Results ===\n"]
    output = result.get("output", "")
    if output:
        lines.append(output)
    else:
        lines.append("No findings.")

    warnings = result.get("warnings")
    if warnings:
        lines.append(f"\nWarnings:\n{warnings}")

    return "\n".join(lines)


async def _execute_git_branch_list(
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """List all git branches from KB graph."""
    url = f"{settings.knowledgebase_url}/api/v1/graph/search"
    params = {
        "query": "",
        "nodeType": "branch",
        "clientId": client_id,
        "limit": 100,
    }
    if project_id:
        params["projectId"] = project_id

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            branches = resp.json()
    except Exception as e:
        return f"Error fetching branches: {str(e)[:200]}"

    if not branches:
        return "No branches found in Knowledge Base for this project."

    lines = [f"## Git Branches ({len(branches)} total)\n"]
    for b in branches:
        label = b.get("label", "?")
        props = b.get("properties", {})
        is_default = props.get("isDefault", False)
        file_count = props.get("fileCount", 0)
        marker = " (default)" if is_default else ""
        lines.append(f"- {label}{marker} [{file_count} files]")

    return "\n".join(lines)


async def _execute_get_recent_commits(
    limit: int = 10,
    branch: str | None = None,
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Get recent commits from KB graph."""
    url = f"{settings.knowledgebase_url}/api/v1/graph/search"
    params = {
        "query": "",
        "nodeType": "commit",
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
            commits = resp.json()
    except Exception as e:
        return f"Error fetching commits: {str(e)[:200]}"

    if not commits:
        return "No commits found in Knowledge Base for this project."

    lines = [f"## Recent Commits ({len(commits)} shown)\n"]
    for c in commits:
        props = c.get("properties", {})
        hash_short = props.get("hash", "?")[:8]
        message = props.get("message", "No message")[:100]
        author = props.get("author", "Unknown")
        date = props.get("date", "")
        lines.append(f"- {hash_short} | {author} | {date}")
        lines.append(f"  {message}")
        lines.append("")

    return "\n".join(lines)


async def _execute_get_technology_stack(
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Get technology stack from repository metadata."""
    url = f"{settings.knowledgebase_url}/api/v1/graph/search"
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
    except Exception as e:
        return f"Error fetching technology stack: {str(e)[:200]}"

    if not repos:
        return "No repository information found in Knowledge Base."

    lines = ["## Technology Stack\n"]
    for repo in repos:
        label = repo.get("label", "?")
        props = repo.get("properties", {})
        tech = props.get("techStack", "")
        lines.append(f"### {label}")
        if tech:
            lines.append(f"Technologies: {tech}")
        else:
            lines.append("Technology stack not yet analyzed.")
        lines.append("")

    # Also get language breakdown from file nodes
    params["nodeType"] = "file"
    params["limit"] = 1000

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            files = resp.json()

        if files:
            languages = {}
            for f in files:
                lang = f.get("properties", {}).get("language", "Unknown")
                languages[lang] = languages.get(lang, 0) + 1

            lines.append("### Programming Languages:")
            sorted_langs = sorted(languages.items(), key=lambda x: -x[1])
            for lang, count in sorted_langs[:10]:
                lines.append(f"- {lang}: {count} files")
    except Exception:
        pass  # Language breakdown is optional

    return "\n".join(lines)


async def _execute_get_repository_structure(
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Get repository directory structure from KB."""
    url = f"{settings.knowledgebase_url}/api/v1/graph/search"
    params = {
        "query": "",
        "nodeType": "file",
        "clientId": client_id,
        "limit": 1000,
    }
    if project_id:
        params["projectId"] = project_id

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            files = resp.json()
    except Exception as e:
        return f"Error fetching repository structure: {str(e)[:200]}"

    if not files:
        return "No files found in Knowledge Base for this project."

    # Group files by top-level directory
    dir_structure = {}
    for f in files:
        file_path = f.get("label", "")
        if "/" in file_path:
            top_dir = file_path.split("/")[0]
        else:
            top_dir = "(root)"

        if top_dir not in dir_structure:
            dir_structure[top_dir] = {"count": 0, "languages": set()}

        dir_structure[top_dir]["count"] += 1
        lang = f.get("properties", {}).get("language")
        if lang:
            dir_structure[top_dir]["languages"].add(lang)

    lines = [f"## Repository Structure ({len(files)} files total)\n"]
    sorted_dirs = sorted(dir_structure.items(), key=lambda x: -x[1]["count"])
    for dir_name, info in sorted_dirs[:20]:  # Show top 20 directories
        langs = ", ".join(sorted(info["languages"]))
        lines.append(f"### {dir_name}/ ({info['count']} files)")
        if langs:
            lines.append(f"Languages: {langs}")
        lines.append("")

    return "\n".join(lines)


async def _execute_code_search(
    query: str,
    language: str | None = None,
    max_results: int = 5,
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Search for code using semantic KB search with language filter."""
    if not query.strip():
        return "Error: Empty code search query."

    url = f"{settings.knowledgebase_url}/api/v1/retrieve"
    payload = {
        "query": query,
        "clientId": client_id,
        "projectId": project_id,
        "maxResults": max_results * 2,  # Get more, then filter
        "minConfidence": 0.5,
        "expandGraph": False,  # Code search doesn't need graph expansion
    }

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_KB_SEARCH) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
    except httpx.TimeoutException:
        return f"Error: Code search timed out after {_TIMEOUT_KB_SEARCH}s for query: {query}"
    except httpx.HTTPStatusError as e:
        return f"Error: Knowledge Base returned HTTP {e.response.status_code} for query: {query}"
    except Exception as e:
        return f"Error: Code search failed: {str(e)[:200]}"

    items = data.get("items", [])

    # Filter by language if specified
    if language:
        filtered = []
        for item in items:
            # Check if sourceUrn contains language hint or metadata
            source = item.get("sourceUrn", "").lower()
            if language.lower() in source:
                filtered.append(item)
        items = filtered

    if not items:
        lang_note = f" (language: {language})" if language else ""
        return f"No code found for query: {query}{lang_note}"

    lines = [f"## Code Search Results for: {query}\n"]
    if language:
        lines[0] = f"## Code Search Results for: {query} (language: {language})\n"

    for i, item in enumerate(items[:max_results], 1):
        source = item.get("sourceUrn", "unknown")
        content = item.get("content", "")[:500]
        score = item.get("score", 0)
        kind = item.get("kind", "")
        lines.append(f"### Result {i} (score: {score:.2f}, kind: {kind})")
        lines.append(f"Source: {source}")
        if content:
            lines.append(f"```\n{content}\n```")
        lines.append("")

    return "\n".join(lines)
