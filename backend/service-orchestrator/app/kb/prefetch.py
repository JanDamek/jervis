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
) -> str:
    """Query KB and return context relevant for the coding task.

    Args:
        task_description: What the agent will do.
        client_id: Client ID for scoping.
        project_id: Project ID (optional).
        files: Specific files that will be modified.

    Returns:
        Markdown string with KB context, or empty string if nothing found.
    """
    kb_url = settings.knowledgebase_url
    sections: list[str] = []

    try:
        async with httpx.AsyncClient(timeout=15) as http:
            # 1. Relevant knowledge for the task
            resp = await http.post(
                f"{kb_url}/retrieve",
                json={
                    "query": task_description,
                    "clientId": client_id,
                    "projectId": project_id,
                    "maxResults": 5,
                    "minConfidence": 0.7,
                    "expandGraph": True,
                },
            )
            if resp.status_code == 200:
                results = resp.json().get("items", [])
                if results:
                    sections.append("## Relevant Knowledge")
                    for item in results:
                        source = item.get("sourceUrn", "?")
                        content = item.get("content", "")[:300]
                        sections.append(f"- **{source}**: {content}")

            # 2. Coding conventions for the client
            resp = await http.post(
                f"{kb_url}/retrieve/simple",
                json={
                    "query": "coding conventions style guide rules",
                    "clientId": client_id,
                    "projectId": "",  # Client-level only
                    "maxResults": 3,
                },
            )
            if resp.status_code == 200:
                conventions = resp.json().get("items", [])
                if conventions:
                    sections.append("\n## Coding Conventions")
                    for item in conventions:
                        sections.append(f"- {item.get('content', '')[:200]}")

            # 3. Architecture decisions for the project
            if project_id:
                resp = await http.post(
                    f"{kb_url}/retrieve/simple",
                    json={
                        "query": "architecture decisions design patterns",
                        "clientId": client_id,
                        "projectId": project_id,
                        "maxResults": 3,
                    },
                )
                if resp.status_code == 200:
                    arch = resp.json().get("items", [])
                    if arch:
                        sections.append("\n## Architecture Decisions")
                        for item in arch:
                            sections.append(f"- {item.get('content', '')[:200]}")

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
                    if resp.status_code == 200:
                        file_results = resp.json().get("items", [])
                        if file_results:
                            sections.append(f"\n## Notes for `{file_path}`")
                            for item in file_results:
                                sections.append(
                                    f"- {item.get('content', '')[:200]}"
                                )

    except httpx.HTTPError as e:
        logger.warning("KB pre-fetch failed: %s", e)
    except Exception as e:
        logger.warning("KB pre-fetch unexpected error: %s", e)

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
) -> str:
    """Query KB for project-level overview — structure, architecture, conventions.

    Used by the clarify and decompose nodes to understand the project
    before planning. Unlike prefetch_kb_context() which fetches task-specific
    context for coding agents, this fetches a broad project overview.

    Queries:
    1. Graph search: file/class nodes → project structure overview
    2. Architecture & modules (semantic search)
    3. Coding conventions (client-level)
    4. Task-relevant context (semantic search)

    Args:
        client_id: Client ID for scoping.
        project_id: Project ID (optional).
        task_description: User's task query for context relevance.

    Returns:
        Markdown string with project context, or empty string if KB is empty.
    """
    kb_url = settings.knowledgebase_url
    sections: list[str] = []

    try:
        async with httpx.AsyncClient(timeout=20) as http:
            # 1. Project structure from code graph (files + classes)
            file_nodes = await _graph_search(
                http, kb_url, query="", node_type="file",
                client_id=client_id, project_id=project_id, limit=50,
            )
            class_nodes = await _graph_search(
                http, kb_url, query="", node_type="class",
                client_id=client_id, project_id=project_id, limit=30,
            )

            if file_nodes or class_nodes:
                sections.append("## Project Structure (from code graph)")
                if file_nodes:
                    sections.append("### Files")
                    for node in file_nodes:
                        label = node.get("label", "?")
                        node_type = node.get("properties", {}).get("type", "")
                        sections.append(f"- {label}")
                if class_nodes:
                    sections.append("### Classes")
                    for node in class_nodes:
                        label = node.get("label", "?")
                        desc = node.get("properties", {}).get("description", "")
                        line = f"- {label}"
                        if desc:
                            line += f" — {desc[:100]}"
                        sections.append(line)

            # 2. Architecture & modules (semantic search)
            if project_id:
                resp = await http.post(
                    f"{kb_url}/retrieve",
                    json={
                        "query": "project structure modules architecture dependencies technology stack",
                        "clientId": client_id,
                        "projectId": project_id,
                        "maxResults": 5,
                        "expandGraph": True,
                    },
                )
                if resp.status_code == 200:
                    results = resp.json().get("items", [])
                    if results:
                        sections.append("\n## Architecture & Modules")
                        for item in results:
                            source = item.get("sourceUrn", "")
                            content = item.get("content", "")[:300]
                            sections.append(f"- **{source}**: {content}")

            # 3. Coding conventions (client-level)
            resp = await http.post(
                f"{kb_url}/retrieve/simple",
                json={
                    "query": "coding conventions technology stack frameworks patterns",
                    "clientId": client_id,
                    "projectId": "",  # Client-level only
                    "maxResults": 3,
                },
            )
            if resp.status_code == 200:
                conventions = resp.json().get("items", [])
                if conventions:
                    sections.append("\n## Coding Conventions")
                    for item in conventions:
                        sections.append(f"- {item.get('content', '')[:200]}")

            # 4. Task-relevant context
            resp = await http.post(
                f"{kb_url}/retrieve",
                json={
                    "query": task_description,
                    "clientId": client_id,
                    "projectId": project_id,
                    "maxResults": 5,
                    "minConfidence": 0.6,
                    "expandGraph": True,
                },
            )
            if resp.status_code == 200:
                results = resp.json().get("items", [])
                if results:
                    sections.append("\n## Relevant Context for Task")
                    for item in results:
                        source = item.get("sourceUrn", "")
                        content = item.get("content", "")[:300]
                        sections.append(f"- **{source}**: {content}")

    except httpx.HTTPError as e:
        logger.warning("KB project context fetch failed: %s", e)
    except Exception as e:
        logger.warning("KB project context fetch unexpected error: %s", e)

    context = "\n".join(sections) if sections else ""
    if context:
        logger.info(
            "KB project context: %d sections, %d chars",
            len(sections),
            len(context),
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
        if resp.status_code == 200:
            return resp.json()
    except Exception as e:
        logger.warning("KB graph search failed (type=%s): %s", node_type, e)
    return []
