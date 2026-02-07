"""KB pre-fetch – queries Knowledge Base before agent launch.

This provides context to ALL agents (not just Claude Code with MCP).
The orchestrator fetches relevant knowledge and writes it to .jervis/kb-context.md.
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
