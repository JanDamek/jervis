"""Tool executor for the respond node's agentic loop.

Executes tool calls returned by the LLM and returns results as strings.
Never raises exceptions — errors are returned as descriptive strings so
the LLM can decide how to proceed.
"""

from __future__ import annotations

import logging
import os
import subprocess
from pathlib import Path
from datetime import datetime

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

_TIMEOUT_WEB_SEARCH = 15.0  # seconds
_TIMEOUT_KB_SEARCH = 10.0   # seconds


class AskUserInterrupt(Exception):
    """Raised by ask_user tool to signal that respond node should interrupt.

    This is NOT an error — it's a control flow mechanism. The respond node
    catches this, calls langgraph interrupt(), and resumes when the user answers.
    """

    def __init__(self, question: str):
        self.question = question
        super().__init__(question)


async def execute_tool(
    tool_name: str,
    arguments: dict,
    client_id: str,
    project_id: str | None,
    processing_mode: str = "FOREGROUND",
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
    logger.debug(
        "execute_tool START: tool=%s, args=%s, client_id=%s, project_id=%s",
        tool_name, arguments, client_id, project_id
    )

    # ask_user is special — it raises AskUserInterrupt (not caught here).
    # The respond node catches it to trigger langgraph interrupt().
    if tool_name == "ask_user":
        question = arguments.get("question", "").strip()
        if not question:
            return "Error: Question cannot be empty."
        logger.info("ASK_USER: raising interrupt for question: %s", question)
        raise AskUserInterrupt(question)

    try:
        result = None
        if tool_name == "web_search":
            result = await _execute_web_search(
                query=arguments.get("query", ""),
                max_results=arguments.get("max_results", 5),
            )
        elif tool_name == "kb_search":
            result = await _execute_kb_search(
                query=arguments.get("query", ""),
                max_results=arguments.get("max_results", 5),
                client_id=client_id,
                project_id=project_id,
                processing_mode=processing_mode,
            )
        elif tool_name == "store_knowledge":
            result = await _execute_store_knowledge(
                subject=arguments.get("subject", ""),
                content=arguments.get("content", ""),
                category=arguments.get("category", "general"),
                client_id=client_id,
                project_id=project_id,
                processing_mode=processing_mode,
            )
        elif tool_name == "create_scheduled_task":
            result = await _execute_create_scheduled_task(
                title=arguments.get("title", ""),
                description=arguments.get("description", ""),
                reason=arguments.get("reason", ""),
                schedule=arguments.get("schedule", "manual"),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_indexed_items":
            result = await _execute_get_indexed_items(
                item_type=arguments.get("item_type", "all"),
                limit=arguments.get("limit", 10),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_kb_stats":
            result = await _execute_get_kb_stats(
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "list_project_files":
            result = await _execute_list_project_files(
                branch=arguments.get("branch"),
                file_pattern=arguments.get("file_pattern"),
                limit=arguments.get("limit", 50),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_repository_info":
            result = await _execute_get_repository_info(
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "joern_quick_scan":
            result = await _execute_joern_quick_scan(
                scan_type=arguments.get("scan_type", "security"),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "git_branch_list":
            result = await _execute_git_branch_list(
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_recent_commits":
            result = await _execute_get_recent_commits(
                limit=arguments.get("limit", 10),
                branch=arguments.get("branch"),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_technology_stack":
            result = await _execute_get_technology_stack(
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "get_repository_structure":
            result = await _execute_get_repository_structure(
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "code_search":
            result = await _execute_code_search(
                query=arguments.get("query", ""),
                language=arguments.get("language"),
                max_results=arguments.get("max_results", 5),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "git_status":
            result = await _execute_git_status(
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "git_log":
            result = await _execute_git_log(
                limit=arguments.get("limit", 10),
                branch=arguments.get("branch"),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "git_diff":
            result = await _execute_git_diff(
                commit1=arguments.get("commit1"),
                commit2=arguments.get("commit2"),
                file_path=arguments.get("file_path"),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "git_show":
            result = await _execute_git_show(
                commit=arguments.get("commit", "HEAD"),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "git_blame":
            result = await _execute_git_blame(
                file_path=arguments.get("file_path", ""),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "list_files":
            result = await _execute_list_files(
                path=arguments.get("path", "."),
                show_hidden=arguments.get("show_hidden", False),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "read_file":
            result = await _execute_read_file(
                file_path=arguments.get("file_path", ""),
                max_lines=arguments.get("max_lines", 1000),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "find_files":
            result = await _execute_find_files(
                pattern=arguments.get("pattern", ""),
                path=arguments.get("path", "."),
                max_results=arguments.get("max_results", 100),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "grep_files":
            result = await _execute_grep_files(
                pattern=arguments.get("pattern", ""),
                file_pattern=arguments.get("file_pattern", "*"),
                max_results=arguments.get("max_results", 50),
                context_lines=arguments.get("context_lines", 2),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "file_info":
            result = await _execute_file_info(
                path=arguments.get("path", ""),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "execute_command":
            result = await _execute_command(
                command=arguments.get("command", ""),
                timeout=arguments.get("timeout", 30),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "memory_store":
            result = await _execute_memory_store(
                subject=arguments.get("subject", ""),
                content=arguments.get("content", ""),
                category=arguments.get("category", "fact"),
                priority=arguments.get("priority", "normal"),
                client_id=client_id,
                project_id=project_id,
            )
        elif tool_name == "memory_recall":
            result = await _execute_memory_recall(
                query=arguments.get("query", ""),
                scope=arguments.get("scope", "all"),
                client_id=client_id,
                project_id=project_id,
                processing_mode=processing_mode,
            )
        elif tool_name == "list_affairs":
            result = await _execute_list_affairs(
                client_id=client_id,
            )
        # ---- Brain tools ----
        elif tool_name == "brain_create_issue":
            result = await _execute_brain_create_issue(
                summary=arguments.get("summary", ""),
                description=arguments.get("description"),
                issue_type=arguments.get("issue_type", "Task"),
                priority=arguments.get("priority"),
                labels=arguments.get("labels"),
                epic_key=arguments.get("epic_key"),
            )
        elif tool_name == "brain_update_issue":
            result = await _execute_brain_update_issue(
                issue_key=arguments.get("issue_key", ""),
                summary=arguments.get("summary"),
                description=arguments.get("description"),
                assignee=arguments.get("assignee"),
                priority=arguments.get("priority"),
                labels=arguments.get("labels"),
            )
        elif tool_name == "brain_add_comment":
            result = await _execute_brain_add_comment(
                issue_key=arguments.get("issue_key", ""),
                body=arguments.get("body", ""),
            )
        elif tool_name == "brain_transition_issue":
            result = await _execute_brain_transition_issue(
                issue_key=arguments.get("issue_key", ""),
                transition_name=arguments.get("transition_name", ""),
            )
        elif tool_name == "brain_search_issues":
            result = await _execute_brain_search_issues(
                jql=arguments.get("jql", ""),
                max_results=arguments.get("max_results", 20),
            )
        elif tool_name == "brain_create_page":
            result = await _execute_brain_create_page(
                title=arguments.get("title", ""),
                content=arguments.get("content", ""),
                parent_page_id=arguments.get("parent_page_id"),
            )
        elif tool_name == "brain_update_page":
            result = await _execute_brain_update_page(
                page_id=arguments.get("page_id", ""),
                title=arguments.get("title", ""),
                content=arguments.get("content", ""),
                version=arguments.get("version", 1),
            )
        elif tool_name == "brain_search_pages":
            result = await _execute_brain_search_pages(
                query=arguments.get("query", ""),
                max_results=arguments.get("max_results", 20),
            )
        else:
            result = f"Error: Unknown tool '{tool_name}'."

        logger.debug("execute_tool END: tool=%s, result_len=%d, result=%s", tool_name, len(result), result[:500])
        return result
    except Exception as e:
        logger.exception("Tool execution failed: %s", tool_name)
        result = f"Error executing {tool_name}: {str(e)[:300]}"
        logger.debug("execute_tool ERROR: tool=%s, result=%s", tool_name, result)
        return result


def _sanitize_text(text: str) -> str:
    """Sanitize text for LLM consumption.

    Removes control characters, normalizes whitespace, and ensures valid UTF-8.
    Prevents Ollama "Operation not allowed" errors from malformed web content.
    """
    if not text:
        return ""

    # Remove control characters except newline, tab, and carriage return
    sanitized = ''.join(
        ch for ch in text
        if ch.isprintable() or ch in '\n\t\r'
    )

    # Normalize excessive whitespace
    sanitized = ' '.join(sanitized.split())

    return sanitized


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
        title = _sanitize_text(r.get("title", "Untitled"))
        url_str = r.get("url", "")
        content = _sanitize_text(r.get("content", ""))[:400]
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
    processing_mode: str = "FOREGROUND",
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

    headers = {"X-Ollama-Priority": "0"} if processing_mode == "FOREGROUND" else {}

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_KB_SEARCH) as client:
            resp = await client.post(url, json=payload, headers=headers)
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
        content = item.get("content", "")  # Full chunk content — already chunked by RAG (1000 chars)
        score = item.get("score", 0)
        kind = item.get("kind", "")
        lines.append(f"## Result {i} (score: {score:.2f}, kind: {kind})")
        lines.append(f"Source: {source}")
        if content:
            lines.append(content)
        lines.append("")

    return "\n".join(lines)


async def _execute_store_knowledge(
    subject: str,
    content: str,
    category: str = "general",
    client_id: str = "",
    project_id: str | None = None,
    processing_mode: str = "FOREGROUND",
) -> str:
    """Store new knowledge into the Knowledge Base via POST /api/v1/ingest.

    Stores user-provided facts, definitions, and information for future reference.
    Uses the write endpoint which routes to jervis-knowledgebase-write service.
    """
    if not subject.strip():
        return "Error: Subject cannot be empty when storing knowledge."
    if not content.strip():
        return "Error: Content cannot be empty when storing knowledge."

    # Use KB write endpoint (separate deployment for write operations)
    kb_write_url = settings.knowledgebase_write_url or settings.knowledgebase_url
    url = f"{kb_write_url}/api/v1/ingest"

    # Generate unique sourceUrn for user-provided knowledge
    timestamp = datetime.now().isoformat()
    source_urn = f"user-knowledge:{category}:{subject}:{timestamp}"

    payload = {
        "clientId": client_id,
        "projectId": project_id,
        "sourceUrn": source_urn,
        "kind": f"user_knowledge_{category}",
        "content": f"# {subject}\n\n{content}",
        "metadata": {
            "subject": subject,
            "category": category,
            "stored_at": timestamp,
            "source": "agent_learning",
        },
    }

    headers = {"X-Ollama-Priority": "0"} if processing_mode == "FOREGROUND" else {}

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, json=payload, headers=headers)
            resp.raise_for_status()
            data = resp.json()
    except httpx.TimeoutException:
        return f"Error: KB write timed out after 10s for subject: {subject}"
    except httpx.HTTPStatusError as e:
        return f"Error: Knowledge Base write returned HTTP {e.response.status_code} for subject: {subject}"
    except Exception as e:
        return f"Error: KB write failed: {str(e)[:200]}"

    # Success response
    chunk_count = data.get("chunk_count", 0)
    return (
        f"✓ Knowledge stored successfully in KB!\n"
        f"Subject: {subject}\n"
        f"Category: {category}\n"
        f"Chunks created: {chunk_count}\n"
        f"This information is now available for future queries.\n"
        f"If there are other parts to handle, continue with those now."
    )


async def _execute_create_scheduled_task(
    title: str,
    description: str,
    reason: str,
    schedule: str = "manual",
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Create a scheduled task for future work via Kotlin server task API.

    Creates a task that will be visible in user's task list and can be scheduled
    for automatic execution or manual review.
    """
    if not title.strip():
        return "Error: Task title cannot be empty."
    if not description.strip():
        return "Error: Task description cannot be empty."

    # Map schedule enum to days offset
    schedule_map = {
        "when_code_available": None,  # Trigger-based, not time-based
        "in_1_day": 1,
        "in_1_week": 7,
        "in_1_month": 30,
        "manual": None,  # No automatic scheduling
    }

    days_offset = schedule_map.get(schedule)

    # Use Kotlin server's internal task creation endpoint
    kotlin_url = settings.kotlin_server_url or "http://jervis-server:8080"
    url = f"{kotlin_url}/internal/tasks/create"

    payload = {
        "clientId": client_id,
        "projectId": project_id,
        "title": title,
        "description": f"{description}\n\nReason: {reason}",
        "schedule": schedule,
        "daysOffset": days_offset,
        "createdBy": "orchestrator_agent",
        "metadata": {
            "reason": reason,
            "schedule_type": schedule,
            "created_from": "agent_tool",
        },
    }

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
            task_id = data.get("taskId", "unknown")
    except httpx.TimeoutException:
        return f"Error: Task creation timed out after 5s for: {title}"
    except httpx.HTTPStatusError as e:
        return f"Error: Task creation returned HTTP {e.response.status_code} for: {title}"
    except Exception as e:
        return f"Error: Task creation failed: {str(e)[:200]}"

    # Success response
    schedule_info = f"scheduled for {schedule.replace('_', ' ')}" if schedule != "manual" else "created for manual review"
    return (
        f"✓ Task created successfully!\n"
        f"Title: {title}\n"
        f"Schedule: {schedule_info}\n"
        f"Task ID: {task_id}\n"
        f"The task is now in your task list and will be executed when appropriate."
    )


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
        content = item.get("content", "")  # Full chunk content — already chunked by RAG (1000 chars)
        score = item.get("score", 0)
        kind = item.get("kind", "")
        lines.append(f"### Result {i} (score: {score:.2f}, kind: {kind})")
        lines.append(f"Source: {source}")
        if content:
            lines.append(f"```\n{content}\n```")
        lines.append("")

    return "\n".join(lines)


# ========== Git Workspace Tools ==========


async def _execute_git_status(
    client_id: str,
    project_id: str | None,
) -> str:
    """Get git status of workspace."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    try:
        result = subprocess.run(
            ["git", "status", "--short", "--branch"],
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=10,
        )

        if result.returncode != 0:
            return f"Error: git status failed: {result.stderr}"

        output = result.stdout.strip()
        if not output:
            return "## Git Status\n\nWorking tree clean, no changes."

        return f"## Git Status\n\n```\n{output}\n```"
    except subprocess.TimeoutExpired:
        return "Error: git status timed out after 10s"
    except Exception as e:
        return f"Error: git status failed: {str(e)[:200]}"


async def _execute_git_log(
    limit: int,
    branch: str | None,
    client_id: str,
    project_id: str | None,
) -> str:
    """Get git commit history."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    try:
        cmd = ["git", "log", f"--max-count={limit}", "--pretty=format:%h - %an, %ar : %s"]
        if branch:
            cmd.append(branch)

        result = subprocess.run(
            cmd,
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=15,
        )

        if result.returncode != 0:
            return f"Error: git log failed: {result.stderr}"

        output = result.stdout.strip()
        if not output:
            return "## Git Log\n\nNo commits found."

        return f"## Git Log ({limit} commits)\n\n```\n{output}\n```"
    except subprocess.TimeoutExpired:
        return "Error: git log timed out after 15s"
    except Exception as e:
        return f"Error: git log failed: {str(e)[:200]}"


async def _execute_git_diff(
    commit1: str | None,
    commit2: str | None,
    file_path: str | None,
    client_id: str,
    project_id: str | None,
) -> str:
    """Show git diff between commits or working directory."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    try:
        cmd = ["git", "diff"]

        if commit1:
            cmd.append(commit1)
        if commit2:
            cmd.append(commit2)
        if file_path:
            cmd.extend(["--", file_path])

        result = subprocess.run(
            cmd,
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=20,
        )

        if result.returncode != 0:
            return f"Error: git diff failed: {result.stderr}"

        output = result.stdout.strip()
        if not output:
            return "## Git Diff\n\nNo differences found."

        # Truncate if too long
        if len(output) > 10000:
            output = output[:10000] + "\n\n... (truncated, diff too large)"

        return f"## Git Diff\n\n```diff\n{output}\n```"
    except subprocess.TimeoutExpired:
        return "Error: git diff timed out after 20s"
    except Exception as e:
        return f"Error: git diff failed: {str(e)[:200]}"


async def _execute_git_show(
    commit: str,
    client_id: str,
    project_id: str | None,
) -> str:
    """Show commit details and changes."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    try:
        result = subprocess.run(
            ["git", "show", "--stat", commit],
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=15,
        )

        if result.returncode != 0:
            return f"Error: git show failed: {result.stderr}"

        output = result.stdout.strip()

        # Truncate if too long
        if len(output) > 8000:
            output = output[:8000] + "\n\n... (truncated, output too large)"

        return f"## Git Show: {commit}\n\n```\n{output}\n```"
    except subprocess.TimeoutExpired:
        return "Error: git show timed out after 15s"
    except Exception as e:
        return f"Error: git show failed: {str(e)[:200]}"


async def _execute_git_blame(
    file_path: str,
    client_id: str,
    project_id: str | None,
) -> str:
    """Show git blame for a file."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    if not file_path:
        return "Error: file_path required for git blame."

    try:
        # Security: prevent path traversal
        if ".." in file_path or file_path.startswith("/"):
            return "Error: Invalid file path (path traversal detected)."

        result = subprocess.run(
            ["git", "blame", "--", file_path],
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=15,
        )

        if result.returncode != 0:
            return f"Error: git blame failed: {result.stderr}"

        output = result.stdout.strip()

        # Truncate if too long
        if len(output) > 10000:
            lines = output.split("\n")
            output = "\n".join(lines[:200]) + "\n\n... (truncated, showing first 200 lines)"

        return f"## Git Blame: {file_path}\n\n```\n{output}\n```"
    except subprocess.TimeoutExpired:
        return "Error: git blame timed out after 15s"
    except Exception as e:
        return f"Error: git blame failed: {str(e)[:200]}"


async def _get_workspace_path(client_id: str, project_id: str | None) -> str | None:
    """Get workspace path from KB graph (repository node)."""
    if not project_id:
        return None

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
                return None

            workspace_path = repos[0].get("properties", {}).get("workspacePath")
            return workspace_path
    except Exception as e:
        logger.error(f"Failed to get workspace path: {e}")
        return None


# ========== Filesystem Tools ==========


async def _execute_list_files(
    path: str,
    show_hidden: bool,
    client_id: str,
    project_id: str | None,
) -> str:
    """List files and directories in workspace path."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    try:
        # Security: prevent path traversal
        if ".." in path or path.startswith("/"):
            return "Error: Invalid path (path traversal detected)."

        full_path = Path(workspace) / path
        if not full_path.exists():
            return f"Error: Path not found: {path}"

        if not full_path.is_dir():
            return f"Error: Not a directory: {path}"

        items = []
        for item in sorted(full_path.iterdir()):
            # Skip hidden files unless requested
            if not show_hidden and item.name.startswith("."):
                continue

            item_type = "DIR" if item.is_dir() else "FILE"
            size = ""
            if item.is_file():
                try:
                    size_bytes = item.stat().st_size
                    if size_bytes < 1024:
                        size = f"{size_bytes}B"
                    elif size_bytes < 1024 * 1024:
                        size = f"{size_bytes / 1024:.1f}KB"
                    else:
                        size = f"{size_bytes / (1024 * 1024):.1f}MB"
                except Exception:
                    size = "?"

            items.append(f"[{item_type}] {item.name}" + (f" ({size})" if size else ""))

        if not items:
            return f"## Directory: {path}\n\n(empty directory)"

        return f"## Directory: {path}\n\n" + "\n".join(items)
    except Exception as e:
        return f"Error: Failed to list files: {str(e)[:200]}"


async def _execute_read_file(
    file_path: str,
    max_lines: int,
    client_id: str,
    project_id: str | None,
) -> str:
    """Read file contents from workspace."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    if not file_path:
        return "Error: file_path required."

    try:
        # Security: prevent path traversal
        if ".." in file_path or file_path.startswith("/"):
            return "Error: Invalid file path (path traversal detected)."

        full_path = Path(workspace) / file_path
        if not full_path.exists():
            return f"Error: File not found: {file_path}"

        if not full_path.is_file():
            return f"Error: Not a file: {file_path}"

        # Check file size
        size_bytes = full_path.stat().st_size
        if size_bytes > 10 * 1024 * 1024:  # 10MB limit
            return f"Error: File too large ({size_bytes / (1024 * 1024):.1f}MB). Max 10MB."

        # Try to read as text
        try:
            content = full_path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            return f"Error: Binary file cannot be displayed: {file_path}"

        lines = content.split("\n")
        total_lines = len(lines)

        if total_lines > max_lines:
            content = "\n".join(lines[:max_lines])
            truncated_msg = f"\n\n... (truncated, showing {max_lines}/{total_lines} lines)"
        else:
            truncated_msg = ""

        return f"## File: {file_path} ({total_lines} lines)\n\n```\n{content}{truncated_msg}\n```"
    except Exception as e:
        return f"Error: Failed to read file: {str(e)[:200]}"


async def _execute_find_files(
    pattern: str,
    path: str,
    max_results: int,
    client_id: str,
    project_id: str | None,
) -> str:
    """Find files matching pattern in workspace."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    if not pattern:
        return "Error: pattern required."

    try:
        # Security: prevent path traversal
        if ".." in path or path.startswith("/"):
            return "Error: Invalid path (path traversal detected)."

        base_path = Path(workspace) / path
        if not base_path.exists():
            return f"Error: Path not found: {path}"

        # Use glob to find files
        matches = []
        if "**" in pattern:
            # Recursive glob
            for match in base_path.glob(pattern):
                if match.is_file():
                    rel_path = match.relative_to(workspace)
                    matches.append(str(rel_path))
                    if len(matches) >= max_results:
                        break
        else:
            # Non-recursive glob
            for match in base_path.glob(pattern):
                if match.is_file():
                    rel_path = match.relative_to(workspace)
                    matches.append(str(rel_path))
                    if len(matches) >= max_results:
                        break

        if not matches:
            return f"No files found matching pattern: {pattern}"

        result_text = "\n".join(f"- {m}" for m in sorted(matches))
        truncated = f" (showing {len(matches)}, more available)" if len(matches) == max_results else ""
        return f"## Files matching '{pattern}'{truncated}\n\n{result_text}"
    except Exception as e:
        return f"Error: Failed to find files: {str(e)[:200]}"


async def _execute_grep_files(
    pattern: str,
    file_pattern: str,
    max_results: int,
    context_lines: int,
    client_id: str,
    project_id: str | None,
) -> str:
    """Search for text pattern in files."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    if not pattern:
        return "Error: pattern required."

    try:
        # Use grep command for efficient search
        cmd = ["grep", "-r", "-n"]  # recursive, line numbers

        # Add context lines
        if context_lines > 0:
            cmd.extend(["-C", str(context_lines)])

        # Add file pattern
        if file_pattern != "*":
            cmd.extend(["--include", file_pattern])

        cmd.extend([pattern, "."])

        result = subprocess.run(
            cmd,
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=30,
        )

        # grep returns 1 if no matches, which is not an error
        if result.returncode not in (0, 1):
            return f"Error: grep failed: {result.stderr}"

        if not result.stdout.strip():
            return f"No matches found for pattern: {pattern}"

        lines = result.stdout.strip().split("\n")
        total_matches = len(lines)

        if total_matches > max_results:
            lines = lines[:max_results]
            truncated_msg = f"\n\n... (showing {max_results}/{total_matches} matches)"
        else:
            truncated_msg = ""

        matches_text = "\n".join(lines)
        return f"## Matches for '{pattern}' in {file_pattern}{truncated_msg}\n\n```\n{matches_text}\n```"
    except subprocess.TimeoutExpired:
        return "Error: grep timed out after 30s"
    except Exception as e:
        return f"Error: Failed to search files: {str(e)[:200]}"


async def _execute_file_info(
    path: str,
    client_id: str,
    project_id: str | None,
) -> str:
    """Get file/directory metadata."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    if not path:
        return "Error: path required."

    try:
        # Security: prevent path traversal
        if ".." in path or path.startswith("/"):
            return "Error: Invalid path (path traversal detected)."

        full_path = Path(workspace) / path
        if not full_path.exists():
            return f"Error: Path not found: {path}"

        stat = full_path.stat()
        item_type = "Directory" if full_path.is_dir() else "File"

        # Format size
        size_bytes = stat.st_size
        if size_bytes < 1024:
            size_str = f"{size_bytes} bytes"
        elif size_bytes < 1024 * 1024:
            size_str = f"{size_bytes / 1024:.2f} KB"
        else:
            size_str = f"{size_bytes / (1024 * 1024):.2f} MB"

        # Format modification time
        mod_time = datetime.fromtimestamp(stat.st_mtime).strftime("%Y-%m-%d %H:%M:%S")

        # Get permissions
        perms = oct(stat.st_mode)[-3:]

        lines = [
            f"## {item_type}: {path}",
            "",
            f"Type: {item_type}",
            f"Size: {size_str}",
            f"Modified: {mod_time}",
            f"Permissions: {perms}",
        ]

        # For directories, count items
        if full_path.is_dir():
            try:
                item_count = len(list(full_path.iterdir()))
                lines.append(f"Items: {item_count}")
            except Exception:
                pass

        return "\n".join(lines)
    except Exception as e:
        return f"Error: Failed to get file info: {str(e)[:200]}"


# ========== Memory Agent Tools ==========


async def _execute_memory_store(
    subject: str,
    content: str,
    category: str = "fact",
    priority: str = "normal",
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Store a fact/decision into Memory Agent's LQM + KB write buffer."""
    if not subject.strip():
        return "Error: Subject cannot be empty."
    if not content.strip():
        return "Error: Content cannot be empty."

    try:
        from app.memory.agent import _get_or_create_lqm
        from app.memory.models import PendingWrite, WritePriority

        priority_map = {
            "critical": WritePriority.CRITICAL,
            "high": WritePriority.HIGH,
            "normal": WritePriority.NORMAL,
        }
        write_priority = priority_map.get(priority, WritePriority.NORMAL)

        lqm = _get_or_create_lqm()

        # Add to active affair key_facts if one exists
        active = lqm.get_active_affair(client_id)
        if active:
            active.key_facts[subject] = content[:500]
            lqm.store_affair(active)

        # Buffer KB write
        now = datetime.now().isoformat()
        kind_map = {
            "fact": "user_knowledge_fact",
            "decision": "user_knowledge_preference",
            "order": "user_knowledge_general",
            "deadline": "user_knowledge_general",
            "contact": "user_knowledge_personal",
            "preference": "user_knowledge_preference",
            "procedure": "user_knowledge_domain",
        }

        write = PendingWrite(
            source_urn=f"memory:{client_id}:{subject[:50]}",
            content=f"# {subject}\n\n{content}",
            kind=kind_map.get(category, "user_knowledge_fact"),
            metadata={
                "category": category,
                "subject": subject,
                "client_id": client_id,
                "project_id": project_id or "",
            },
            priority=write_priority,
            created_at=now,
        )
        await lqm.buffer_write(write)

        affair_note = f" (added to affair: {active.title})" if active else ""
        return (
            f"✓ Stored in memory: '{subject}' ({category}){affair_note}\n"
            f"Priority: {priority}\n"
            f"Available immediately for recall; will be persisted to KB."
        )
    except Exception as e:
        logger.warning("memory_store failed: %s", e)
        return f"Error storing to memory: {str(e)[:200]}"


async def _execute_memory_recall(
    query: str,
    scope: str = "all",
    client_id: str = "",
    project_id: str | None = None,
    processing_mode: str = "FOREGROUND",
) -> str:
    """Search Memory Agent's LQM + KB for relevant information."""
    if not query.strip():
        return "Error: Empty recall query."

    try:
        from app.memory.agent import _get_or_create_lqm

        lqm = _get_or_create_lqm()
        results: list[str] = []

        # Search write buffer (recent unsync'd writes)
        if scope in ("current", "all"):
            buffer_hits = lqm.search_write_buffer(query)
            for hit in buffer_hits[:3]:
                results.append(
                    f"[Memory Buffer] {hit.get('source_urn', '?')}\n{hit.get('content', '')}"
                )

        # Search active affair key_facts
        if scope in ("current", "all"):
            active = lqm.get_active_affair(client_id)
            if active:
                for key, value in active.key_facts.items():
                    if query.lower() in key.lower() or query.lower() in value.lower():
                        results.append(f"[Active Affair: {active.title}] {key}: {value}")

        # Search parked affairs
        if scope == "all":
            parked = lqm.get_parked_affairs(client_id)
            for affair in parked:
                searchable = (
                    f"{affair.title} {affair.summary} "
                    f"{' '.join(affair.key_facts.values())}"
                )
                if query.lower() in searchable.lower():
                    facts = ", ".join(f"{k}: {v}" for k, v in affair.key_facts.items())
                    results.append(
                        f"[Parked Affair: {affair.title}] {affair.summary}\nFacts: {facts}"
                    )

        # Search cache / KB
        if scope in ("all", "kb_only"):
            cached = lqm.get_cached_search(query)
            if cached is not None:
                for item in cached[:3]:
                    content = item.get("content", "")
                    source = item.get("sourceUrn", "?")
                    results.append(f"[KB Cache] {source}\n{content}")
            else:
                # KB search fallback
                try:
                    async with httpx.AsyncClient(timeout=10.0) as client:
                        resp = await client.post(
                            f"{settings.knowledgebase_url}/api/v1/retrieve",
                            json={
                                "query": query,
                                "clientId": client_id,
                                "maxResults": 3,
                            },
                            headers={"X-Ollama-Priority": "0"} if processing_mode == "FOREGROUND" else {},
                        )
                        if resp.status_code == 200:
                            kb_items = resp.json().get("items", [])
                            lqm.cache_search(query, kb_items)
                            for item in kb_items[:3]:
                                content = item.get("content", "")
                                source = item.get("sourceUrn", "?")
                                results.append(f"[KB] {source}\n{content}")
                except Exception as kb_err:
                    logger.debug("KB search in memory_recall failed: %s", kb_err)

        if not results:
            return f"No memory results found for: {query}"

        return f"## Memory Recall: {query}\n\n" + "\n\n---\n\n".join(results)
    except Exception as e:
        logger.warning("memory_recall failed: %s", e)
        return f"Error recalling from memory: {str(e)[:200]}"


async def _execute_list_affairs(
    client_id: str = "",
) -> str:
    """List all active and parked affairs from LQM."""
    try:
        from app.memory.agent import _get_or_create_lqm

        lqm = _get_or_create_lqm()
        active = lqm.get_active_affair(client_id)
        parked = lqm.get_parked_affairs(client_id)

        if not active and not parked:
            return "No affairs currently tracked."

        lines = ["## Affairs Overview\n"]

        if active:
            lines.append(f"### 🟢 Active: {active.title}")
            if active.summary:
                lines.append(f"Summary: {active.summary}")
            if active.key_facts:
                lines.append("Key facts:")
                for k, v in active.key_facts.items():
                    lines.append(f"  - {k}: {v}")
            if active.pending_actions:
                lines.append("Pending actions:")
                for a in active.pending_actions:
                    lines.append(f"  - {a}")
            lines.append("")

        if parked:
            lines.append(f"### ⏸️ Parked ({len(parked)}):")
            for affair in parked:
                lines.append(f"- **{affair.title}** (ID: {affair.id})")
                if affair.summary:
                    lines.append(f"  {affair.summary[:200]}")
                if affair.pending_actions:
                    lines.append(f"  Pending: {', '.join(affair.pending_actions[:3])}")
            lines.append("")

        return "\n".join(lines)
    except Exception as e:
        logger.warning("list_affairs failed: %s", e)
        return f"Error listing affairs: {str(e)[:200]}"


# ========== Terminal Tool ==========

# Whitelist of safe command prefixes
_SAFE_COMMANDS = {
    "ls", "cat", "head", "tail", "wc", "grep", "find", "echo", "pwd",
    "tree", "file", "diff", "patch", "stat", "du", "which", "whereis",
    # Build tools
    "make", "cmake", "npm", "yarn", "pnpm", "pip", "pipenv", "poetry",
    "python", "python3", "node", "java", "javac", "kotlinc", "scalac",
    "mvn", "gradle", "cargo", "go", "rustc", "gcc", "g++", "clang",
    # Testing
    "pytest", "jest", "mocha", "junit", "cargo test", "go test",
    # Version control (read-only)
    "git status", "git log", "git diff", "git show", "git branch",
}

# Dangerous command patterns to block
_DANGEROUS_PATTERNS = [
    "rm ", "rmdir", "mv ", "dd ", "mkfs", "format",
    "> /dev/", "sudo", "su ", "chmod", "chown",
    "kill", "pkill", "killall",
    "curl", "wget", "nc ", "netcat",  # Network access
    "|bash", "|sh", "|zsh",  # Piping to shell
]


async def _execute_command(
    command: str,
    timeout: int,
    client_id: str,
    project_id: str | None,
) -> str:
    """Execute shell command in workspace."""
    workspace = await _get_workspace_path(client_id, project_id)
    if not workspace:
        return "Error: Workspace not found. Repository not cloned yet."

    if not command.strip():
        return "Error: Empty command."

    # Validate timeout
    if timeout > 300:
        timeout = 300
    elif timeout < 1:
        timeout = 30

    try:
        # Security: check for dangerous patterns
        cmd_lower = command.lower()
        for pattern in _DANGEROUS_PATTERNS:
            if pattern in cmd_lower:
                return f"Error: Dangerous command blocked: '{pattern}' not allowed."

        # Check if command starts with a safe prefix
        cmd_start = command.split()[0] if command.split() else ""
        is_safe = False
        for safe_cmd in _SAFE_COMMANDS:
            if cmd_start == safe_cmd or command.startswith(safe_cmd + " "):
                is_safe = True
                break

        if not is_safe:
            # Allow if it's a known safe pattern (e.g., "git status")
            for safe_cmd in _SAFE_COMMANDS:
                if command.startswith(safe_cmd):
                    is_safe = True
                    break

        if not is_safe:
            return (
                f"Error: Command '{cmd_start}' not in safe command whitelist. "
                f"Safe commands: {', '.join(sorted(_SAFE_COMMANDS))}"
            )

        # Execute command
        result = subprocess.run(
            command,
            shell=True,
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=timeout,
        )

        # Combine stdout and stderr
        output = ""
        if result.stdout:
            output += result.stdout
        if result.stderr:
            if output:
                output += "\n--- stderr ---\n"
            output += result.stderr

        if not output.strip():
            output = "(no output)"

        # Truncate if too long
        if len(output) > 20000:
            output = output[:20000] + "\n\n... (truncated, output too large)"

        status = "✓" if result.returncode == 0 else f"✗ (exit code {result.returncode})"
        return f"## Command: {command} {status}\n\n```\n{output}\n```"
    except subprocess.TimeoutExpired:
        return f"Error: Command timed out after {timeout}s"
    except Exception as e:
        return f"Error: Failed to execute command: {str(e)[:200]}"


# ========== Brain Tools ==========


async def _execute_brain_create_issue(
    summary: str,
    description: str | None = None,
    issue_type: str = "Task",
    priority: str | None = None,
    labels: list[str] | None = None,
    epic_key: str | None = None,
) -> str:
    """Create a Jira issue in the brain project."""
    if not summary.strip():
        return "Error: Issue summary cannot be empty."

    try:
        from app.tools.brain_client import brain_client

        issue = await brain_client.create_issue(
            summary=summary,
            description=description,
            issue_type=issue_type,
            priority=priority,
            labels=labels,
            epic_key=epic_key,
        )

        key = issue.get("key", "?")
        title = issue.get("summary", summary)
        status = issue.get("status", "?")
        return (
            f"✓ Brain issue created: {key}\n"
            f"Summary: {title}\n"
            f"Type: {issue_type}\n"
            f"Status: {status}\n"
            f"Priority: {priority or 'default'}"
        )
    except Exception as e:
        logger.warning("brain_create_issue failed: %s", e)
        return f"Error creating brain issue: {str(e)[:300]}"


async def _execute_brain_update_issue(
    issue_key: str,
    summary: str | None = None,
    description: str | None = None,
    assignee: str | None = None,
    priority: str | None = None,
    labels: list[str] | None = None,
) -> str:
    """Update a Jira issue in the brain project."""
    if not issue_key.strip():
        return "Error: Issue key cannot be empty."

    try:
        from app.tools.brain_client import brain_client

        issue = await brain_client.update_issue(
            issue_key=issue_key,
            summary=summary,
            description=description,
            assignee=assignee,
            priority=priority,
            labels=labels,
        )

        return (
            f"✓ Brain issue updated: {issue.get('key', issue_key)}\n"
            f"Summary: {issue.get('summary', '?')}\n"
            f"Status: {issue.get('status', '?')}"
        )
    except Exception as e:
        logger.warning("brain_update_issue failed: %s", e)
        return f"Error updating brain issue {issue_key}: {str(e)[:300]}"


async def _execute_brain_add_comment(
    issue_key: str,
    body: str,
) -> str:
    """Add a comment to a brain Jira issue."""
    if not issue_key.strip():
        return "Error: Issue key cannot be empty."
    if not body.strip():
        return "Error: Comment body cannot be empty."

    try:
        from app.tools.brain_client import brain_client

        comment = await brain_client.add_comment(issue_key=issue_key, body=body)

        return (
            f"✓ Comment added to {issue_key}\n"
            f"Comment ID: {comment.get('id', '?')}\n"
            f"Preview: {body[:200]}"
        )
    except Exception as e:
        logger.warning("brain_add_comment failed: %s", e)
        return f"Error adding comment to {issue_key}: {str(e)[:300]}"


async def _execute_brain_transition_issue(
    issue_key: str,
    transition_name: str,
) -> str:
    """Transition a brain issue to a new status."""
    if not issue_key.strip():
        return "Error: Issue key cannot be empty."
    if not transition_name.strip():
        return "Error: Transition name cannot be empty."

    try:
        from app.tools.brain_client import brain_client

        await brain_client.transition_issue(
            issue_key=issue_key,
            transition_name=transition_name,
        )

        return f"✓ Issue {issue_key} transitioned to '{transition_name}'"
    except Exception as e:
        logger.warning("brain_transition_issue failed: %s", e)
        return f"Error transitioning {issue_key}: {str(e)[:300]}"


async def _execute_brain_search_issues(
    jql: str,
    max_results: int = 20,
) -> str:
    """Search brain Jira issues using JQL."""
    if not jql.strip():
        return "Error: JQL query cannot be empty."

    try:
        from app.tools.brain_client import brain_client

        issues = await brain_client.search_issues(jql=jql, max_results=max_results)

        if not issues:
            return f"No brain issues found for JQL: {jql}"

        lines = [f"## Brain Issues ({len(issues)} results)\n"]
        for issue in issues:
            key = issue.get("key", "?")
            summary = issue.get("summary", "?")
            status = issue.get("status", "?")
            priority = issue.get("priority", "")
            assignee = issue.get("assignee", "")
            lines.append(f"- **{key}** [{status}] {summary}")
            if priority:
                lines.append(f"  Priority: {priority}")
            if assignee:
                lines.append(f"  Assignee: {assignee}")

        return "\n".join(lines)
    except Exception as e:
        logger.warning("brain_search_issues failed: %s", e)
        return f"Error searching brain issues: {str(e)[:300]}"


async def _execute_brain_create_page(
    title: str,
    content: str,
    parent_page_id: str | None = None,
) -> str:
    """Create a Confluence page in the brain wiki space."""
    if not title.strip():
        return "Error: Page title cannot be empty."
    if not content.strip():
        return "Error: Page content cannot be empty."

    try:
        from app.tools.brain_client import brain_client

        page = await brain_client.create_page(
            title=title,
            content=content,
            parent_page_id=parent_page_id,
        )

        return (
            f"✓ Brain page created\n"
            f"ID: {page.get('id', '?')}\n"
            f"Title: {page.get('title', title)}\n"
            f"Space: {page.get('spaceKey', '?')}"
        )
    except Exception as e:
        logger.warning("brain_create_page failed: %s", e)
        return f"Error creating brain page: {str(e)[:300]}"


async def _execute_brain_update_page(
    page_id: str,
    title: str,
    content: str,
    version: int,
) -> str:
    """Update a Confluence page in the brain wiki space."""
    if not page_id.strip():
        return "Error: Page ID cannot be empty."

    try:
        from app.tools.brain_client import brain_client

        page = await brain_client.update_page(
            page_id=page_id,
            title=title,
            content=content,
            version=version,
        )

        return (
            f"✓ Brain page updated\n"
            f"ID: {page.get('id', page_id)}\n"
            f"Title: {page.get('title', title)}\n"
            f"Version: {version}"
        )
    except Exception as e:
        logger.warning("brain_update_page failed: %s", e)
        return f"Error updating brain page {page_id}: {str(e)[:300]}"


async def _execute_brain_search_pages(
    query: str,
    max_results: int = 20,
) -> str:
    """Search Confluence pages in the brain wiki space."""
    if not query.strip():
        return "Error: Search query cannot be empty."

    try:
        from app.tools.brain_client import brain_client

        pages = await brain_client.search_pages(query=query, max_results=max_results)

        if not pages:
            return f"No brain wiki pages found for: {query}"

        lines = [f"## Brain Wiki Pages ({len(pages)} results)\n"]
        for page in pages:
            page_id = page.get("id", "?")
            title = page.get("title", "?")
            space = page.get("spaceKey", "")
            content_preview = page.get("content", "")[:200]
            lines.append(f"- **{title}** (ID: {page_id})")
            if content_preview:
                lines.append(f"  {content_preview}...")

        return "\n".join(lines)
    except Exception as e:
        logger.warning("brain_search_pages failed: %s", e)
        return f"Error searching brain pages: {str(e)[:300]}"
