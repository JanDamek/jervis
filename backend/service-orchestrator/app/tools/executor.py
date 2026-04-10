"""Tool executor for the respond node's agentic loop.

Executes tool calls returned by the LLM and returns results as strings.
Never raises exceptions — errors are returned as descriptive strings so
the LLM can decide how to proceed.
"""

from __future__ import annotations

import asyncio
import logging
import os
import subprocess
from pathlib import Path
from datetime import datetime, timezone

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
# POLICY: store_knowledge is AUTO-APPROVED — user rule "VŠE okamžitě do KB" (no friction).
# kb_delete stays under the gate — "NIKDY mazat bez potvrzení".
# dispatch_coding_agent does NOT require approval — user gave the task, agent executes.
_WRITE_TOOLS_TO_APPROVAL_ACTION: dict[str, str] = {
    "kb_delete": "KB_DELETE",
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
    task_id: str | None = None,
) -> str:
    """Execute a tool call and return the result as a string.

    Args:
        tool_name: Name of the tool to execute.
        arguments: Parsed JSON arguments from the LLM's tool_call.
        client_id: Tenant client ID for KB scoping.
        project_id: Tenant project ID for KB scoping.
        skip_approval: If True, skip approval gate (already approved by user).
        group_id: Group ID for cross-project KB visibility.
        task_id: Parent task ID (for sub-task nesting in master graph).

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
        elif tool_name == "web_fetch":
            result = await _execute_web_fetch(
                url=arguments.get("url", ""),
                max_length=arguments.get("max_length", 10000),
            )
        elif tool_name == "web_crawl":
            result = await _execute_web_crawl(
                url=arguments.get("url", ""),
                max_depth=arguments.get("max_depth", 2),
                allow_external=arguments.get("allow_external", False),
                client_id=client_id,
                project_id=project_id,
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
                target_project_name=arguments.get("target_project_name"),
            )
        elif tool_name == "create_scheduled_task":
            result = await _execute_create_scheduled_task(
                title=arguments.get("title", ""),
                description=arguments.get("description", ""),
                reason=arguments.get("reason", ""),
                schedule=arguments.get("schedule", "manual"),
                scheduled_at=arguments.get("scheduled_at"),
                urgency=arguments.get("urgency", "normal"),
                is_personal_reminder=arguments.get("is_personal_reminder", False),
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
        elif tool_name in ("get_stack_recommendations", "list_templates"):
            result = await _execute_get_stack_recommendations(
                requirements=arguments.get("requirements", ""),
            )
        # --- Issue tracker tools ---
        elif tool_name == "create_issue":
            result = await _execute_issue_create(
                client_id=client_id, project_id=project_id,
                title=arguments.get("title", ""),
                description=arguments.get("description", ""),
                labels=arguments.get("labels", ""),
            )
        elif tool_name == "update_issue":
            result = await _execute_issue_update(
                client_id=client_id, project_id=project_id,
                issue_key=arguments.get("issue_key", ""),
                title=arguments.get("title"),
                description=arguments.get("description"),
                state=arguments.get("state"),
                labels=arguments.get("labels"),
            )
        elif tool_name == "add_issue_comment":
            result = await _execute_issue_comment(
                client_id=client_id, project_id=project_id,
                issue_key=arguments.get("issue_key", ""),
                comment=arguments.get("comment", ""),
            )
        elif tool_name == "list_issues":
            result = await _execute_issue_list(
                client_id=client_id, project_id=project_id,
            )
        # --- Guidelines tools (available in graph agent + chat) ---
        elif tool_name == "get_guidelines":
            result = await _execute_get_guidelines(
                client_id=arguments.get("client_id") or client_id,
                project_id=arguments.get("project_id") or project_id,
            )
        elif tool_name == "update_guideline":
            result = await _execute_update_guideline(
                scope=arguments.get("scope", "GLOBAL"),
                category=arguments.get("category", ""),
                rules=arguments.get("rules", {}),
                client_id=arguments.get("client_id") or client_id,
                project_id=arguments.get("project_id") or project_id,
            )
        # --- Meeting tools ---
        elif tool_name == "classify_meeting":
            result = await _execute_classify_meeting(
                meeting_id=arguments.get("meeting_id", ""),
                client_id=arguments.get("client_id") or client_id,
                project_id=arguments.get("project_id") or project_id,
                title=arguments.get("title"),
            )
        elif tool_name == "list_unclassified_meetings":
            result = await _execute_list_unclassified_meetings()
        elif tool_name == "get_meeting_transcript":
            result = await kotlin_client.get_meeting_transcript(
                meeting_id=arguments.get("meeting_id", ""),
            )
        elif tool_name == "list_meetings":
            result = await kotlin_client.list_meetings(
                client_id=arguments.get("client_id") or client_id,
                project_id=arguments.get("project_id") or project_id,
                state=arguments.get("state"),
                limit=arguments.get("limit", 20),
            )
        # --- MongoDB self-management ---
        elif tool_name == "mongo_list_collections":
            result = await _execute_mongo_list_collections()
        elif tool_name == "mongo_get_document":
            result = await _execute_mongo_get_document(
                collection=arguments.get("collection", ""),
                filter_doc=arguments.get("filter"),
                limit=arguments.get("limit", 10),
            )
        elif tool_name == "mongo_update_document":
            result = await _execute_mongo_update_document(
                collection=arguments.get("collection", ""),
                filter_doc=arguments.get("filter", {}),
                update_doc=arguments.get("update", {}),
                upsert=arguments.get("upsert", False),
            )
        # --- Coding agent dispatch (graph agent + chat) ---
        elif tool_name == "dispatch_coding_agent":
            result = await _execute_dispatch_coding_agent(
                task_description=arguments.get("task_description", ""),
                client_id=arguments.get("client_id") or client_id,
                project_id=arguments.get("project_id") or project_id,
                agent_preference=arguments.get("agent_preference", "auto"),
                parent_task_id=task_id,
            )
        # --- O365 tools (Teams, Mail, Calendar, OneDrive) ---
        elif tool_name == "o365_teams_list_chats":
            result = await _execute_o365_teams_list_chats(
                client_id=arguments.get("client_id") or client_id,
                top=arguments.get("top", 20),
            )
        elif tool_name == "o365_teams_read_chat":
            result = await _execute_o365_teams_read_chat(
                client_id=arguments.get("client_id") or client_id,
                chat_id=arguments.get("chat_id", ""),
                top=arguments.get("top", 20),
            )
        elif tool_name == "o365_teams_send_message":
            result = await _execute_o365_teams_send_message(
                client_id=arguments.get("client_id") or client_id,
                chat_id=arguments.get("chat_id", ""),
                content=arguments.get("content", ""),
                content_type=arguments.get("content_type", "text"),
            )
        elif tool_name == "o365_teams_list_teams":
            result = await _execute_o365_teams_list_teams(
                client_id=arguments.get("client_id") or client_id,
            )
        elif tool_name == "o365_teams_list_channels":
            result = await _execute_o365_teams_list_channels(
                client_id=arguments.get("client_id") or client_id,
                team_id=arguments.get("team_id", ""),
            )
        elif tool_name == "o365_teams_read_channel":
            result = await _execute_o365_teams_read_channel(
                client_id=arguments.get("client_id") or client_id,
                team_id=arguments.get("team_id", ""),
                channel_id=arguments.get("channel_id", ""),
                top=arguments.get("top", 20),
            )
        elif tool_name == "o365_teams_send_channel_message":
            result = await _execute_o365_teams_send_channel_message(
                client_id=arguments.get("client_id") or client_id,
                team_id=arguments.get("team_id", ""),
                channel_id=arguments.get("channel_id", ""),
                content=arguments.get("content", ""),
            )
        elif tool_name == "o365_session_status":
            result = await _execute_o365_session_status(
                client_id=arguments.get("client_id") or client_id,
            )
        elif tool_name == "o365_mail_list":
            result = await _execute_o365_mail_list(
                client_id=arguments.get("client_id") or client_id,
                top=arguments.get("top", 20),
                folder=arguments.get("folder", "inbox"),
            )
        elif tool_name == "o365_mail_read":
            result = await _execute_o365_mail_read(
                client_id=arguments.get("client_id") or client_id,
                message_id=arguments.get("message_id", ""),
            )
        elif tool_name == "o365_mail_send":
            result = await _execute_o365_mail_send(
                client_id=arguments.get("client_id") or client_id,
                to=arguments.get("to", ""),
                subject=arguments.get("subject", ""),
                body=arguments.get("body", ""),
                cc=arguments.get("cc", ""),
                content_type=arguments.get("content_type", "text"),
            )
        elif tool_name == "o365_calendar_events":
            result = await _execute_o365_calendar_events(
                client_id=arguments.get("client_id") or client_id,
                top=arguments.get("top", 20),
                start_date_time=arguments.get("start_date_time", ""),
                end_date_time=arguments.get("end_date_time", ""),
            )
        elif tool_name == "o365_calendar_create":
            result = await _execute_o365_calendar_create(
                client_id=arguments.get("client_id") or client_id,
                subject=arguments.get("subject", ""),
                start_date_time=arguments.get("start_date_time", ""),
                start_time_zone=arguments.get("start_time_zone", ""),
                end_date_time=arguments.get("end_date_time", ""),
                end_time_zone=arguments.get("end_time_zone", ""),
                location=arguments.get("location", ""),
                body=arguments.get("body", ""),
                attendees=arguments.get("attendees", ""),
                is_online_meeting=arguments.get("is_online_meeting", False),
            )
        elif tool_name == "o365_files_list":
            result = await _execute_o365_files_list(
                client_id=arguments.get("client_id") or client_id,
                path=arguments.get("path", "root"),
                top=arguments.get("top", 50),
            )
        elif tool_name == "o365_files_download":
            result = await _execute_o365_files_download(
                client_id=arguments.get("client_id") or client_id,
                item_id=arguments.get("item_id", ""),
            )
        elif tool_name == "o365_files_search":
            result = await _execute_o365_files_search(
                client_id=arguments.get("client_id") or client_id,
                query=arguments.get("query", ""),
                top=arguments.get("top", 25),
            )
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
    """W-11: Return tool result as-is (no truncation).

    Previously truncated to MAX_TOOL_RESULT_CHARS, but truncation is now forbidden —
    results must always be preserved in full. Context budget management in _helpers.py
    handles overall prompt size; routing to OpenRouter handles large contexts (>48k).
    """
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
        "engines": "duckduckgo,brave",
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
        lines.append(f"**Result {i}: {title}**")
        lines.append(f"URL: {url_str}")
        if content:
            lines.append(content)
        lines.append("")

    return "\n".join(lines)


async def _execute_web_fetch(url: str, max_length: int = 10000) -> str:
    """Fetch a web page and return its text content (HTML stripped).

    Uses httpx to GET the URL, then strips HTML tags to extract readable text.
    Useful for reading existing websites, checking if URLs are live, etc.
    """
    import re as _re

    if not url.strip():
        return "Error: Empty URL."

    if not url.startswith(("http://", "https://")):
        return f"Error: Invalid URL (must start with http:// or https://): {url}"

    try:
        async with httpx.AsyncClient(
            timeout=15.0,
            follow_redirects=True,
            headers={
                "User-Agent": "Mozilla/5.0 (compatible; JervisBot/1.0)",
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            },
        ) as client:
            resp = await client.get(url)
            resp.raise_for_status()
    except httpx.TimeoutException:
        return f"Error: Timeout fetching {url}"
    except httpx.HTTPStatusError as e:
        return f"Error: HTTP {e.response.status_code} fetching {url}"
    except Exception as e:
        return f"Error: Failed to fetch {url}: {str(e)[:200]}"

    content_type = resp.headers.get("content-type", "")
    raw = resp.text

    # Strip HTML tags to get readable text
    if "html" in content_type or raw.strip().startswith("<"):
        # Remove script/style blocks
        text = _re.sub(r"<(script|style|noscript)[^>]*>.*?</\1>", "", raw, flags=_re.DOTALL | _re.IGNORECASE)
        # Remove HTML tags
        text = _re.sub(r"<[^>]+>", " ", text)
        # Decode common HTML entities
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        text = text.replace("&nbsp;", " ").replace("&quot;", '"').replace("&#39;", "'")
        # Collapse whitespace
        text = _re.sub(r"\s+", " ", text).strip()
    else:
        text = raw.strip()

    if not text:
        return f"Page at {url} returned empty content."

    # Truncate to max_length
    if len(text) > max_length:
        text = text[:max_length] + f"\n\n[... truncated at {max_length} chars, total {len(text)}]"

    return f"Content of {url}:\n\n{text}"


async def _execute_web_crawl(
    url: str,
    max_depth: int = 2,
    allow_external: bool = False,
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Crawl a website and ingest content into KB via KB service /crawl endpoint."""
    if not url.strip():
        return "Error: Empty URL."
    if not url.startswith(("http://", "https://")):
        return f"Error: Invalid URL: {url}"

    kb_url = f"{settings.kb_service_url}/crawl"
    payload = {
        "url": url,
        "maxDepth": max_depth,
        "allowExternalDomains": allow_external,
        "clientId": client_id or "",
        "projectId": project_id or "",
    }

    try:
        async with httpx.AsyncClient(timeout=120.0) as client:
            resp = await client.post(kb_url, json=payload)
            resp.raise_for_status()
            data = resp.json()
    except httpx.TimeoutException:
        return f"Error: Web crawl timed out after 120s for {url}"
    except httpx.HTTPStatusError as e:
        return f"Error: KB crawl returned HTTP {e.response.status_code}: {e.response.text[:200]}"
    except Exception as e:
        return f"Error: Web crawl failed: {str(e)[:200]}"

    pages = data.get("pages_indexed", data.get("pagesIndexed", 0))
    chunks = data.get("chunks_created", data.get("chunksCreated", 0))
    skipped = data.get("pages_skipped", data.get("pagesSkipped", 0))

    return (
        f"Web crawl complete for {url}:\n"
        f"- Pages indexed: {pages}\n"
        f"- Chunks created: {chunks}\n"
        f"- Pages skipped (already indexed): {skipped}\n"
        f"- Depth: {max_depth}\n"
        f"Content is now searchable via kb_search."
    )


async def _execute_kb_search(
    query: str,
    max_results: int = 5,
    client_id: str = "",
    project_id: str | None = None,
    processing_mode: str = "FOREGROUND",
    group_id: str | None = None,
) -> str:
    """Search the Knowledge Base via RAG + Thought Map traversal.

    Runs both in parallel:
    1. RAG flat search (POST /api/v1/retrieve) — cosine similarity
    2. Thought Map traverse (POST /thoughts/traverse) — spreading activation

    Results are merged: Thought Map anchored knowledge boosts or supplements RAG.
    When group_id is provided, KB returns results from all projects in the group.
    """
    if not query.strip():
        return "Error: Empty KB search query."

    logger.info("kb_search: query=%r clientId=%s projectId=%s groupId=%s maxResults=%d",
                query[:120], client_id, project_id, group_id, max_results)

    headers = foreground_headers(processing_mode)

    # Run RAG search and Thought Map traverse in parallel
    rag_task = asyncio.create_task(_kb_rag_search(query, max_results, client_id, project_id, group_id, headers))
    thought_task = asyncio.create_task(_kb_thought_search(query, client_id, project_id, group_id, headers))

    rag_items = await rag_task
    thought_items = await thought_task

    # Merge: RAG items first, then unique Thought Map items not already in RAG
    seen_sources = {item.get("sourceUrn", "") for item in rag_items}
    merged = list(rag_items)
    thought_added = 0
    for item in thought_items:
        source = item.get("sourceUrn", "")
        if source and source not in seen_sources:
            merged.append(item)
            seen_sources.add(source)
            thought_added += 1

    if merged:
        # Sort: RAG items first (have actual content), thought-anchors second (metadata only).
        # Within each group, sort by score descending.
        # Thought-anchors (sourceUrn starts with "thought-anchor:") are supplementary context,
        # not primary results — they should never push out actual documents.
        def _sort_key(item):
            is_thought = item.get("sourceUrn", "").startswith("thought-anchor:")
            return (1 if is_thought else 0, -item.get("score", 0))
        merged.sort(key=_sort_key)

        # If project-scoped RAG results have low confidence, also search at client scope
        rag_top_score = max((it.get("score", 0) for it in rag_items), default=0)
        if project_id and client_id and rag_top_score < 0.5:
            logger.info("kb_search: low confidence (top=%.2f), adding client-scope search...", rag_top_score)
            client_rag = await _kb_rag_search(query, max_results, client_id, None, None, headers)
            seen_sources_client = {item.get("sourceUrn", "") for item in merged}
            for item in client_rag:
                source = item.get("sourceUrn", "")
                if source and source not in seen_sources_client:
                    merged.append(item)
                    seen_sources_client.add(source)
            merged.sort(key=lambda x: x.get("score", 0), reverse=True)

        merged = merged[:max_results]
        result_summary = "; ".join(
            f"[{it.get('score', 0):.2f}] {it.get('sourceUrn', '?')[:80]}"
            for it in merged
        )
        logger.info("kb_search: query=%r → %d results (rag=%d, thought_added=%d): %s",
                     query[:80], len(merged), len(rag_items), thought_added, result_summary)
    else:
        # Cross-context fallback: search globally when scoped search returns nothing
        if client_id:
            logger.info("kb_search: query=%r → 0 results in scope (client=%s), trying global fallback...",
                        query[:80], client_id)
            global_rag = await _kb_rag_search(query, max_results, "", None, None, headers)
            if global_rag:
                # Found results in different scope — tell the LLM
                found_clients = set()
                for item in global_rag:
                    src_client = item.get("clientId", "")
                    if src_client and src_client != client_id:
                        found_clients.add(src_client)
                if found_clients:
                    client_names = ", ".join(found_clients)
                    logger.info("kb_search: global fallback found %d results in other clients: %s",
                                len(global_rag), client_names)
                    lines = [f"⚠️ Žádné výsledky v aktuálním kontextu. Nalezeny výsledky v jiných klientech: {client_names}\n"]
                    lines.append("Zeptej se uživatele zda chce přepnout kontext, nebo použij tyto výsledky:\n")
                    for i, item in enumerate(global_rag[:3], 1):
                        lines.append(f"**Result {i}** (client: {item.get('clientId', '?')})")
                        lines.append(f"Source: {item.get('sourceUrn', '?')[:80]}")
                        content = item.get("content", "")
                        if content:
                            lines.append(content[:500])
                        lines.append("")
                    return "\n".join(lines)
        logger.info("kb_search: query=%r → 0 results (including global)", query[:80])
        return f"No Knowledge Base results found for: {query}"

    lines = [f"Knowledge Base results for: {query}\n"]
    for i, item in enumerate(merged, 1):
        source = item.get("sourceUrn", "unknown")
        content = item.get("content", "")
        score = item.get("score", 0)
        kind = item.get("kind", "")
        origin = item.get("origin", "rag")
        origin_tag = " [thought-map]" if origin == "thought" else ""
        lines.append(f"**Result {i}** (score: {score:.2f}, kind: {kind}{origin_tag})")
        lines.append(f"Source: {source}")
        if content:
            lines.append(content)
        lines.append("")

    return "\n".join(lines)


async def _kb_rag_search(
    query: str, max_results: int, client_id: str,
    project_id: str | None, group_id: str | None, headers: dict,
) -> list[dict]:
    """Flat RAG search via POST /api/v1/retrieve."""
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

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_KB_SEARCH) as client:
            resp = await client.post(url, json=payload, headers=headers)
            resp.raise_for_status()
            data = resp.json()
        items = data.get("items", [])
        for item in items:
            item["origin"] = "rag"
        return items
    except Exception as e:
        logger.warning("kb_search: RAG failed: %s", e)
        return []


async def _kb_thought_search(
    query: str, client_id: str,
    project_id: str | None, group_id: str | None, headers: dict,
) -> list[dict]:
    """Thought Map traverse via POST /thoughts/traverse — returns anchored knowledge."""
    if not client_id:
        return []

    url = f"{settings.knowledgebase_url}/api/v1/thoughts/traverse"
    payload = {
        "query": query,
        "clientId": client_id,
        "projectId": project_id or "",
        "groupId": group_id or "",
        "maxResults": 10,
        "floor": 0.1,
        "maxDepth": 2,
        "entryTopK": 3,
    }

    try:
        timeout = httpx.Timeout(8.0, connect=3.0)  # Short — don't slow down kb_search
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(url, json=payload, headers=headers)
            resp.raise_for_status()
            data = resp.json()
    except Exception as e:
        logger.debug("kb_search: Thought Map traverse failed (non-critical): %s", e)
        return []

    # Convert anchored knowledge nodes to items matching RAG format
    items = []
    for k in data.get("knowledge", []):
        node = k.get("node", {})
        items.append({
            "content": node.get("description", ""),
            "score": k.get("pathWeight", 0.5),
            "sourceUrn": node.get("sourceUrn", f"thought-anchor:{node.get('_key', '?')}"),
            "kind": node.get("type", ""),
            "origin": "thought",
        })

    # Also include thought summaries as results
    for t in data.get("thoughts", []):
        node = t.get("node", {})
        summary = node.get("summary", "")
        if summary:
            items.append({
                "content": f"[Thought: {node.get('label', '?')}] {summary}",
                "score": t.get("pathWeight", 0.3),
                "sourceUrn": f"thought:{node.get('_key', '?')}",
                "kind": node.get("type", "concept"),
                "origin": "thought",
            })

    return items


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
    target_project_name: str | None = None,
) -> str:
    """Store new knowledge into the Knowledge Base via POST /api/v1/ingest.

    Stores user-provided facts, definitions, and information for future reference.
    Uses the write endpoint which routes to jervis-knowledgebase-write service.
    When target_project_name is set, also stores a copy tagged for the target project.
    """
    if not subject.strip():
        return "Error: Subject cannot be empty when storing knowledge."
    if not content.strip():
        return "Error: Content cannot be empty when storing knowledge."

    # Log large content but don't reject — LLM compression handles size during memory parking
    if len(content) > 5000:
        logger.warning("Large content stored (%d chars) for subject '%s'", len(content), subject)

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

    # Generate STABLE sourceUrn — same subject+category+client deduplicates via
    # KB upsert (content-hash check). The old "{timestamp}" suffix made every
    # store unique → 10× duplicates for the same content (e.g., "Řešení
    # průběžného testu" stored on every retry of a multi-vertex graph).
    timestamp = datetime.now(timezone.utc).isoformat()
    source_urn = f"user-knowledge:{category}:{subject}"

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

    # Cross-project storage: if target_project_name is set, also store for that project
    cross_project_note = ""
    if target_project_name:
        try:
            # Look up target project by name
            async with httpx.AsyncClient(timeout=10.0) as client2:
                resp2 = await client2.get(
                    f"{_KOTLIN_INTERNAL_URL}/internal/projects",
                    params={"clientId": client_id},
                )
                if resp2.status_code == 200:
                    projects = resp2.json()
                    target_proj = next(
                        (p for p in projects if target_project_name.lower() in p.get("name", "").lower()),
                        None,
                    )
                    if target_proj:
                        target_pid = target_proj.get("id", "")
                        cross_payload = {
                            "clientId": client_id,
                            "projectId": target_pid,
                            "sourceUrn": f"cross-ref:{category}:{subject}:{timestamp}",
                            "kind": f"user_knowledge_{category}",
                            "content": f"# {subject} (cross-reference from other context)\n\n{content}",
                            "metadata": {
                                "subject": subject,
                                "category": category,
                                "stored_at": timestamp,
                                "source": "cross_project_reference",
                                "source_project_id": project_id or "",
                            },
                        }
                        resp3 = await client2.post(
                            f"{kb_write_url}/api/v1/ingest",
                            json=cross_payload,
                            headers=headers,
                        )
                        if resp3.status_code in (200, 201, 202):
                            cross_project_note = (
                                f"\n✓ Also stored for project '{target_project_name}' (cross-reference)."
                            )
                        else:
                            cross_project_note = (
                                f"\n⚠ Cross-project store for '{target_project_name}' failed: {resp3.status_code}"
                            )
                    else:
                        cross_project_note = (
                            f"\n⚠ Project '{target_project_name}' not found — "
                            "cross-reference not stored. Knowledge is still saved in current project."
                        )
        except Exception as e:
            cross_project_note = f"\n⚠ Cross-project store failed: {str(e)[:100]}"

    # Success response
    chunk_count = data.get("chunk_count", 0)
    return (
        f"✓ Knowledge stored successfully in KB!\n"
        f"Subject: {subject}\n"
        f"Category: {category}\n"
        f"Chunks created: {chunk_count}\n"
        f"This information is now available for future queries.{cross_project_note}\n"
        f"If there are other parts to handle, continue with those now."
    )


async def _execute_create_scheduled_task(
    title: str,
    description: str,
    reason: str,
    schedule: str = "manual",
    scheduled_at: str | None = None,
    urgency: str = "normal",
    is_personal_reminder: bool = False,
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

    # Resolve user timezone for scheduling
    from app.tools.kotlin_client import kotlin_client
    user_timezone = "Europe/Prague"
    try:
        user_timezone = await kotlin_client.get_user_timezone()
    except Exception:
        pass

    # Resolve scheduling: explicit ISO time takes precedence
    scheduled_at_iso = None
    scheduled_local_time = None  # For personal reminders (followUserTimezone)
    if scheduled_at:
        # Validate and normalize ISO-8601 datetime
        from datetime import datetime, timezone
        import zoneinfo
        try:
            dt = datetime.fromisoformat(scheduled_at.replace("Z", "+00:00"))
            if dt.tzinfo is None:
                # Assume user's current timezone
                dt = dt.replace(tzinfo=zoneinfo.ZoneInfo(user_timezone))

            if is_personal_reminder:
                # Store local time for floating-timezone resolution
                local_dt = dt.astimezone(zoneinfo.ZoneInfo(user_timezone))
                scheduled_local_time = local_dt.strftime("%Y-%m-%dT%H:%M:%S")

            scheduled_at_iso = dt.astimezone(timezone.utc).isoformat()
        except (ValueError, KeyError) as e:
            return f"Error: Invalid scheduled_at format '{scheduled_at}': {e}"
    else:
        # Map schedule enum to days offset → compute ISO time
        schedule_map = {
            "when_code_available": None,
            "in_1_day": 1,
            "in_1_week": 7,
            "in_1_month": 30,
            "manual": None,
        }
        days_offset = schedule_map.get(schedule)
        if days_offset:
            from datetime import datetime, timedelta, timezone
            import zoneinfo
            utc_dt = datetime.now(timezone.utc) + timedelta(days=days_offset)
            scheduled_at_iso = utc_dt.isoformat()
            if is_personal_reminder:
                local_dt = utc_dt.astimezone(zoneinfo.ZoneInfo(user_timezone))
                scheduled_local_time = local_dt.strftime("%Y-%m-%dT%H:%M:%S")

    # Use Kotlin server's internal task creation endpoint
    kotlin_url = settings.kotlin_server_url or "http://jervis-server:8080"
    url = f"{kotlin_url}/internal/tasks/create"

    # Prepend urgency marker so qualification agent routes correctly
    task_description = f"{description}\n\nReason: {reason}"
    if urgency == "urgent":
        task_description = f"[URGENT REMINDER] {task_description}\n\nThis task should be treated as URGENT and shown as an alert to the user."

    payload = {
        "clientId": client_id,
        "projectId": project_id,
        "title": title,
        "description": task_description,
        "schedule": schedule if not scheduled_at else "custom",
        "scheduledAt": scheduled_at_iso,
        "cronTimezone": user_timezone if not is_personal_reminder else None,
        "followUserTimezone": is_personal_reminder,
        "scheduledLocalTime": scheduled_local_time,
        "createdBy": "orchestrator_agent",
        "metadata": {
            "reason": reason,
            "schedule_type": "custom" if scheduled_at else schedule,
            "urgency": urgency,
            "created_from": "agent_tool",
        },
    }

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
            task_id = data.get("taskId", "unknown")
            # Server-side dedup: task already existed
            if data.get("deduplicated") == "true":
                return (
                    f"⚠ Scheduled task already exists (deduplicated).\n"
                    f"Existing task ID: {task_id} (state: {data.get('state', '?')})\n"
                    f"Title: {data.get('name', title)}\n"
                    f"No new task created — avoid duplicate follow-ups."
                )
    except httpx.TimeoutException:
        return f"Error: Task creation timed out after 5s for: {title}"
    except httpx.HTTPStatusError as e:
        return f"Error: Task creation returned HTTP {e.response.status_code} for: {title}"
    except Exception as e:
        return f"Error: Task creation failed: {str(e)[:200]}"

    # Success response
    if scheduled_at_iso:
        schedule_info = f"naplánováno na {scheduled_at or scheduled_at_iso}"
    elif schedule != "manual":
        schedule_info = f"scheduled for {schedule.replace('_', ' ')}"
    else:
        schedule_info = "created for manual review"

    urgency_info = " (URGENT — zobrazí se jako upozornění)" if urgency == "urgent" else ""
    return (
        f"✓ Task created successfully!\n"
        f"Title: {title}\n"
        f"Schedule: {schedule_info}{urgency_info}\n"
        f"Task ID: {task_id}\n"
        f"The task is now in your task list and will be executed when appropriate."
    )


async def _execute_get_indexed_items(
    item_type: str = "all",
    limit: int = 10,
    client_id: str = "",
    project_id: str | None = None,
) -> str:
    """Get indexed items summary from KB via POST /api/v1/chunks/by-kind."""
    url = f"{settings.knowledgebase_url}/api/v1/chunks/by-kind"

    # Map item_type to KB kind values
    kind_map = {
        "all": "all",
        "git": "git_commit",
        "jira": "jira_issue",
        "confluence": "confluence_page",
        "email": "email",
    }
    kinds_to_query = [kind_map.get(item_type, item_type)] if item_type != "all" else list(kind_map.values())[1:]

    all_results: dict[str, list] = {}
    for kind in kinds_to_query:
        payload = {
            "clientId": client_id,
            "projectId": project_id,
            "kind": kind,
            "maxResults": limit,
        }
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.post(url, json=payload)
                resp.raise_for_status()
                data = resp.json()
                if data:
                    all_results[kind] = data if isinstance(data, list) else [data]
        except Exception as e:
            all_results[kind] = [{"error": str(e)[:100]}]

    if not any(v for v in all_results.values() if v and not v[0].get("error")):
        return "No indexed items found in Knowledge Base for this project."

    lines = ["## Indexed Items Summary\n"]
    for kind, items in all_results.items():
        if items and items[0].get("error"):
            continue
        count = len(items)
        lines.append(f"### {kind.upper()}: {count} items")
        for item in items[:5]:
            source = item.get("sourceUrn", item.get("source_urn", "?"))
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
        lines.append(f"**Result {i}** (score: {score:.2f}, kind: {kind})")
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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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


async def _get_workspace_path(
    client_id: str, project_id: str | None, auto_init: bool = True,
) -> str | None:
    """Get workspace path from KB graph (repository node).

    If the workspace is not yet cloned and auto_init=True, triggers the
    Kotlin server's BLOCKING /internal/git/init-workspace endpoint which
    clones the repo synchronously and returns the agent workspace path.

    Returns None only when:
    - project_id is missing
    - no git resource configured for the project
    - clone failed (auth / not found / network error)
    """
    if not project_id:
        return None

    # 1. Try KB graph first (fast path, no clone needed if repo already indexed)
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
            if repos:
                workspace_path = repos[0].get("properties", {}).get("workspacePath")
                if workspace_path:
                    # Verify the directory actually exists on disk
                    from pathlib import Path as _P
                    if _P(workspace_path).exists():
                        return workspace_path
    except Exception as e:
        logger.warning("KB graph lookup for workspace failed: %s", e)

    # 2. KB didn't have it (or path doesn't exist on disk yet).
    #    Trigger blocking init on the Kotlin server — it will clone and return
    #    the workspace path synchronously once READY.
    if not auto_init:
        return None

    init_url = f"{settings.kotlin_server_url.rstrip('/')}/internal/git/init-workspace"
    try:
        async with httpx.AsyncClient(timeout=600.0) as client:
            logger.info(
                "AUTO_INIT_WORKSPACE | project=%s — triggering blocking clone",
                project_id,
            )
            resp = await client.post(init_url, json={"projectId": project_id})
            if resp.status_code != 200:
                logger.warning(
                    "init-workspace returned HTTP %d: %s",
                    resp.status_code, resp.text[:200],
                )
                return None
            data = resp.json()
            status = data.get("status", "")
            path = data.get("workspacePath", "")
            if data.get("ok") and path:
                logger.info(
                    "AUTO_INIT_WORKSPACE | project=%s status=%s path=%s",
                    project_id, status, path,
                )
                return path
            logger.warning(
                "AUTO_INIT_WORKSPACE | project=%s FAILED status=%s error=%s",
                project_id, status, data.get("error", ""),
            )
            return None
    except Exception as e:
        logger.error("AUTO_INIT_WORKSPACE | project=%s exception: %s", project_id, e)
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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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

    # Log large content but don't reject — LLM compression handles size during memory parking
    if len(content) > 5000:
        logger.warning("Large content stored (%d chars) for subject '%s'", len(content), subject)

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
        # No truncation — LLM compression handles this during parking
        active = lqm.get_active_affair(client_id)
        if active:
            active.key_facts[subject] = content
            lqm.store_affair(active)

        # Buffer KB write
        now = datetime.now(timezone.utc).isoformat()
        kind_map = {
            "fact": "user_knowledge_fact",
            "decision": "user_knowledge_preference",
            "specification": "user_knowledge_specification",
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
            client_id=client_id,
            project_id=project_id or "",
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
            pid = project_id or ""
            buffer_hits = lqm.search_write_buffer(query, client_id=client_id, project_id=pid)
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
        pid = project_id or ""
        if scope in ("all", "kb_only"):
            cached = lqm.get_cached_search(query, client_id=client_id, project_id=pid)
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
                            lqm.cache_search(query, kb_items, client_id=client_id, project_id=pid)
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
        return "Error: Workspace for this project is not cloned yet. \n\nTry one of these alternatives that read from the Knowledge Base (no clone needed):\n- get_recent_commits(limit=10) — latest commits with hash, author, message, date\n- get_repository_info() — repo overview with branches\n- git_branch_list() — all branches\n- get_repository_structure() — file tree structure\n- get_technology_stack() — detected languages/frameworks\n\nOr call init_workspace(project_id=\"<id>\") to trigger async clone (takes ~30s-5min for large repos), then retry this tool."

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
_O365_GATEWAY_URL = settings.o365_gateway_url


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


async def _execute_get_stack_recommendations(requirements: str) -> str:
    """Get technology stack recommendations from the advisor service."""
    if not requirements:
        return "Error: requirements parameter is required. Pass the full project requirements."
    try:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.post(
                f"{_KOTLIN_INTERNAL_URL}/internal/project-advisor/recommendations",
                json={"requirements": requirements},
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            data = resp.json()

            # Format recommendations for the LLM
            parts = []

            # Archetype
            arch = data.get("archetype", {})
            parts.append(f"## Recommended Architecture: {arch.get('name', 'unknown')}")
            parts.append(f"Type: {arch.get('type', '')}")
            parts.append(f"Description: {arch.get('description', '')}")
            if arch.get("pros"):
                parts.append(f"Pros: {', '.join(arch['pros'])}")
            if arch.get("cons"):
                parts.append(f"Cons: {', '.join(arch['cons'])}")
            parts.append(f"Best for: {arch.get('bestFor', '')}")

            # Platforms
            platforms = data.get("platforms", [])
            if platforms:
                parts.append("\n## Platform Recommendations")
                for p in platforms:
                    rec = "RECOMMENDED" if p.get("recommended") else "optional"
                    parts.append(f"- {p['platform']} [{rec}]: {p.get('rationale', '')}")
                    for alt in p.get("alternatives", []):
                        parts.append(f"    Alternative: {alt['name']} — {alt['description']}")

            # Storage
            storage = data.get("storage", [])
            if storage:
                parts.append("\n## Storage Recommendations")
                for s in storage:
                    rec = "RECOMMENDED" if s.get("recommended") else "optional"
                    parts.append(f"- {s['technology']} [{rec}]: {s.get('useCase', '')}")
                    parts.append(f"    Spring dependency: {s.get('springDependency', '')}")
                    if s.get("pros"):
                        parts.append(f"    Pros: {', '.join(s['pros'])}")
                    if s.get("cons"):
                        parts.append(f"    Cons: {', '.join(s['cons'])}")

            # Features
            features = data.get("features", [])
            if features:
                parts.append("\n## Feature Recommendations")
                for f in features:
                    parts.append(f"- {f['feature']}:")
                    for opt in f.get("options", []):
                        parts.append(f"    Option: {opt['name']} — {opt['description']}")

            # Scaffolding instructions (for coding agent dispatch later)
            instructions = data.get("scaffoldingInstructions", "")
            if instructions:
                parts.append(f"\n## Scaffolding Instructions (for coding agent)\n{instructions}")

            return "\n".join(parts)
    except Exception as e:
        return f"Error getting stack recommendations: {str(e)[:300]}"


# ---------------------------------------------------------------------------
# Guidelines tools (shared between chat handler and graph agent)
# ---------------------------------------------------------------------------


async def _execute_get_guidelines(
    client_id: str | None,
    project_id: str | None,
) -> str:
    """Fetch merged guidelines for the given scope."""
    from app.tools.kotlin_client import kotlin_client

    scope = "GLOBAL" if not client_id else ("CLIENT" if not project_id else "PROJECT")
    return await kotlin_client.get_guidelines(
        scope=scope,
        client_id=client_id,
        project_id=project_id,
    )


async def _execute_update_guideline(
    scope: str,
    category: str,
    rules: dict,
    client_id: str | None,
    project_id: str | None,
) -> str:
    """Update guidelines for the given scope and category."""
    from app.tools.kotlin_client import kotlin_client

    if not category:
        return "Error: category is required."
    if scope in ("CLIENT", "PROJECT") and not client_id:
        return "Error: client_id is required for CLIENT/PROJECT scope."
    if scope == "PROJECT" and not project_id:
        return "Error: project_id is required for PROJECT scope."

    return await kotlin_client.update_guideline(
        scope=scope,
        category=category,
        rules=rules,
        client_id=client_id,
        project_id=project_id,
    )


# ---------------------------------------------------------------------------
# Meeting tools (shared between chat handler and graph agent)
# ---------------------------------------------------------------------------


async def _execute_classify_meeting(
    meeting_id: str,
    client_id: str,
    project_id: str | None,
    title: str | None = None,
) -> str:
    """Classify a meeting recording."""
    from app.tools.kotlin_client import kotlin_client

    if not meeting_id:
        return "Error: meeting_id is required."
    if not client_id:
        return "Error: client_id is required."
    result = await kotlin_client.classify_meeting(
        meeting_id=meeting_id,
        client_id=client_id,
        project_id=project_id,
        title=title,
    )
    return f"Meeting classified: {result}"


async def _execute_list_unclassified_meetings() -> str:
    """List unclassified meeting recordings."""
    from app.tools.kotlin_client import kotlin_client

    return await kotlin_client.list_unclassified_meetings()


# ---------------------------------------------------------------------------
# Dispatch coding agent (shared — used by chat handler and graph agent)
# ---------------------------------------------------------------------------


async def _execute_dispatch_coding_agent(
    task_description: str,
    client_id: str,
    project_id: str | None,
    agent_preference: str = "auto",
    parent_task_id: str | None = None,
) -> str:
    """Dispatch a coding agent for a task."""
    from app.tools.kotlin_client import kotlin_client

    if not client_id or not project_id:
        return "Error: client_id and project_id are required for dispatch_coding_agent."
    result = await kotlin_client.dispatch_coding_agent(
        task_description=task_description,
        client_id=client_id,
        project_id=project_id,
        agent_preference=agent_preference,
    )

    # Register parent→child relationship for master graph nesting
    if parent_task_id and result and not str(result).startswith("Error"):
        try:
            import re
            child_id = None
            result_str = str(result)
            # Try to extract task ID (24-char hex ObjectId)
            match = re.search(r"[0-9a-f]{24}", result_str)
            if match:
                child_id = match.group(0)
            if child_id:
                from app.agent.persistence import agent_store
                agent_store.register_task_parent(child_id, parent_task_id)
        except Exception as e:
            logger.debug("Failed to register task parent: %s", e)

    return f"Coding agent dispatched: {result}"


# ---------------------------------------------------------------------------
# MongoDB self-management tools
# ---------------------------------------------------------------------------

# Collections that have Kotlin in-memory cache — must invalidate after writes
_CACHED_COLLECTIONS = {
    "clients", "projects", "project_groups", "cloud_model_policies",
    "openrouter_settings", "polling_intervals", "whisper_settings", "guidelines",
}


async def _execute_mongo_list_collections() -> str:
    """List all MongoDB collections in the Jervis database."""
    from app.config import settings
    from motor.motor_asyncio import AsyncIOMotorClient

    client = AsyncIOMotorClient(settings.mongodb_uri)
    db = client[settings.mongodb_database]
    names = await db.list_collection_names()
    return "\n".join(sorted(names))


async def _execute_mongo_get_document(
    collection: str,
    filter_doc: dict | None,
    limit: int = 10,
) -> str:
    """Read documents from a MongoDB collection."""
    import json
    from app.config import settings
    from motor.motor_asyncio import AsyncIOMotorClient

    if not collection:
        return "Error: collection name is required."

    client = AsyncIOMotorClient(settings.mongodb_uri)
    db = client[settings.mongodb_database]
    coll = db[collection]

    mongo_filter = filter_doc or {}
    cursor = coll.find(mongo_filter).limit(limit)
    docs = await cursor.to_list(length=limit)

    # Convert ObjectId to string for JSON serialization
    for doc in docs:
        if "_id" in doc:
            doc["_id"] = str(doc["_id"])

    if not docs:
        return f"No documents found in '{collection}' with filter {mongo_filter}"

    return json.dumps(docs, ensure_ascii=False, indent=2, default=str)


async def _execute_mongo_update_document(
    collection: str,
    filter_doc: dict,
    update_doc: dict,
    upsert: bool = False,
) -> str:
    """Update a document in MongoDB and invalidate Kotlin cache if needed."""
    from app.config import settings
    from motor.motor_asyncio import AsyncIOMotorClient

    if not collection:
        return "Error: collection name is required."
    if not filter_doc:
        return "Error: filter is required (safety: no unfiltered updates)."
    if not update_doc:
        return "Error: update document is required."

    client = AsyncIOMotorClient(settings.mongodb_uri)
    db = client[settings.mongodb_database]
    coll = db[collection]

    result = await coll.update_one(filter_doc, update_doc, upsert=upsert)

    # Invalidate Kotlin cache for collections that have in-memory cache
    if collection in _CACHED_COLLECTIONS and (result.modified_count > 0 or result.upserted_id):
        try:
            from app.tools.kotlin_client import kotlin_client
            await kotlin_client.invalidate_cache(collection)
            logger.info("CACHE_INVALIDATED | collection=%s", collection)
        except Exception as e:
            logger.warning("Cache invalidation failed for %s: %s", collection, e)

    parts = [
        f"matched={result.matched_count}",
        f"modified={result.modified_count}",
    ]
    if result.upserted_id:
        parts.append(f"upserted_id={result.upserted_id}")

    return f"Update result: {', '.join(parts)}"


# ============================================================
# O365 tools (via O365 Gateway)
# ============================================================


async def _execute_o365_teams_list_chats(client_id: str, top: int = 20) -> str:
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{_O365_GATEWAY_URL}/api/o365/chats/{client_id}",
                params={"top": min(top, 50)},
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            chats = resp.json()
            if not chats:
                return "No chats found."
            lines = []
            for c in chats:
                topic = c.get("topic") or "(no topic)"
                chat_type = c.get("chatType", "?")
                chat_id = c.get("id", "?")
                preview = ""
                mp = c.get("lastMessagePreview")
                if mp and mp.get("body"):
                    preview_text = mp["body"].get("content", "")[:100]
                    from_user = ""
                    if mp.get("from") and mp["from"].get("user"):
                        from_user = mp["from"]["user"].get("displayName", "")
                    preview = f" | {from_user}: {preview_text}"
                lines.append(f"[{chat_type}] {topic} (id={chat_id}){preview}")
            return "\n".join(lines)
    except Exception as e:
        return f"Error listing Teams chats: {str(e)[:300]}"


async def _execute_o365_teams_read_chat(client_id: str, chat_id: str, top: int = 20) -> str:
    if not chat_id:
        return "Error: chat_id is required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{_O365_GATEWAY_URL}/api/o365/chats/{client_id}/{chat_id}/messages",
                params={"top": top},
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            messages = resp.json()
            if not messages:
                return "No messages found."
            lines = []
            for m in messages:
                sender = _extract_sender(m)
                body = (m.get("body") or {}).get("content", "")[:500]
                ts = m.get("createdDateTime", "")
                lines.append(f"[{ts}] {sender}: {body}")
            return "\n---\n".join(lines)
    except Exception as e:
        return f"Error reading Teams chat: {str(e)[:300]}"


async def _execute_o365_teams_send_message(
    client_id: str, chat_id: str, content: str, content_type: str = "text",
) -> str:
    if not chat_id or not content:
        return "Error: chat_id and content are required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{_O365_GATEWAY_URL}/api/o365/chats/{client_id}/{chat_id}/messages",
                json={"contentType": content_type, "content": content},
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            data = resp.json()
            return f"Message sent (id={data.get('id', '?')})"
    except Exception as e:
        return f"Error sending Teams message: {str(e)[:300]}"


async def _execute_o365_teams_list_teams(client_id: str) -> str:
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(f"{_O365_GATEWAY_URL}/api/o365/teams/{client_id}")
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            teams = resp.json()
            if not teams:
                return "No teams found."
            return "\n".join(
                f"{t.get('displayName', '?')} (id={t.get('id', '?')})" for t in teams
            )
    except Exception as e:
        return f"Error listing Teams: {str(e)[:300]}"


async def _execute_o365_teams_list_channels(client_id: str, team_id: str) -> str:
    if not team_id:
        return "Error: team_id is required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{_O365_GATEWAY_URL}/api/o365/teams/{client_id}/{team_id}/channels",
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            channels = resp.json()
            if not channels:
                return "No channels found."
            return "\n".join(
                f"{ch.get('displayName', '?')} (id={ch.get('id', '?')}, type={ch.get('membershipType', '?')})"
                for ch in channels
            )
    except Exception as e:
        return f"Error listing channels: {str(e)[:300]}"


async def _execute_o365_teams_read_channel(
    client_id: str, team_id: str, channel_id: str, top: int = 20,
) -> str:
    if not team_id or not channel_id:
        return "Error: team_id and channel_id are required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{_O365_GATEWAY_URL}/api/o365/teams/{client_id}/{team_id}/channels/{channel_id}/messages",
                params={"top": top},
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            messages = resp.json()
            if not messages:
                return "No messages found."
            lines = []
            for m in messages:
                sender = _extract_sender(m)
                body = (m.get("body") or {}).get("content", "")[:500]
                ts = m.get("createdDateTime", "")
                lines.append(f"[{ts}] {sender}: {body}")
            return "\n---\n".join(lines)
    except Exception as e:
        return f"Error reading channel: {str(e)[:300]}"


async def _execute_o365_teams_send_channel_message(
    client_id: str, team_id: str, channel_id: str, content: str,
) -> str:
    if not team_id or not channel_id or not content:
        return "Error: team_id, channel_id, and content are required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{_O365_GATEWAY_URL}/api/o365/teams/{client_id}/{team_id}/channels/{channel_id}/messages",
                json={"contentType": "text", "content": content},
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            data = resp.json()
            return f"Channel message sent (id={data.get('id', '?')})"
    except Exception as e:
        return f"Error sending channel message: {str(e)[:300]}"


async def _execute_o365_session_status(client_id: str) -> str:
    try:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.get(f"{_O365_GATEWAY_URL}/api/o365/session/{client_id}")
            if resp.status_code == 404:
                return f"No O365 session for client '{client_id}'. Session needs to be initialized."
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            data = resp.json()
            parts = [f"Session state: {data.get('state', '?')}"]
            if data.get("lastActivity"):
                parts.append(f"Last activity: {data['lastActivity']}")
            if data.get("lastTokenExtract"):
                parts.append(f"Last token extract: {data['lastTokenExtract']}")
            if data.get("novncUrl"):
                parts.append(f"noVNC URL (for manual login): {data['novncUrl']}")
            return "\n".join(parts)
    except Exception as e:
        return f"Error checking session status: {str(e)[:300]}"


# -- Mail --

async def _execute_o365_mail_list(
    client_id: str, top: int = 20, folder: str = "inbox",
) -> str:
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{_O365_GATEWAY_URL}/api/o365/mail/{client_id}",
                params={"top": top, "folder": folder},
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            messages = resp.json()
            if not messages:
                return f"No emails found in '{folder}'."
            lines = []
            for m in messages:
                subj = m.get("subject", "(no subject)")
                sender = ""
                if m.get("from") and m["from"].get("emailAddress"):
                    ea = m["from"]["emailAddress"]
                    sender = ea.get("name") or ea.get("address", "?")
                ts = m.get("receivedDateTime", "")
                read = "" if m.get("isRead") else " [UNREAD]"
                attach = " [ATTACH]" if m.get("hasAttachments") else ""
                msg_id = m.get("id", "?")
                preview = (m.get("bodyPreview") or "")[:120]
                lines.append(
                    f"[{ts}] {sender}: {subj}{read}{attach}\n  id={msg_id}\n  {preview}"
                )
            return "\n---\n".join(lines)
    except Exception as e:
        return f"Error listing mail: {str(e)[:300]}"


async def _execute_o365_mail_read(client_id: str, message_id: str) -> str:
    if not message_id:
        return "Error: message_id is required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{_O365_GATEWAY_URL}/api/o365/mail/{client_id}/{message_id}",
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            m = resp.json()
            sender = ""
            if m.get("from") and m["from"].get("emailAddress"):
                ea = m["from"]["emailAddress"]
                sender = f"{ea.get('name', '')} <{ea.get('address', '')}>"
            to_list = [
                r["emailAddress"].get("address", "")
                for r in (m.get("toRecipients") or [])
                if r.get("emailAddress")
            ]
            cc_list = [
                r["emailAddress"].get("address", "")
                for r in (m.get("ccRecipients") or [])
                if r.get("emailAddress")
            ]
            body_content = (m.get("body") or {}).get("content", "")
            parts = [
                f"Subject: {m.get('subject', '(none)')}",
                f"From: {sender}",
                f"To: {', '.join(to_list)}",
            ]
            if cc_list:
                parts.append(f"CC: {', '.join(cc_list)}")
            parts.append(f"Date: {m.get('receivedDateTime', '?')}")
            if m.get("hasAttachments"):
                parts.append("Has attachments: yes")
            parts.append(f"\n{body_content}")
            return "\n".join(parts)
    except Exception as e:
        return f"Error reading mail: {str(e)[:300]}"


async def _execute_o365_mail_send(
    client_id: str, to: str, subject: str, body: str,
    cc: str = "", content_type: str = "text",
) -> str:
    if not to or not subject:
        return "Error: to and subject are required."
    to_recipients = [
        {"emailAddress": {"address": addr.strip()}}
        for addr in to.split(",") if addr.strip()
    ]
    cc_recipients = [
        {"emailAddress": {"address": addr.strip()}}
        for addr in cc.split(",") if addr.strip()
    ] if cc else []
    payload = {
        "message": {
            "subject": subject,
            "body": {"contentType": content_type, "content": body},
            "toRecipients": to_recipients,
            "ccRecipients": cc_recipients,
        },
        "saveToSentItems": True,
    }
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{_O365_GATEWAY_URL}/api/o365/mail/{client_id}/send",
                json=payload,
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            return "Email sent successfully."
    except Exception as e:
        return f"Error sending mail: {str(e)[:300]}"


# -- Calendar --

async def _execute_o365_calendar_events(
    client_id: str, top: int = 20,
    start_date_time: str = "", end_date_time: str = "",
) -> str:
    params: dict = {"top": top}
    if start_date_time:
        params["startDateTime"] = start_date_time
    if end_date_time:
        params["endDateTime"] = end_date_time
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{_O365_GATEWAY_URL}/api/o365/calendar/{client_id}",
                params=params,
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            events = resp.json()
            if not events:
                return "No events found."
            lines = []
            for ev in events:
                subj = ev.get("subject", "(no subject)")
                start = (ev.get("start") or {}).get("dateTime", "?")
                end = (ev.get("end") or {}).get("dateTime", "?")
                loc = ""
                if ev.get("location") and ev["location"].get("displayName"):
                    loc = f" @ {ev['location']['displayName']}"
                online = " [ONLINE]" if ev.get("isOnlineMeeting") else ""
                all_day = " [ALL DAY]" if ev.get("isAllDay") else ""
                ev_id = ev.get("id", "?")
                attendees = []
                for a in ev.get("attendees") or []:
                    if a.get("emailAddress"):
                        attendees.append(
                            a["emailAddress"].get("name")
                            or a["emailAddress"].get("address", "")
                        )
                att_str = f"\n  Attendees: {', '.join(attendees)}" if attendees else ""
                lines.append(
                    f"{subj}{all_day}{online}{loc}\n  {start} → {end}\n  id={ev_id}{att_str}"
                )
            return "\n---\n".join(lines)
    except Exception as e:
        return f"Error listing calendar events: {str(e)[:300]}"


async def _execute_o365_calendar_create(
    client_id: str, subject: str,
    start_date_time: str, start_time_zone: str,
    end_date_time: str, end_time_zone: str,
    location: str = "", body: str = "",
    attendees: str = "", is_online_meeting: bool = False,
) -> str:
    if not subject or not start_date_time or not end_date_time:
        return "Error: subject, start_date_time, and end_date_time are required."
    payload: dict = {
        "subject": subject,
        "start": {"dateTime": start_date_time, "timeZone": start_time_zone or "UTC"},
        "end": {"dateTime": end_date_time, "timeZone": end_time_zone or "UTC"},
        "isOnlineMeeting": is_online_meeting,
    }
    if location:
        payload["location"] = {"displayName": location}
    if body:
        payload["body"] = {"contentType": "text", "content": body}
    if attendees:
        payload["attendees"] = [
            {"emailAddress": {"address": addr.strip()}, "type": "required"}
            for addr in attendees.split(",") if addr.strip()
        ]
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{_O365_GATEWAY_URL}/api/o365/calendar/{client_id}",
                json=payload,
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            ev = resp.json()
            result = f"Event created: {ev.get('subject', subject)} (id={ev.get('id', '?')})"
            if ev.get("onlineMeetingUrl"):
                result += f"\nTeams meeting URL: {ev['onlineMeetingUrl']}"
            if ev.get("webLink"):
                result += f"\nWeb link: {ev['webLink']}"
            return result
    except Exception as e:
        return f"Error creating calendar event: {str(e)[:300]}"


# -- OneDrive --

async def _execute_o365_files_list(
    client_id: str, path: str = "root", top: int = 50,
) -> str:
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{_O365_GATEWAY_URL}/api/o365/drive/{client_id}",
                params={"path": path, "top": top},
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            items = resp.json()
            if not items:
                return f"No items found at '{path}'."
            lines = []
            for item in items:
                name = item.get("name", "?")
                is_folder = item.get("folder") is not None
                icon = "[DIR]" if is_folder else "[FILE]"
                size = ""
                if not is_folder and item.get("size"):
                    size_kb = item["size"] / 1024
                    size = f" ({size_kb:.1f} KB)" if size_kb < 1024 else f" ({size_kb / 1024:.1f} MB)"
                modified = item.get("lastModifiedDateTime", "")
                item_id = item.get("id", "?")
                lines.append(f"{icon} {name}{size} (modified={modified}, id={item_id})")
            return "\n".join(lines)
    except Exception as e:
        return f"Error listing drive files: {str(e)[:300]}"


async def _execute_o365_files_download(client_id: str, item_id: str) -> str:
    if not item_id:
        return "Error: item_id is required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{_O365_GATEWAY_URL}/api/o365/drive/{client_id}/item/{item_id}",
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            item = resp.json()
            parts = [
                f"Name: {item.get('name', '?')}",
                f"Size: {item.get('size', '?')} bytes",
            ]
            if item.get("file"):
                parts.append(f"MIME type: {item['file'].get('mimeType', '?')}")
            if item.get("webUrl"):
                parts.append(f"Web URL: {item['webUrl']}")
            if item.get("@microsoft.graph.downloadUrl"):
                parts.append(f"Download URL: {item['@microsoft.graph.downloadUrl']}")
            return "\n".join(parts)
    except Exception as e:
        return f"Error getting drive item: {str(e)[:300]}"


async def _execute_o365_files_search(
    client_id: str, query: str, top: int = 25,
) -> str:
    if not query:
        return "Error: query is required."
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{_O365_GATEWAY_URL}/api/o365/drive/{client_id}/search",
                params={"q": query, "top": top},
            )
            if resp.status_code != 200:
                return f"Error ({resp.status_code}): {resp.text[:300]}"
            items = resp.json()
            if not items:
                return f"No files found for query '{query}'."
            lines = []
            for item in items:
                name = item.get("name", "?")
                is_folder = item.get("folder") is not None
                icon = "[DIR]" if is_folder else "[FILE]"
                size = ""
                if not is_folder and item.get("size"):
                    size_kb = item["size"] / 1024
                    size = f" ({size_kb:.1f} KB)" if size_kb < 1024 else f" ({size_kb / 1024:.1f} MB)"
                path = ""
                if item.get("parentReference") and item["parentReference"].get("path"):
                    path = f" in {item['parentReference']['path']}"
                item_id = item.get("id", "?")
                lines.append(f"{icon} {name}{size}{path} (id={item_id})")
            return "\n".join(lines)
    except Exception as e:
        return f"Error searching drive: {str(e)[:300]}"


# -- Helper --

def _extract_sender(message: dict) -> str:
    """Extract sender display name from a Graph API message."""
    from_data = message.get("from")
    if not from_data:
        return "?"
    if from_data.get("user"):
        return from_data["user"].get("displayName", "?")
    if from_data.get("application"):
        return from_data["application"].get("displayName", "bot")
    return "?"


# ============================================================
# Issue Tracker tool handlers
# ============================================================

async def _execute_issue_create(
    client_id: str, project_id: str,
    title: str, description: str = "", labels: str = "",
) -> str:
    body: dict = {
        "clientId": client_id,
        "projectId": project_id,
        "title": title,
    }
    if description:
        body["description"] = description
    if labels:
        body["labels"] = [l.strip() for l in labels.split(",") if l.strip()]
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/issues/create", json=body)
            data = resp.json()
            if data.get("ok"):
                url_info = f"\n  URL: {data.get('url', '')}" if data.get("url") else ""
                return f"Issue created: {data.get('key', '?')}{url_info}"
            return f"Error: {data.get('error', resp.text)}"
    except Exception as e:
        return f"Error creating issue: {e}"


async def _execute_issue_update(
    client_id: str, project_id: str, issue_key: str,
    title: str | None = None, description: str | None = None,
    state: str | None = None, labels: str | None = None,
) -> str:
    key = issue_key if issue_key.startswith("#") else f"#{issue_key}"
    body: dict = {
        "clientId": client_id,
        "projectId": project_id,
        "issueKey": key,
    }
    if title:
        body["title"] = title
    if description:
        body["description"] = description
    if state:
        body["state"] = state
    if labels is not None and labels != "":
        body["labels"] = [l.strip() for l in labels.split(",") if l.strip()]
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/issues/update", json=body)
            data = resp.json()
            if data.get("ok"):
                url_info = f"\n  URL: {data.get('url', '')}" if data.get("url") else ""
                return f"Issue {key} updated{url_info}"
            return f"Error: {data.get('error', resp.text)}"
    except Exception as e:
        return f"Error updating issue: {e}"


async def _execute_issue_comment(
    client_id: str, project_id: str,
    issue_key: str, comment: str,
) -> str:
    key = issue_key if issue_key.startswith("#") else f"#{issue_key}"
    body: dict = {
        "clientId": client_id,
        "projectId": project_id,
        "issueKey": key,
        "comment": comment,
    }
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(f"{_KOTLIN_INTERNAL_URL}/internal/issues/comment", json=body)
            data = resp.json()
            if data.get("ok"):
                return f"Comment added to {key}"
            return f"Error: {data.get('error', resp.text)}"
    except Exception as e:
        return f"Error adding comment: {e}"


async def _execute_issue_list(client_id: str, project_id: str) -> str:
    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.get(
                f"{_KOTLIN_INTERNAL_URL}/internal/issues/list",
                params={"clientId": client_id, "projectId": project_id},
            )
            data = resp.json()
            if not data.get("ok"):
                return f"Error: {data.get('error', resp.text)}"
            issues = data.get("issues", [])
            if not issues:
                return "No issues found."
            lines = [f"Found {len(issues)} issue(s):"]
            for issue in issues:
                lines.append(f"  {issue['key']} [{issue['state']}] {issue['title']}\n    URL: {issue.get('url', '')}")
            return "\n".join(lines)
    except Exception as e:
        return f"Error listing issues: {e}"
