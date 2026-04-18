"""KB pre-fetch – queries Knowledge Base before agent launch.

Three main functions:
- prefetch_kb_context(): Task-specific context for coding agents (written to .jervis/kb-context.md)
- fetch_project_context(): Project-level overview for orchestrator's clarify/decompose nodes
- fetch_user_context(): Auto-prefetch user-learned knowledge for personalized responses
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings, foreground_headers

logger = logging.getLogger(__name__)

# Structured categories for user-learned knowledge (maps to kind=user_knowledge_{category})
USER_CONTEXT_KINDS: dict[str, str] = {
    "user_knowledge_preference": "Preferences",
    "user_knowledge_domain": "Domain Context",
    "user_knowledge_team": "Team & Organization",
    "user_knowledge_tech_stack": "Technical Stack",
    "user_knowledge_personal": "Personal",
    "user_knowledge_general": "General Knowledge",
}

# Maximum total characters for user context (prevent bloating LLM prompt)
_USER_CONTEXT_MAX_CHARS = 4000


async def prefetch_kb_context(
    task_description: str,
    client_id: str,
    project_id: str | None,
    files: list[str] | None = None,
    search_queries: list[str] | None = None,
    processing_mode: str = "FOREGROUND",
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
    from jervis_contracts import kb_client

    sections: list[str] = []

    # 1. Relevant knowledge for the task
    task_results: list[dict] = []
    queries_to_try = search_queries if search_queries else [task_description]
    for query in queries_to_try:
        try:
            results = await kb_client.retrieve(
                caller="orchestrator.kb.prefetch",
                query=query,
                client_id=client_id,
                project_id=project_id or "",
                max_results=5,
                min_confidence=0.7,
                expand_graph=True,
                timeout=120.0,
            )
        except Exception as _e:
            logger.debug("KB task query error '%s': %s", query, _e)
            continue
        if results:
            task_results.extend(results)
            logger.info("KB task query succeeded: '%s' (%d results)", query, len(results))
        else:
            logger.debug("KB task query returned no results: '%s'", query)

    if task_results:
        seen = set()
        unique_results = []
        for item in task_results:
            urn = item.get("sourceUrn")
            if urn and urn not in seen:
                seen.add(urn)
                unique_results.append(item)

        sections.append("## Relevant Knowledge")
        for item in unique_results[:5]:
            source = item.get("sourceUrn", "?")
            content = (item.get("content", "") or "")[:300]
            sections.append(f"- **{source}**: {content}")

    # 2. Coding conventions for the client
    convention_queries = search_queries if search_queries else ["coding conventions style guide rules"]
    for query in convention_queries:
        try:
            conventions = await kb_client.retrieve(
                caller="orchestrator.kb.prefetch",
                query=query,
                client_id=client_id,
                project_id="",  # Client-level only
                max_results=3,
                simple=True,
                timeout=120.0,
            )
        except Exception:
            conventions = []
        if conventions:
            if not sections or "Coding Conventions" not in "\n".join(sections):
                sections.append("\n## Coding Conventions")
            for item in conventions:
                sections.append(f"- {(item.get('content', '') or '')[:200]}")
            break

    # 3. Architecture decisions for the project
    if project_id:
        arch_queries = search_queries if search_queries else ["architecture decisions design patterns"]
        for query in arch_queries:
            try:
                arch = await kb_client.retrieve(
                    caller="orchestrator.kb.prefetch",
                    query=query,
                    client_id=client_id,
                    project_id=project_id,
                    max_results=3,
                    simple=True,
                    timeout=120.0,
                )
            except Exception:
                arch = []
            if arch:
                if "\n## Architecture Decisions" not in sections:
                    sections.append("\n## Architecture Decisions")
                for item in arch:
                    sections.append(f"- {(item.get('content', '') or '')[:200]}")
                break

    # 4. File-specific knowledge
    if files:
        for file_path in files[:3]:
            try:
                file_results = await kb_client.retrieve(
                    caller="orchestrator.kb.prefetch",
                    query=f"file {file_path} implementation notes",
                    client_id=client_id,
                    project_id=project_id or "",
                    max_results=2,
                    simple=True,
                    timeout=120.0,
                )
            except Exception:
                file_results = []
            if file_results:
                sections.append(f"\n## Notes for `{file_path}`")
                for item in file_results:
                    sections.append(
                        f"- {(item.get('content', '') or '')[:200]}"
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
    processing_mode: str = "FOREGROUND",
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

    # Dynamic priority: FOREGROUND → CRITICAL (0), BACKGROUND → no header (NORMAL)
    headers = foreground_headers(processing_mode)

    # KB operations can take long due to embeddings and graph traversal
    async with httpx.AsyncClient(timeout=120.0, headers=headers) as http:
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

        from jervis_contracts import kb_client

        # 3. Architecture & modules (semantic search)
        if project_id:
            arch_queries = search_queries if search_queries else ["project structure modules architecture dependencies technology stack"]
            for query in arch_queries:
                try:
                    results = await kb_client.retrieve(
                        caller="orchestrator.kb.prefetch.project",
                        query=query,
                        client_id=client_id,
                        project_id=project_id,
                        max_results=5,
                        expand_graph=True,
                        timeout=120.0,
                    )
                except Exception:
                    results = []
                if results:
                    if "\n## Architecture & Modules" not in sections:
                        sections.append("\n## Architecture & Modules")
                    for item in results:
                        source = item.get("sourceUrn", "")
                        content = (item.get("content", "") or "")[:300]
                        sections.append(f"- **{source}**: {content}")
                    break

        # 4. Coding conventions (client-level)
        try:
            conventions = await kb_client.retrieve(
                caller="orchestrator.kb.prefetch.project",
                query="coding conventions technology stack frameworks patterns",
                client_id=client_id,
                project_id="",
                max_results=3,
                simple=True,
                timeout=120.0,
            )
        except Exception:
            conventions = []
        if conventions:
            sections.append("\n## Coding Conventions")
            for item in conventions:
                sections.append(f"- {(item.get('content', '') or '')[:200]}")

        # 5. Task-relevant context
        task_queries = search_queries if search_queries else [task_description]
        for query in task_queries:
            try:
                results = await kb_client.retrieve(
                    caller="orchestrator.kb.prefetch.project",
                    query=query,
                    client_id=client_id,
                    project_id=project_id or "",
                    max_results=5,
                    min_confidence=0.6,
                    expand_graph=True,
                    timeout=120.0,
                )
            except Exception:
                results = []
            if results:
                if "\n## Relevant Context for Task" not in sections:
                    sections.append("\n## Relevant Context for Task")
                for item in results:
                    source = item.get("sourceUrn", "")
                    content = (item.get("content", "") or "")[:300]
                    sections.append(f"- **{source}**: {content}")
                break

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
    http: httpx.AsyncClient | None,
    kb_url: str,
    query: str,
    node_type: str,
    client_id: str,
    project_id: str | None,
    limit: int = 20,
) -> list[dict]:
    """Search KB graph nodes by type (gRPC). http + kb_url kept for signature
    compatibility with callers that pre-built the httpx client; unused."""
    from jervis_contracts import kb_client

    try:
        return await kb_client.graph_search(
            caller="orchestrator.kb.prefetch",
            query=query,
            client_id=client_id,
            project_id=project_id or "",
            node_type=node_type,
            max_results=limit,
            timeout=30.0,
        )
    except Exception as e:
        logger.debug("Graph search failed (type=%s): %s", node_type, e)
        return []


async def _graph_search_branch_aware(
    http: httpx.AsyncClient | None,
    kb_url: str,
    query: str,
    node_type: str,
    client_id: str,
    project_id: str | None,
    branch_name: str | None = None,
    limit: int = 20,
) -> list[dict]:
    """Branch-scoped variant of _graph_search (gRPC)."""
    from jervis_contracts import kb_client

    try:
        return await kb_client.graph_search(
            caller="orchestrator.kb.prefetch",
            query=query,
            client_id=client_id,
            project_id=project_id or "",
            node_type=node_type,
            branch_name=branch_name or "",
            max_results=limit,
            timeout=30.0,
        )
    except Exception as e:
        logger.debug(
            "Branch-aware graph search failed (type=%s, branch=%s): %s",
            node_type, branch_name, e,
        )
        return []


async def fetch_user_context(
    client_id: str,
    project_id: str | None = None,
) -> str:
    """Fetch all user-learned knowledge from KB for context injection.

    Queries each structured category (preference, domain, team, tech_stack,
    personal, general) via the /chunks/by-kind endpoint. This is a pure
    Weaviate filter query — no embeddings, no GPU, very fast.

    Args:
        client_id: Client ID for scoping.
        project_id: Project ID (optional).

    Returns:
        Markdown string with user context organized by category,
        or empty string if no user knowledge exists.
    """
    kb_url = f"{settings.knowledgebase_url}/api/v1"
    sections: list[str] = []
    total_chars = 0

    from jervis_contracts import kb_client

    for kind, label in USER_CONTEXT_KINDS.items():
        if total_chars >= _USER_CONTEXT_MAX_CHARS:
            break

        try:
            chunks = await kb_client.list_chunks_by_kind(
                caller="orchestrator.kb.prefetch.user_context",
                kind=kind,
                client_id=client_id,
                project_id=project_id or "",
                max_results=20,
                timeout=30.0,
            )

            if not chunks:
                continue

            section_lines = [f"### {label}"]
            for chunk in chunks:
                content = chunk.get("content", "").strip()
                if not content:
                    continue
                if content.startswith("# "):
                    parts = content.split("\n\n", 1)
                    subject = parts[0].lstrip("# ").strip()
                    body = parts[1].strip() if len(parts) > 1 else ""
                    line = f"- **{subject}**: {body}" if body else f"- {subject}"
                else:
                    line = f"- {content}"

                if len(line) > 300:
                    line = line[:297] + "..."

                section_lines.append(line)
                total_chars += len(line)

                if total_chars >= _USER_CONTEXT_MAX_CHARS:
                    break

            if len(section_lines) > 1:  # More than just the header
                sections.append("\n".join(section_lines))

        except Exception as e:
            logger.debug("User context fetch failed for kind=%s: %s", kind, e)
            continue

    context = "\n\n".join(sections) if sections else ""
    if context:
        logger.info(
            "User context prefetch: %d categories, %d chars",
            len(sections),
            len(context),
        )
    return context
