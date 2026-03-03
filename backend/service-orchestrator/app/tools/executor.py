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

from app.config import settings, foreground_headers

logger = logging.getLogger(__name__)

_TIMEOUT_WEB_SEARCH = settings.timeout_web_search
_TIMEOUT_KB_SEARCH = settings.timeout_kb_search
MAX_TOOL_RESULT_CHARS = settings.max_tool_result_chars
_TOOL_EXECUTION_TIMEOUT_S = settings.tool_execution_timeout


class AskUserInterrupt(Exception):
    """Raised by ask_user tool to signal that respond node should interrupt.

    This is NOT an error — it's a control flow mechanism. The respond node
    catches this, calls langgraph interrupt(), and resumes when the user answers.
    """

    def __init__(self, question: str):
        self.question = question
        super().__init__(question)


class ApprovalRequiredInterrupt(Exception):
    """Raised when an action requires user approval (EPIC 4/5).

    Similar to AskUserInterrupt but specifically for approval flow.
    The respond node catches this and triggers a LangGraph interrupt
    with approval metadata.
    """

    def __init__(self, action: str, preview: str, payload: dict):
        self.action = action
        self.preview = preview
        self.payload = payload
        super().__init__(f"Approval required for {action}: {preview}")


# EPIC 4/5: Write tools that require approval gate evaluation
_WRITE_TOOLS_TO_APPROVAL_ACTION: dict[str, str] = {
    "kb_delete": "KB_DELETE",
    "dispatch_coding_agent": "CODING_DISPATCH",
    "store_knowledge": "KB_STORE",
}


async def _check_approval_gate(
    tool_name: str,
    arguments: dict,
    client_id: str,
    project_id: str | None,
) -> None:
    """Check if a write tool requires approval. Raises ApprovalRequiredInterrupt if so.

    Read-only tools skip this check entirely.
    """
    approval_action = _WRITE_TOOLS_TO_APPROVAL_ACTION.get(tool_name)
    if not approval_action:
        return  # Read-only tool, no approval needed

    try:
        from app.review.approval_gate import approval_gate, ApprovalDecision

        decision = await approval_gate.evaluate(
            action=approval_action,
            payload=arguments,
            risk_level="MEDIUM",
            confidence=0.8,
            client_id=client_id,
            project_id=project_id,
        )

        if decision == ApprovalDecision.DENIED:
            raise ApprovalRequiredInterrupt(
                action=approval_action,
                preview=f"DENIED: {tool_name} with {list(arguments.keys())}",
                payload=arguments,
            )

        if decision == ApprovalDecision.NEEDS_APPROVAL:
            # Build preview for user
            preview = f"{tool_name}: {str(arguments)[:200]}"
            raise ApprovalRequiredInterrupt(
                action=approval_action,
                preview=preview,
                payload=arguments,
            )

        # AUTO_APPROVED → proceed
        logger.info(
            "APPROVAL_GATE: tool=%s action=%s → AUTO_APPROVED",
            tool_name, approval_action,
        )

    except ApprovalRequiredInterrupt:
        raise  # Re-raise approval interrupt
    except Exception as e:
        # Approval gate failure is non-fatal — log and proceed (fail open for now)
        logger.warning("Approval gate check failed for %s: %s", tool_name, e)


async def execute_tool(
    tool_name: str,
    arguments: dict,
    client_id: str,
    project_id: str | None,
    processing_mode: str = "FOREGROUND",
    skip_approval: bool = False,
    group_id: str | None = None,
) -> str:
    """Execute a tool call and return the result as a string.

    Args:
        tool_name: Name of the tool to execute.
        arguments: Parsed JSON arguments from the LLM's tool_call.
        client_id: Tenant client ID for KB scoping.
        project_id: Tenant project ID for KB scoping.
        skip_approval: If True, skip approval gate (already approved by user).
        group_id: Group ID for cross-project KB visibility.

    Returns:
        Formatted result string (never raises).
    """
    logger.debug(
        "execute_tool START: tool=%s, args=%s, client_id=%s, project_id=%s, group_id=%s",
        tool_name, arguments, client_id, project_id, group_id
    )

    # ask_user is special — it raises AskUserInterrupt (not caught here).
    # The respond node catches it to trigger langgraph interrupt().
    if tool_name == "ask_user":
        question = arguments.get("question", "").strip()
        if not question:
            return "Error: Question cannot be empty."
        logger.info("ASK_USER: raising interrupt for question: %s", question)
        raise AskUserInterrupt(question)

    # EPIC 4/5: Check approval gate for write tools.
    # Raises ApprovalRequiredInterrupt if approval needed (caught by respond node / chat handler).
    if not skip_approval:
        await _check_approval_gate(tool_name, arguments, client_id, project_id)

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
                group_id=group_id,
            )
        elif tool_name == "kb_delete":
            result = await _execute_kb_delete(
                source_urn=arguments.get("source_urn", ""),
                reason=arguments.get("reason", ""),
                client_id=client_id,
            )
        elif tool_name == "store_knowledge":
            result = await _execute_store_knowledge(
                subject=arguments.get("subject", ""),
                content=arguments.get("content", ""),
                category=arguments.get("category", "general"),
                client_id=client_id,
                project_id=project_id,
                processing_mode=processing_mode,
                group_id=group_id,
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
        # ---- Environment management tools ----
        elif tool_name == "environment_list":
            result = await _execute_environment_list(
                client_id=arguments.get("client_id") or client_id,
            )
        elif tool_name == "environment_get":
            result = await _execute_environment_get(
                environment_id=arguments.get("environment_id", ""),
            )
        elif tool_name == "environment_create":
            result = await _execute_environment_create(
                client_id=client_id,
                name=arguments.get("name", ""),
                namespace=arguments.get("namespace"),
                description=arguments.get("description"),
                agent_instructions=arguments.get("agent_instructions"),
                storage_size_gi=arguments.get("storage_size_gi", 5),
            )
        elif tool_name == "environment_add_component":
            result = await _execute_environment_add_component(
                environment_id=arguments.get("environment_id", ""),
                name=arguments.get("name", ""),
                component_type=arguments.get("component_type", ""),
                image=arguments.get("image"),
                version=arguments.get("version"),
                env_vars=arguments.get("env_vars"),
                source_repo=arguments.get("source_repo"),
                source_branch=arguments.get("source_branch"),
            )
        elif tool_name == "environment_configure":
            result = await _execute_environment_configure(
                environment_id=arguments.get("environment_id", ""),
                component_name=arguments.get("component_name", ""),
                image=arguments.get("image"),
                env_vars=arguments.get("env_vars"),
                cpu_limit=arguments.get("cpu_limit"),
                memory_limit=arguments.get("memory_limit"),
            )
        elif tool_name == "environment_deploy":
            result = await _execute_environment_deploy(
                environment_id=arguments.get("environment_id", ""),
            )
        elif tool_name == "environment_stop":
            result = await _execute_environment_stop(
                environment_id=arguments.get("environment_id", ""),
            )
        elif tool_name == "environment_status":
            result = await _execute_environment_status(
                environment_id=arguments.get("environment_id", ""),
            )
        elif tool_name == "environment_sync":
            result = await _execute_environment_sync(
                environment_id=arguments.get("environment_id", ""),
            )
        elif tool_name == "environment_delete":
            result = await _execute_environment_delete(
                environment_id=arguments.get("environment_id", ""),
            )
        elif tool_name == "environment_add_property_mapping":
            result = await _execute_environment_add_property_mapping(
                environment_id=arguments.get("environment_id", ""),
                project_component=arguments.get("project_component", ""),
                property_name=arguments.get("property_name", ""),
                target_component=arguments.get("target_component", ""),
                value_template=arguments.get("value_template", ""),
            )
        elif tool_name == "environment_auto_suggest_mappings":
            result = await _execute_environment_auto_suggest_mappings(
                environment_id=arguments.get("environment_id", ""),
            )
        elif tool_name == "environment_keep_running":
            enabled = arguments.get("enabled", True)
            result = (
                "Environment will be kept running after task completion."
                if enabled else
                "Environment will be auto-stopped after task completion."
            )
        elif tool_name == "environment_clone":
            result = await _execute_environment_clone(
                environment_id=arguments.get("environment_id", ""),
                new_name=arguments.get("new_name", ""),
                new_namespace=arguments.get("new_namespace"),
                new_tier=arguments.get("new_tier"),
                target_client_id=arguments.get("target_client_id"),
                target_project_id=arguments.get("target_project_id"),
            )
        elif tool_name == "task_queue_inspect":
            result = await _execute_task_queue_inspect(
                client_id=arguments.get("client_id"),
                limit=arguments.get("limit", 20),
            )
        elif tool_name == "task_queue_set_priority":
            result = await _execute_task_queue_set_priority(
                task_id=arguments["task_id"],
                priority_score=arguments["priority_score"],
            )
        # ---- Project management tools ----
        elif tool_name == "create_client":
            result = await _execute_create_client(
                name=arguments.get("name", ""),
                description=arguments.get("description", ""),
            )
        elif tool_name == "create_project":
            result = await _execute_create_project(
                client_id=arguments.get("client_id", ""),
                name=arguments.get("name", ""),
                description=arguments.get("description", ""),
            )
        elif tool_name == "create_connection":
            result = await _execute_create_connection(
                name=arguments.get("name", ""),
                provider=arguments.get("provider", ""),
                auth_type=arguments.get("auth_type", "BEARER"),
                base_url=arguments.get("base_url", ""),
                bearer_token=arguments.get("bearer_token", ""),
                is_cloud=arguments.get("is_cloud", False),
                client_id=arguments.get("client_id", ""),
            )
        elif tool_name == "create_git_repository":
            result = await _execute_create_git_repository(
                client_id=arguments.get("client_id", ""),
                name=arguments.get("name", ""),
                description=arguments.get("description", ""),
                connection_id=arguments.get("connection_id", ""),
                is_private=arguments.get("is_private", True),
            )
        elif tool_name == "update_project":
            result = await _execute_update_project(
                project_id=arguments.get("project_id", ""),
                description=arguments.get("description", ""),
                git_remote_url=arguments.get("git_remote_url", ""),
            )
        elif tool_name == "init_workspace":
            result = await _execute_init_workspace(
                project_id=arguments.get("project_id", ""),
            )
        elif tool_name == "list_templates":
            result = await _execute_list_templates()
        else:
            result = f"Error: Unknown tool '{tool_name}'."

        result = _truncate_result(result, tool_name)
        logger.debug("execute_tool END: tool=%s, result_len=%d, result=%s", tool_name, len(result), result[:500])
        return result
    except Exception as e:
        logger.exception("Tool execution failed: %s", tool_name)
        result = f"Error executing {tool_name}: {str(e)[:300]}"
        logger.debug("execute_tool ERROR: tool=%s, result=%s", tool_name, result)
        return result


def _truncate_result(result: str, tool_name: str) -> str:
    """W-11: Truncate tool result to MAX_TOOL_RESULT_CHARS.

    Preserves beginning and end of content for context, inserts truncation marker.
    """
    if len(result) <= MAX_TOOL_RESULT_CHARS:
        return result

    # Keep 70% from start, 20% from end, 10% for marker
    head_chars = int(MAX_TOOL_RESULT_CHARS * 0.70)
    tail_chars = int(MAX_TOOL_RESULT_CHARS * 0.20)
    truncated_chars = len(result) - head_chars - tail_chars

    logger.info(
        "TOOL_RESULT_TRUNCATED | tool=%s | original=%d chars | truncated=%d chars",
        tool_name, len(result), truncated_chars,
    )

    return (
        result[:head_chars]
        + f"\n\n... [TRUNCATED {truncated_chars} chars — result too large] ...\n\n"
        + result[-tail_chars:]
    )


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
    group_id: str | None = None,
) -> str:
    """Search the Knowledge Base via POST /api/v1/retrieve.

    Reuses the same pattern as app/kb/prefetch.py.
    When group_id is provided, KB returns results from all projects in the group.
    """
    if not query.strip():
        return "Error: Empty KB search query."

    logger.info("kb_search: query=%r clientId=%s projectId=%s groupId=%s maxResults=%d",
                query[:120], client_id, project_id, group_id, max_results)

    url = f"{settings.knowledgebase_url}/api/v1/retrieve"
    payload = {
        "query": query,
        "clientId": client_id,
        "projectId": project_id,
        "maxResults": max_results,
        "minConfidence": 0.3,
        "expandGraph": True,
    }
    if group_id:
        payload["groupId"] = group_id

    headers = foreground_headers(processing_mode)

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
    if items:
        result_summary = "; ".join(
            f"[{it.get('score', 0):.2f}] {it.get('sourceUrn', '?')[:80]}"
            for it in items[:max_results]
        )
        logger.info("kb_search: query=%r → %d results: %s", query[:80], len(items), result_summary)
    else:
        logger.info("kb_search: query=%r → 0 results", query[:80])
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


async def _execute_kb_delete(
    source_urn: str,
    reason: str,
    client_id: str = "",
) -> str:
    """Delete incorrect/outdated KB entries via POST /purge.

    Calls the KB write service purge endpoint which removes:
    - All Weaviate RAG chunks matching sourceUrn
    - References from ArangoDB graph nodes/edges
    - Orphaned nodes/edges with no remaining evidence
    """
    if not source_urn.strip():
        return "Error: source_urn cannot be empty. Use kb_search first to find the sourceUrn."
    if not reason.strip():
        return "Error: reason cannot be empty. Explain why the entry is being deleted."

    logger.info("kb_delete: sourceUrn=%r reason=%r clientId=%s", source_urn, reason[:100], client_id)

    kb_write_url = settings.knowledgebase_write_url or settings.knowledgebase_url
    url = f"{kb_write_url}/purge"

    payload = {
        "sourceUrn": source_urn,
        "clientId": client_id,
    }

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
    except httpx.TimeoutException:
        return f"Error: KB delete timed out after 15s for sourceUrn: {source_urn}"
    except httpx.HTTPStatusError as e:
        return f"Error: KB purge returned HTTP {e.response.status_code} for sourceUrn: {source_urn}"
    except Exception as e:
        return f"Error: KB delete failed: {str(e)[:200]}"

    chunks_deleted = data.get("chunks_deleted", 0)
    nodes_deleted = data.get("nodes_deleted", 0)

    logger.info(
        "kb_delete OK: sourceUrn=%r chunks=%d nodes=%d reason=%r",
        source_urn, chunks_deleted, nodes_deleted, reason[:100],
    )

    return (
        f"KB entry deleted successfully.\n"
        f"Source: {source_urn}\n"
        f"Chunks removed: {chunks_deleted}\n"
        f"Graph nodes removed: {nodes_deleted}\n"
        f"Reason: {reason}\n"
        f"The incorrect information has been purged from the Knowledge Base."
    )


async def _execute_store_knowledge(
    subject: str,
    content: str,
    category: str = "general",
    client_id: str = "",
    project_id: str | None = None,
    processing_mode: str = "FOREGROUND",
    group_id: str | None = None,
) -> str:
    """Store new knowledge into the Knowledge Base via POST /api/v1/ingest.

    Stores user-provided facts, definitions, and information for future reference.
    Uses the write endpoint which routes to jervis-knowledgebase-write service.
    """
    if not subject.strip():
        return "Error: Subject cannot be empty when storing knowledge."
    if not content.strip():
        return "Error: Content cannot be empty when storing knowledge."

    # Anti-dump guard: reject oversized content (model tries to store entire user messages)
    if len(content) > 2000:
        return (
            f"Error: Content too long ({len(content)} chars, max 2000). "
            "Store only key facts in 1-2 sentences. Do NOT dump the user's entire message. "
            "Summarize the essential information instead."
        )

    # EPIC 14-S3: Check for contradictions before writing
    try:
        from app.guard.contradiction_detector import check_contradictions, ConflictSeverity
        contradiction = await check_contradictions(subject, content, client_id, project_id)
        if contradiction.severity == ConflictSeverity.CONFLICT:
            return (
                f"Warning: KB contradiction detected!\n{contradiction.message}\n\n"
                "The new knowledge was NOT stored. Please resolve the contradiction first:\n"
                "- Use kb_delete to remove outdated entries, then retry store_knowledge.\n"
                "- Or rephrase the new knowledge to be consistent with existing data."
            )
        elif contradiction.severity == ConflictSeverity.WARNING:
            content = f"{content}\n\n[Note: Potential conflict with existing KB content — {contradiction.message}]"
    except Exception:
        pass  # Fail open — don't block writes on check failure

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
    if group_id:
        payload["groupId"] = group_id

    headers = foreground_headers(processing_mode)

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
        logger.error("Failed to get workspace path: %s", e)
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
            except PermissionError:
                lines.append("Items: (permission denied)")

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
    logger.info(
        "memory_store: subject=%r category=%s priority=%s clientId=%s projectId=%s",
        subject[:80], category, priority, client_id, project_id,
    )
    if not subject.strip():
        return "Error: Subject cannot be empty."
    if not content.strip():
        return "Error: Content cannot be empty."

    # Anti-dump guard: reject oversized content (model tries to store entire user messages)
    if len(content) > 2000:
        return (
            f"Error: Content too long ({len(content)} chars, max 2000). "
            "Store only key facts in 1-2 sentences. Do NOT dump the user's entire message. "
            "Summarize the essential information instead."
        )

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
        logger.info(
            "memory_store OK: subject=%r category=%s urn=%s",
            subject[:80], category, write.source_urn,
        )
        return (
            f"✓ Stored in memory: '{subject}' ({category}){affair_note}\n"
            f"Priority: {priority}\n"
            f"Available immediately for recall; will be persisted to KB."
        )
    except Exception as e:
        logger.warning("memory_store FAILED: subject=%r error=%s", subject[:80], e)
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
                            headers=foreground_headers(processing_mode),
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


# ============================================================
# Environment management tools
# ============================================================

_KOTLIN_INTERNAL_URL = settings.kotlin_server_url


async def _execute_environment_list(client_id: str) -> str:
    """List environments, optionally filtered by client."""
    params = {}
    if client_id:
        params["clientId"] = client_id
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(f"{_KOTLIN_INTERNAL_URL}/internal/environments", params=params)
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            envs = resp.json()
            if not envs:
                return "No environments found."
            lines = []
            for env in envs:
                comps = env.get("components", [])
                infra = sum(1 for c in comps if c.get("type") != "PROJECT")
                apps = len(comps) - infra
                lines.append(
                    f"- {env['name']} (id={env['id']})\n"
                    f"  ns={env['namespace']}, state={env['state']}, "
                    f"components: {len(comps)} ({infra} infra, {apps} app)"
                )
            return "\n".join(lines)
    except Exception as e:
        return f"Error listing environments: {str(e)[:300]}"


async def _execute_environment_get(environment_id: str) -> str:
    """Get detailed environment info."""
    if not environment_id:
        return "Error: environment_id is required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}")
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            env = resp.json()
            lines = [
                f"Environment: {env['name']} (id={env['id']})",
                f"Namespace: {env['namespace']}",
                f"State: {env['state']}",
                f"Storage: {env.get('storageSizeGi', 5)}Gi",
            ]
            for comp in env.get("components", []):
                state = comp.get("componentState", "PENDING")
                lines.append(f"  - {comp['name']} ({comp['type']}) [{state}] image={comp.get('image', 'N/A')}")
            if env.get("agentInstructions"):
                lines.append(f"\nAgent Instructions:\n{env['agentInstructions']}")
            return "\n".join(lines)
    except Exception as e:
        return f"Error getting environment: {str(e)[:300]}"


async def _execute_environment_create(
    client_id: str, name: str, namespace: str | None = None,
    tier: str | None = None, description: str | None = None,
    agent_instructions: str | None = None, storage_size_gi: int = 5,
) -> str:
    """Create a new environment."""
    if not name:
        return "Error: name is required."
    body: dict = {"clientId": client_id, "name": name, "storageSizeGi": storage_size_gi}
    if namespace:
        body["namespace"] = namespace
    if tier:
        body["tier"] = tier.upper()
    if description:
        body["description"] = description
    if agent_instructions:
        body["agentInstructions"] = agent_instructions
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/environments", json=body)
            if resp.status_code not in (200, 201):
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            env = resp.json()
            return (
                f"Created environment: {env['name']} (id={env['id']})\n"
                f"Namespace: {env['namespace']}, State: {env['state']}\n"
                f"Next: use environment_add_component, then environment_deploy."
            )
    except Exception as e:
        return f"Error creating environment: {str(e)[:300]}"


async def _execute_environment_add_component(
    environment_id: str, name: str, component_type: str,
    image: str | None = None, version: str | None = None,
    env_vars: str | None = None, source_repo: str | None = None,
    source_branch: str | None = None, dockerfile_path: str | None = None,
) -> str:
    """Add component to environment."""
    if not environment_id or not name or not component_type:
        return "Error: environment_id, name, and component_type are required."
    body: dict = {"name": name, "type": component_type.upper()}
    if image:
        body["image"] = image
    if version:
        body["version"] = version
    if env_vars:
        try:
            import json as _json
            body["envVars"] = _json.loads(env_vars)
        except Exception:
            return f"Error: Invalid JSON for env_vars: {env_vars}"
    if source_repo:
        body["sourceRepo"] = source_repo
    if source_branch:
        body["sourceBranch"] = source_branch
    if dockerfile_path:
        body["dockerfilePath"] = dockerfile_path
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}/components",
                json=body,
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            env = resp.json()
            return f"Component '{name}' added. Total components: {len(env.get('components', []))}"
    except Exception as e:
        return f"Error adding component: {str(e)[:300]}"


async def _execute_environment_configure(
    environment_id: str, component_name: str,
    image: str | None = None, env_vars: str | None = None,
    cpu_limit: str | None = None, memory_limit: str | None = None,
    source_repo: str | None = None, source_branch: str | None = None,
    dockerfile_path: str | None = None,
) -> str:
    """Update component configuration."""
    if not environment_id or not component_name:
        return "Error: environment_id and component_name are required."
    body: dict = {}
    if image:
        body["image"] = image
    if env_vars:
        try:
            import json as _json
            body["envVars"] = _json.loads(env_vars)
        except Exception:
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
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.put(
                f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}/components/{component_name}",
                json=body,
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            return f"Component '{component_name}' updated. Use environment_sync to apply changes."
    except Exception as e:
        return f"Error configuring component: {str(e)[:300]}"


async def _execute_environment_deploy(environment_id: str) -> str:
    """Provision/deploy environment to K8s."""
    if not environment_id:
        return "Error: environment_id is required."
    try:
        async with httpx.AsyncClient(timeout=120) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}/deploy")
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            env = resp.json()
            return f"Deployed: {env['name']} (ns={env['namespace']}, state={env['state']})"
    except Exception as e:
        return f"Error deploying environment: {str(e)[:300]}"


async def _execute_environment_stop(environment_id: str) -> str:
    """Stop/deprovision environment."""
    if not environment_id:
        return "Error: environment_id is required."
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}/stop")
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            env = resp.json()
            return f"Stopped: {env['name']} (state={env['state']})"
    except Exception as e:
        return f"Error stopping environment: {str(e)[:300]}"


async def _execute_environment_status(environment_id: str) -> str:
    """Get environment deployment status."""
    if not environment_id:
        return "Error: environment_id is required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}/status")
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            status = resp.json()
            lines = [f"State: {status['state']}, Namespace: {status['namespace']}"]
            for comp in status.get("componentStatuses", []):
                ready = "READY" if comp.get("ready") else "NOT READY"
                lines.append(f"  - {comp['name']}: {ready} ({comp.get('availableReplicas', 0)}/{comp.get('replicas', 0)})")
            return "\n".join(lines)
    except Exception as e:
        return f"Error getting status: {str(e)[:300]}"


async def _execute_environment_sync(environment_id: str) -> str:
    """Sync environment resources from DB to K8s."""
    if not environment_id:
        return "Error: environment_id is required."
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}/sync")
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            env = resp.json()
            return f"Synced: {env['name']} (state={env['state']})"
    except Exception as e:
        return f"Error syncing environment: {str(e)[:300]}"


async def _execute_environment_delete(environment_id: str) -> str:
    """Delete environment and its K8s resources."""
    if not environment_id:
        return "Error: environment_id is required."
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.delete(f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}")
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            return f"Environment {environment_id} deleted."
    except Exception as e:
        return f"Error deleting environment: {str(e)[:300]}"


async def _execute_environment_clone(
    environment_id: str, new_name: str, new_namespace: str | None = None,
    new_tier: str | None = None, target_client_id: str | None = None,
    target_project_id: str | None = None,
) -> str:
    """Clone environment to a new scope."""
    if not environment_id or not new_name:
        return "Error: environment_id and new_name are required."
    body: dict = {"newName": new_name}
    if new_namespace:
        body["newNamespace"] = new_namespace
    if new_tier:
        body["newTier"] = new_tier.upper()
    if target_client_id:
        body["targetClientId"] = target_client_id
    if target_project_id:
        body["targetProjectId"] = target_project_id
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}/clone",
                json=body,
            )
            if resp.status_code not in (200, 201):
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            env = resp.json()
            return (
                f"Cloned: {env['name']} (id={env['id']})\n"
                f"Tier: {env.get('tier', 'DEV')}, Namespace: {env['namespace']}\n"
                f"State: {env['state']} — use environment_deploy to provision."
            )
    except Exception as e:
        return f"Error cloning environment: {str(e)[:300]}"


async def _execute_environment_add_property_mapping(
    environment_id: str, project_component: str, property_name: str,
    target_component: str, value_template: str,
) -> str:
    """Add a property mapping to an environment."""
    if not environment_id or not project_component or not property_name or not target_component or not value_template:
        return "Error: all parameters are required (environment_id, project_component, property_name, target_component, value_template)."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}/property-mappings",
                json={
                    "projectComponentId": project_component,
                    "propertyName": property_name,
                    "targetComponentId": target_component,
                    "valueTemplate": value_template,
                },
            )
            if resp.status_code == 409:
                return f"Mapping for '{property_name}' already exists."
            if resp.status_code not in (200, 201):
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            env = resp.json()
            count = len(env.get("propertyMappings", []))
            return f"Property mapping added: {property_name} → {target_component}. Total mappings: {count}."
    except Exception as e:
        return f"Error adding property mapping: {str(e)[:300]}"


async def _execute_environment_auto_suggest_mappings(environment_id: str) -> str:
    """Auto-suggest property mappings for all PROJECT×INFRA pairs."""
    if not environment_id:
        return "Error: environment_id is required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{_KOTLIN_INTERNAL_URL}/internal/environments/{environment_id}/property-mappings/auto-suggest",
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            env = resp.json()
            added = resp.headers.get("X-Mappings-Added", "?")
            total = len(env.get("propertyMappings", []))
            return (
                f"Auto-suggested mappings for '{env['name']}'.\n"
                f"Added: {added}, Total: {total}.\n"
                f"Use environment_deploy to provision and resolve values."
            )
    except Exception as e:
        return f"Error auto-suggesting mappings: {str(e)[:300]}"


# ============================================================
# Task Queue tools (cross-project priority management)
# ============================================================


async def _execute_task_queue_inspect(
    client_id: str | None = None,
    limit: int = 20,
) -> str:
    """Inspect the background task queue via Kotlin internal API."""
    from app.tools.kotlin_client import kotlin_client

    try:
        client = await kotlin_client._get_client()
        params = {"limit": str(limit)}
        if client_id:
            params["clientId"] = client_id
        resp = await client.get("/internal/tasks/queue", params=params)
        if resp.status_code != 200:
            return f"Error: HTTP {resp.status_code}"
        tasks = resp.json()
        if not tasks:
            return "Queue is empty — no background tasks waiting."
        lines = [f"Background task queue ({len(tasks)} tasks):"]
        for t in tasks:
            lines.append(
                f"  [{t['state']}] {t['title']} "
                f"(id={t['id']}, priority={t.get('priorityScore', '50')}, "
                f"client={t['clientId'][:8]}…)"
            )
        return "\n".join(lines)
    except Exception as e:
        return f"Error inspecting queue: {str(e)[:300]}"


async def _execute_task_queue_set_priority(
    task_id: str,
    priority_score: int,
) -> str:
    """Set priority score for a task via Kotlin internal API."""
    from app.tools.kotlin_client import kotlin_client

    try:
        client = await kotlin_client._get_client()
        resp = await client.post(
            f"/internal/tasks/{task_id}/priority",
            json={"priorityScore": priority_score},
        )
        if resp.status_code != 200:
            return f"Error: HTTP {resp.status_code} — {resp.text}"
        return f"Priority set to {priority_score} for task {task_id}."
    except Exception as e:
        return f"Error setting priority: {str(e)[:300]}"


# ---- Project management tools ----


async def _execute_create_client(name: str, description: str = "") -> str:
    """Create a client via Kotlin internal API."""
    if not name:
        return "Error: name is required."
    body: dict = {"name": name}
    if description:
        body["description"] = description
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/clients", json=body)
            if resp.status_code not in (200, 201):
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            data = resp.json()
            return f"Client created: {data.get('name', name)} (id={data.get('id', '?')})"
    except Exception as e:
        return f"Error creating client: {str(e)[:300]}"


async def _execute_create_project(client_id: str, name: str, description: str = "") -> str:
    """Create a project via Kotlin internal API."""
    if not client_id or not name:
        return "Error: client_id and name are required."
    body: dict = {"clientId": client_id, "name": name}
    if description:
        body["description"] = description
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/projects", json=body)
            if resp.status_code not in (200, 201):
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            data = resp.json()
            return (
                f"Project created: {data.get('name', name)} "
                f"(id={data.get('id', '?')}, clientId={client_id})"
            )
    except Exception as e:
        return f"Error creating project: {str(e)[:300]}"


async def _execute_create_connection(
    name: str, provider: str, auth_type: str = "BEARER",
    base_url: str = "", bearer_token: str = "",
    is_cloud: bool = False, client_id: str = "",
) -> str:
    """Create a connection via Kotlin internal API, optionally linking to a client."""
    if not name or not provider:
        return "Error: name and provider are required."
    body: dict = {
        "name": name,
        "provider": provider,
        "protocol": "HTTP",
        "authType": auth_type,
        "isCloud": is_cloud,
    }
    if base_url:
        body["baseUrl"] = base_url
    if bearer_token:
        body["bearerToken"] = bearer_token
    if client_id:
        body["clientId"] = client_id
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/connections", json=body)
            if resp.status_code not in (200, 201):
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            data = resp.json()
            return (
                f"Connection created: {data.get('name', name)} "
                f"(id={data.get('id', '?')}, provider={data.get('provider', provider)})"
            )
    except Exception as e:
        return f"Error creating connection: {str(e)[:300]}"


async def _execute_create_git_repository(
    client_id: str, name: str, description: str = "",
    connection_id: str = "", is_private: bool = True,
) -> str:
    """Create a git repository via Kotlin internal API."""
    if not client_id or not name:
        return "Error: client_id and name are required."
    body: dict = {"clientId": client_id, "name": name, "isPrivate": is_private}
    if description:
        body["description"] = description
    if connection_id:
        body["connectionId"] = connection_id
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/git/repos", json=body)
            if resp.status_code not in (200, 201):
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            data = resp.json()
            return (
                f"Repository created: {data.get('fullName', name)}\n"
                f"  Clone URL: {data.get('cloneUrl', '?')}\n"
                f"  Web URL: {data.get('htmlUrl', '?')}\n"
                f"  Provider: {data.get('provider', '?')}"
            )
    except Exception as e:
        return f"Error creating git repository: {str(e)[:300]}"


async def _execute_update_project(
    project_id: str, description: str = "", git_remote_url: str = "",
) -> str:
    """Update a project via Kotlin internal API."""
    if not project_id:
        return "Error: project_id is required."
    body: dict = {"projectId": project_id}
    if description:
        body["description"] = description
    if git_remote_url:
        body["gitRemoteUrl"] = git_remote_url
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.put(
                f"{_KOTLIN_INTERNAL_URL}/internal/projects/{project_id}", json=body,
            )
            if resp.status_code not in (200, 201):
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            return f"Project {project_id} updated."
    except Exception as e:
        return f"Error updating project: {str(e)[:300]}"


async def _execute_init_workspace(project_id: str) -> str:
    """Trigger workspace initialization via Kotlin internal API."""
    if not project_id:
        return "Error: project_id is required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{_KOTLIN_INTERNAL_URL}/internal/git/init-workspace",
                json={"projectId": project_id},
            )
            if resp.status_code not in (200, 201):
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            data = resp.json()
            if data.get("ok"):
                return f"Workspace init triggered for project {project_id}."
            return f"Error: {data.get('error', 'unknown')}"
    except Exception as e:
        return f"Error initializing workspace: {str(e)[:300]}"


async def _execute_list_templates() -> str:
    """List available project scaffolding templates."""
    templates = [
        "kmp — Kotlin Multiplatform + Compose (Desktop/Android/iOS/Web)",
        "spring-boot — Spring Boot 3.x (Kotlin) with Web, MongoDB, PostgreSQL",
        "kmp-spring — Full-stack: KMP Compose frontend + Spring Boot backend",
    ]
    return "Available project templates:\n" + "\n".join(f"  - {t}" for t in templates)
