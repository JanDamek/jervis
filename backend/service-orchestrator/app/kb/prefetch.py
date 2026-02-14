"""KB pre-fetch – queries Knowledge Base before agent launch.

Two main functions:
- prefetch_kb_context(): Task-specific context for coding agents (written to .jervis/kb-context.md)
- fetch_project_context(): Project-level overview for orchestrator's clarify/decompose nodes
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


async def prefetch_kb_context(
    task_description: str,
    client_id: str,
    project_id: str | None,
    files: list[str] | None = None,
    search_queries: list[str] | None = None,
) -> str:
    """Query KB and return context relevant for the coding task.

    Args:
        task_description: What the agent will do (used for file-specific queries and as fallback).
        client_id: Client ID for scoping.
        project_id: Project ID (optional).
        files: Specific files that will be modified.
        search_queries: Optional list of queries to use instead of task_description.
            If provided, these queries are tried in order (first that returns results wins
            for the main task query), and all are used for conventions/architecture if needed.

    Returns:
        Markdown string with KB context, or empty string if nothing found.
    """
    kb_url = f"{settings.knowledgebase_url}/api/v1"
    sections: list[str] = []

    # KB operations can take long due to embeddings and graph traversal
    async with httpx.AsyncClient(timeout=120.0) as http:
        # 1. Relevant knowledge for the task
        # Use search_queries if provided, otherwise fall back to task_description
        task_results: list[dict] = []
        queries_to_try = search_queries if search_queries else [task_description]
        
        for query in queries_to_try:
            resp = await http.post(
                f"{kb_url}/retrieve",
                json={
                    "query": query,
                    "clientId": client_id,
                    "projectId": project_id,
                    "maxResults": 5,
                    "minConfidence": 0.7,
                    "expandGraph": True,
                },
            )
            resp.raise_for_status()
            results = resp.json().get("items", [])
            if results:
                task_results.extend(results)
                # Log which query succeeded
                logger.info("KB task query succeeded: '%s' (%d results)", query, len(results))
                # If we have multiple queries, we could break after first success
                # but let's continue to gather more context from other queries
            else:
                logger.debug("KB task query returned no results: '%s'", query)
        
        if task_results:
            # Deduplicate by sourceUrn
            seen = set()
            unique_results = []
            for item in task_results:
                urn = item.get("sourceUrn")
                if urn and urn not in seen:
                    seen.add(urn)
                    unique_results.append(item)
            
            sections.append("## Relevant Knowledge")
            for item in unique_results[:5]:  # Limit to top 5 unique
                source = item.get("sourceUrn", "?")
                content = item.get("content", "")[:300]
                sections.append(f"- **{source}**: {content}")

        # 2. Coding conventions for the client
        # Try multiple queries if search_queries provided, otherwise use default
        convention_queries = search_queries if search_queries else ["coding conventions style guide rules"]
        for query in convention_queries:
            resp = await http.post(
                f"{kb_url}/retrieve/simple",
                json={
                    "query": query,
                    "clientId": client_id,
                    "projectId": "",  # Client-level only
                    "maxResults": 3,
                },
            )
            resp.raise_for_status()
            conventions = resp.json().get("items", [])
            if conventions:
                if not sections or "Coding Conventions" not in "\n".join(sections):
                    sections.append("\n## Coding Conventions")
                for item in conventions:
                    sections.append(f"- {item.get('content', '')[:200]}")
                break  # Found some, stop trying other queries

        # 3. Architecture decisions for the project
        if project_id:
            arch_queries = search_queries if search_queries else ["architecture decisions design patterns"]
            for query in arch_queries:
                resp = await http.post(
                    f"{kb_url}/retrieve/simple",
                    json={
                        "query": query,
                        "clientId": client_id,
                        "projectId": project_id,
                        "maxResults": 3,
                    },
                )
                resp.raise_for_status()
                arch = resp.json().get("items", [])
                if arch:
                    if "\n## Architecture Decisions" not in sections:
                        sections.append("\n## Architecture Decisions")
                    for item in arch:
                        sections.append(f"- {item.get('content', '')[:200]}")
                    break  # Found some, stop trying other queries

        # 4. File-specific knowledge
        if files:
            for file_path in files[:3]:  # Max 3 files
                resp = await http.post(
                    f"{kb_url}/retrieve/simple",
                    json={
                        "query": f"file {file_path} implementation notes",
                        "clientId": client_id,
                        "projectId": project_id,
                        "maxResults": 2,
                    },
                )
                resp.raise_for_status()
                file_results = resp.json().get("items", [])
                if file_results:
                    sections.append(f"\n## Notes for `{file_path}`")
                    for item in file_results:
                        sections.append(
                            f"- {item.get('content', '')[:200]}"
                        )

    context = "\n".join(sections) if sections else ""
    if context:
        logger.info(
            "KB pre-fetch: %d sections, %d chars",
            len(sections),
            len(context),
        )
    return context


async def fetch_project_context(
    client_id: str,
    project_id: str | None,
    task_description: str,
    target_branch: str | None = None,
    search_queries: list[str] | None = None,
) -> str:
    """Query KB for project-level overview — structure, architecture, conventions.

    Used by the clarify and decompose nodes to understand the project
    before planning. Unlike prefetch_kb_context() which fetches task-specific
    context for coding agents, this fetches a broad project overview.

    Queries:
    1. Repository & branch structure (graph nodes)
    2. File/class nodes (branch-scoped if target_branch specified)
    3. Architecture & modules (semantic search)
    4. Coding conventions (client-level)
    5. Task-relevant context (semantic search)

    Args:
        client_id: Client ID for scoping.
        project_id: Project ID (optional).
        task_description: User's task query for context relevance.
        target_branch: Target branch name (optional, for branch-aware context).
        search_queries: Optional list of queries to use instead of task_description
            for semantic search sections (architecture & task-relevant context).

    Returns:
        Markdown string with project context, or empty string if KB is empty.
    """
    kb_url = f"{settings.knowledgebase_url}/api/v1"
    sections: list[str] = []

    # KB operations can take long due to embeddings and graph traversal
    async with httpx.AsyncClient(timeout=120.0) as http:
        # 1. Repository & branch structure
        repo_nodes = await _graph_search(
            http, kb_url, query="", node_type="repository",
            client_id=client_id, project_id=project_id, limit=5,
        )
        branch_nodes = await _graph_search(
            http, kb_url, query="", node_type="branch",
            client_id=client_id, project_id=project_id, limit=20,
        )

        if repo_nodes or branch_nodes:
            sections.append("## Repository Structure")
            if repo_nodes:
                for node in repo_nodes:
                    label = node.get("label", "?")
                    props = node.get("properties", {})
                    tech = props.get("techStack", "")
                    default_br = props.get("defaultBranch", "")
                    line = f"- **{label}**"
                    if tech:
                        line += f" ({tech})"
                    if default_br:
                        line += f" [default: {default_br}]"
                    sections.append(line)

            if branch_nodes:
                sections.append("### Branches")
                for node in branch_nodes:
                    label = node.get("label", "?")
                    props = node.get("properties", {})
                    is_default = props.get("isDefault", False)
                    status = props.get("status", "")
                    file_count = props.get("fileCount", 0)
                    marker = " (default)" if is_default else ""
                    target_marker = " ← TARGET" if label == target_branch else ""
                    line = f"- {label}{marker}{target_marker}"
                    if file_count:
                        line += f" [{file_count} files]"
                    if status and status != "active":
                        line += f" ({status})"
                    sections.append(line)

        # 2. Project structure from code graph (files + classes)
        # If target_branch specified, scope to that branch; otherwise get all
        file_nodes = await _graph_search_branch_aware(
            http, kb_url, query="", node_type="file",
            client_id=client_id, project_id=project_id,
            branch_name=target_branch, limit=50,
        )
        class_nodes = await _graph_search_branch_aware(
            http, kb_url, query="", node_type="class",
            client_id=client_id, project_id=project_id,
            branch_name=target_branch, limit=30,
        )

        if file_nodes or class_nodes:
            branch_label = f" (branch: {target_branch})" if target_branch else ""
            sections.append(f"## Project Structure{branch_label}")
            if file_nodes:
                sections.append("### Files")
                for node in file_nodes:
                    label = node.get("label", "?")
                    props = node.get("properties", {})
                    branch = props.get("branchName", "")
                    annotation = f" [branch: {branch}]" if branch and not target_branch else ""
                    sections.append(f"- {label}{annotation}")
            if class_nodes:
                sections.append("### Classes")
                for node in class_nodes:
                    label = node.get("label", "?")
                    props = node.get("properties", {})
                    desc = props.get("description", "")
                    branch = props.get("branchName", "")
                    file_path = props.get("filePath", "")
                    line = f"- {label}"
                    if file_path:
                        line += f" (`{file_path}`)"
                    if branch and not target_branch:
                        line += f" [branch: {branch}]"
                    if desc:
                        line += f" — {desc[:100]}"
                    sections.append(line)

        # 3. Architecture & modules (semantic search)
        if project_id:
            arch_queries = search_queries if search_queries else ["project structure modules architecture dependencies technology stack"]
            for query in arch_queries:
                resp = await http.post(
                    f"{kb_url}/retrieve",
                    json={
                        "query": query,
                        "clientId": client_id,
                        "projectId": project_id,
                        "maxResults": 5,
                        "expandGraph": True,
                    },
                )
                resp.raise_for_status()
                results = resp.json().get("items", [])
                if results:
                    if "\n## Architecture & Modules" not in sections:
                        sections.append("\n## Architecture & Modules")
                    for item in results:
                        source = item.get("sourceUrn", "")
                        content = item.get("content", "")[:300]
                        sections.append(f"- **{source}**: {content}")
                    break  # Found some, stop trying other queries

        # 4. Coding conventions (client-level)
        resp = await http.post(
            f"{kb_url}/retrieve/simple",
            json={
                "query": "coding conventions technology stack frameworks patterns",
                "clientId": client_id,
                "projectId": "",  # Client-level only
                "maxResults": 3,
            },
        )
        resp.raise_for_status()
        conventions = resp.json().get("items", [])
        if conventions:
            sections.append("\n## Coding Conventions")
            for item in conventions:
                sections.append(f"- {item.get('content', '')[:200]}")

        # 5. Task-relevant context
        task_queries = search_queries if search_queries else [task_description]
        for query in task_queries:
            resp = await http.post(
                f"{kb_url}/retrieve",
                json={
                    "query": query,
                    "clientId": client_id,
                    "projectId": project_id,
                    "maxResults": 5,
                    "minConfidence": 0.6,
                    "expandGraph": True,
                },
            )
            resp.raise_for_status()
            results = resp.json().get("items", [])
            if results:
                if "\n## Relevant Context for Task" not in sections:
                    sections.append("\n## Relevant Context for Task")
                for item in results:
                    source = item.get("sourceUrn", "")
                    content = item.get("content", "")[:300]
                    sections.append(f"- **{source}**: {content}")
                break  # Found some, stop trying other queries

    context = "\n".join(sections) if sections else ""
    if context:
        logger.info(
            "KB project context: %d sections, %d chars, branch=%s",
            len(sections),
            len(context),
            target_branch,
        )
    return context


async def _graph_search(
    http: httpx.AsyncClient,
    kb_url: str,
    query: str,
    node_type: str,
    client_id: str,
    project_id: str | None,
    limit: int = 20,
) -> list[dict]:
    """Search KB graph nodes by type. Returns list of node dicts or empty list."""
    params: dict = {
        "query": query,
        "nodeType": node_type,
        "clientId": client_id,
        "limit": limit,
    }
    if project_id:
        params["projectId"] = project_id

    try:
        resp = await http.get(f"{kb_url}/graph/search", params=params)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        logger.debug("Graph search failed (type=%s): %s", node_type, e)
        return []


async def _graph_search_branch_aware(
    http: httpx.AsyncClient,
    kb_url: str,
    query: str,
    node_type: str,
    client_id: str,
    project_id: str | None,
    branch_name: str | None = None,
    limit: int = 20,
) -> list[dict]:
    """Search KB graph nodes with optional branch filter.

    When branch_name is specified, only returns nodes scoped to that branch.
    This uses the branchName query parameter added to GET /graph/search.
    """
    params: dict = {
        "query": query,
        "nodeType": node_type,
        "clientId": client_id,
        "limit": limit,
    }
    if project_id:
        params["projectId"] = project_id
    if branch_name:
        params["branchName"] = branch_name

    try:
        resp = await http.get(f"{kb_url}/graph/search", params=params)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        logger.debug(
            "Branch-aware graph search failed (type=%s, branch=%s): %s",
            node_type, branch_name, e,
        )
        return []
