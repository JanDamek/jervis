"""Default tool sets per vertex type.

Each vertex type gets a curated default set of tools matching its
responsibility. Vertices can request additional tools at runtime
via the `request_tools` tool.

Tool sets are composed from existing tool definitions in the codebase.
"""

from __future__ import annotations

import logging
from typing import Any

from app.agent.models import VertexType

logger = logging.getLogger(__name__)


def get_default_tools(vertex_type: VertexType) -> list[dict]:
    """Get the default tool set for a vertex type.

    Lazy import to avoid circular dependencies at module level.
    Returns OpenAI-compatible tool dicts.
    """
    # Late imports — tools depend on heavy modules
    # Git metadata tools (branch list, recent commits) = OK for orientation.
    # Deep code investigation / git history analysis → dispatch_coding_agent.
    from app.tools.definitions import (
        TOOL_KB_SEARCH,
        TOOL_WEB_SEARCH,
        TOOL_ASK_USER,
        TOOL_LIST_PROJECT_FILES,
        TOOL_GET_REPOSITORY_INFO,
        TOOL_GET_RECENT_COMMITS,
        TOOL_GET_TECHNOLOGY_STACK,
        TOOL_GET_REPOSITORY_STRUCTURE,
        TOOL_GIT_BRANCH_LIST,
        TOOL_STORE_KNOWLEDGE,
        TOOL_GET_KB_STATS,
        TOOL_GET_INDEXED_ITEMS,
        TOOL_KB_DELETE,
        TOOL_MEMORY_STORE,
        TOOL_MEMORY_RECALL,
        TOOL_CREATE_SCHEDULED_TASK,
        TOOL_TASK_QUEUE_INSPECT,
        TOOL_TASK_QUEUE_SET_PRIORITY,
        ENVIRONMENT_TOOLS,
        PROJECT_MANAGEMENT_TOOLS,
        O365_ALL_TOOLS,
    )
    from app.chat.tools import (
        TOOL_DISPATCH_CODING_AGENT,
        TOOL_GET_GUIDELINES,
        TOOL_UPDATE_GUIDELINE,
        TOOL_CLASSIFY_MEETING,
        TOOL_LIST_UNCLASSIFIED_MEETINGS,
        TOOL_GET_MEETING_TRANSCRIPT,
        TOOL_LIST_MEETINGS,
    )

    # --- Tool sets by responsibility ---

    # Shared base: KB search + memory (everyone can search and remember)
    _base = [TOOL_KB_SEARCH, TOOL_MEMORY_RECALL]

    # The meta-tool for requesting additional tools
    _request_tools = _build_request_tools_definition()

    # PLANNER: plans approach, inspects queue for cross-project priority
    if vertex_type in (VertexType.PLANNER, VertexType.DECOMPOSE):
        return _base + [
            TOOL_GET_REPOSITORY_INFO,
            TOOL_GET_REPOSITORY_STRUCTURE,
            TOOL_GET_TECHNOLOGY_STACK,
            TOOL_GET_KB_STATS,
            TOOL_TASK_QUEUE_INSPECT,
            TOOL_TASK_QUEUE_SET_PRIORITY,
            TOOL_GET_GUIDELINES,
            _request_tools,
        ]

    # INVESTIGATOR: researches context (KB, web, code metadata, meetings)
    # Git metadata (branches, commits) = orientation only.
    # For any code analysis or deep git investigation → dispatch_coding_agent.
    if vertex_type == VertexType.INVESTIGATOR:
        return _base + [
            TOOL_WEB_SEARCH,
            TOOL_LIST_PROJECT_FILES,
            TOOL_GET_REPOSITORY_INFO,
            TOOL_GET_RECENT_COMMITS,
            TOOL_GET_TECHNOLOGY_STACK,
            TOOL_GET_REPOSITORY_STRUCTURE,
            TOOL_GIT_BRANCH_LIST,
            TOOL_GET_KB_STATS,
            TOOL_GET_INDEXED_ITEMS,
            TOOL_DISPATCH_CODING_AGENT,
            TOOL_STORE_KNOWLEDGE,
            TOOL_LIST_MEETINGS,
            TOOL_GET_MEETING_TRANSCRIPT,
            TOOL_LIST_UNCLASSIFIED_MEETINGS,
            _request_tools,
        ]

    # EXECUTOR: performs concrete work (coding, tracker, KB write, interactive dialog, O365)
    if vertex_type in (VertexType.EXECUTOR, VertexType.TASK):
        return _base + O365_ALL_TOOLS + [
            TOOL_ASK_USER,
            TOOL_WEB_SEARCH,
            TOOL_LIST_PROJECT_FILES,
            TOOL_GET_REPOSITORY_INFO,
            TOOL_GET_REPOSITORY_STRUCTURE,
            TOOL_DISPATCH_CODING_AGENT,
            TOOL_STORE_KNOWLEDGE,
            TOOL_MEMORY_STORE,
            TOOL_CREATE_SCHEDULED_TASK,
            TOOL_GET_GUIDELINES,
            TOOL_UPDATE_GUIDELINE,
            TOOL_CLASSIFY_MEETING,
            TOOL_LIST_UNCLASSIFIED_MEETINGS,
            TOOL_LIST_MEETINGS,
            TOOL_GET_MEETING_TRANSCRIPT,
            _request_tools,
        ]

    # VALIDATOR: verifies results (metadata + coding agent for tests/code checks)
    if vertex_type == VertexType.VALIDATOR:
        return _base + [
            TOOL_LIST_PROJECT_FILES,
            TOOL_GET_REPOSITORY_INFO,
            TOOL_GET_REPOSITORY_STRUCTURE,
            TOOL_GIT_BRANCH_LIST,
            TOOL_GET_RECENT_COMMITS,
            TOOL_DISPATCH_CODING_AGENT,
            _request_tools,
        ]

    # REVIEWER: reviews quality (metadata + coding agent for code review + KB)
    if vertex_type == VertexType.REVIEWER:
        return _base + [
            TOOL_LIST_PROJECT_FILES,
            TOOL_GET_REPOSITORY_INFO,
            TOOL_GET_REPOSITORY_STRUCTURE,
            TOOL_GIT_BRANCH_LIST,
            TOOL_GET_RECENT_COMMITS,
            TOOL_GET_TECHNOLOGY_STACK,
            TOOL_DISPATCH_CODING_AGENT,
            TOOL_GET_GUIDELINES,
            _request_tools,
        ]

    # SYNTHESIS: combines results (minimal tools — works from context)
    if vertex_type == VertexType.SYNTHESIS:
        return _base + [TOOL_STORE_KNOWLEDGE, TOOL_MEMORY_STORE]

    # GATE: decision point (can ask user for approval/clarification)
    if vertex_type == VertexType.GATE:
        return _base + [TOOL_ASK_USER, _request_tools]

    # SETUP: project scaffolding + environment provisioning
    # Includes ask_user for confirming technology choices (advisor pattern)
    if vertex_type == VertexType.SETUP:
        return _base + ENVIRONMENT_TOOLS + PROJECT_MANAGEMENT_TOOLS + [
            TOOL_ASK_USER,
            TOOL_GET_REPOSITORY_INFO,
            TOOL_GET_REPOSITORY_STRUCTURE,
            TOOL_GET_TECHNOLOGY_STACK,
            TOOL_DISPATCH_CODING_AGENT,
            TOOL_STORE_KNOWLEDGE,
            TOOL_MEMORY_STORE,
            TOOL_GET_GUIDELINES,
            TOOL_UPDATE_GUIDELINE,
            _request_tools,
        ]

    # ROOT: decomposition only (handled separately)
    return _base


def get_all_tools() -> list[dict]:
    """Get ALL available tools (for when a vertex requests 'all')."""
    from app.background.tools import ALL_BACKGROUND_TOOLS
    return list(ALL_BACKGROUND_TOOLS)


def get_tools_by_category(category: str) -> list[dict]:
    """Get tools by category name.

    Categories: kb, web, git, code, memory, scheduling, interactive,
                guidelines, meetings, queue, environment, project_management, setup, all
    """
    from app.tools.definitions import (
        TOOL_KB_SEARCH,
        TOOL_KB_DELETE,
        TOOL_GET_KB_STATS,
        TOOL_GET_INDEXED_ITEMS,
        TOOL_STORE_KNOWLEDGE,
        TOOL_WEB_SEARCH,
        TOOL_ASK_USER,
        TOOL_LIST_PROJECT_FILES,
        TOOL_GET_REPOSITORY_INFO,
        TOOL_GET_RECENT_COMMITS,
        TOOL_GET_TECHNOLOGY_STACK,
        TOOL_GET_REPOSITORY_STRUCTURE,
        TOOL_GIT_BRANCH_LIST,
        TOOL_MEMORY_STORE,
        TOOL_MEMORY_RECALL,
        TOOL_CREATE_SCHEDULED_TASK,
        TOOL_TASK_QUEUE_INSPECT,
        TOOL_TASK_QUEUE_SET_PRIORITY,
        ENVIRONMENT_TOOLS,
        PROJECT_MANAGEMENT_TOOLS,
        MONGO_TOOLS,
        O365_ALL_TOOLS,
        O365_TEAMS_TOOLS,
        O365_MAIL_TOOLS,
        O365_CALENDAR_TOOLS,
        O365_FILES_TOOLS,
    )
    from app.chat.tools import (
        TOOL_DISPATCH_CODING_AGENT,
        TOOL_GET_GUIDELINES,
        TOOL_UPDATE_GUIDELINE,
        TOOL_CLASSIFY_MEETING,
        TOOL_LIST_UNCLASSIFIED_MEETINGS,
        TOOL_GET_MEETING_TRANSCRIPT,
        TOOL_LIST_MEETINGS,
    )

    categories = {
        "kb": [TOOL_KB_SEARCH, TOOL_KB_DELETE, TOOL_GET_KB_STATS,
               TOOL_GET_INDEXED_ITEMS, TOOL_STORE_KNOWLEDGE],
        "web": [TOOL_WEB_SEARCH],
        # git = metadata (branches, commits) + dispatch_coding_agent for deep analysis
        "git": [TOOL_GIT_BRANCH_LIST, TOOL_GET_RECENT_COMMITS, TOOL_DISPATCH_CODING_AGENT],
        "code": [TOOL_LIST_PROJECT_FILES, TOOL_GET_REPOSITORY_INFO,
                 TOOL_GET_REPOSITORY_STRUCTURE, TOOL_GET_TECHNOLOGY_STACK,
                 TOOL_DISPATCH_CODING_AGENT],
        "memory": [TOOL_MEMORY_STORE, TOOL_MEMORY_RECALL],
        "scheduling": [TOOL_CREATE_SCHEDULED_TASK],
        "interactive": [TOOL_ASK_USER],
        "guidelines": [TOOL_GET_GUIDELINES, TOOL_UPDATE_GUIDELINE],
        "meetings": [TOOL_CLASSIFY_MEETING, TOOL_LIST_UNCLASSIFIED_MEETINGS,
                     TOOL_LIST_MEETINGS, TOOL_GET_MEETING_TRANSCRIPT],
        "queue": [TOOL_TASK_QUEUE_INSPECT, TOOL_TASK_QUEUE_SET_PRIORITY],
        "environment": ENVIRONMENT_TOOLS,
        "project_management": PROJECT_MANAGEMENT_TOOLS,
        "settings": MONGO_TOOLS,
        "setup": ENVIRONMENT_TOOLS + PROJECT_MANAGEMENT_TOOLS + [
                 TOOL_ASK_USER, TOOL_DISPATCH_CODING_AGENT,
                 TOOL_GET_REPOSITORY_INFO, TOOL_GET_REPOSITORY_STRUCTURE],
        "o365": O365_ALL_TOOLS,
        "o365_teams": O365_TEAMS_TOOLS,
        "o365_mail": O365_MAIL_TOOLS,
        "o365_calendar": O365_CALENDAR_TOOLS,
        "o365_files": O365_FILES_TOOLS,
    }

    if category == "all":
        return get_all_tools()

    return categories.get(category, [])


def _build_request_tools_definition() -> dict:
    """Build the meta-tool definition for requesting additional tools.

    This tool lets a vertex request more tools if its default set
    isn't sufficient for the task.
    """
    return {
        "type": "function",
        "function": {
            "name": "request_tools",
            "description": (
                "Request additional tools if the current set is insufficient. "
                "Available categories: kb, web, git, code, memory, scheduling, "
                "interactive, guidelines, meetings, queue, environment, "
                "project_management, settings, setup, o365, o365_teams, "
                "o365_mail, o365_calendar, o365_files, all. "
                "IMPORTANT: 'git' gives basic metadata (branches, recent commits) + dispatch_coding_agent. "
                "For ANY deep code analysis, git history investigation, file reading, or code changes "
                "you MUST use dispatch_coding_agent — the orchestrator never investigates code directly. "
                "Use 'code' for repository metadata + dispatch_coding_agent. "
                "Use 'interactive' to get ask_user for dialog with the user. "
                "Use 'guidelines' to read/update project rules. "
                "Use 'meetings' to classify/list meeting recordings. "
                "Use 'settings' to read/write MongoDB documents (self-management). "
                "Use 'all' to get every available tool."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "categories": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Tool categories to add: kb, web, code, memory, scheduling, interactive, guidelines, meetings, queue, environment, project_management, settings, setup, o365, o365_teams, o365_mail, o365_calendar, o365_files, all. NO 'git' category — use dispatch_coding_agent for git.",
                    },
                    "reason": {
                        "type": "string",
                        "description": "Why additional tools are needed",
                    },
                },
                "required": ["categories", "reason"],
            },
        },
    }
