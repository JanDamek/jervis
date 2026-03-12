"""Background-specific tool subset.

Background tasks have access to a subset of tools:
- KB tools (search, store, stats, indexed items)
- Web search
- KB-based repository info tools (list_project_files, get_repository_info, etc.)
- dispatch_coding_agent (K8s Job)
- Scheduled task creation

Background tasks do NOT have:
- ask_user (no user in background)
- memory_store/recall (foreground only)
- list_affairs (foreground only)
- code_search, git workspace tools, filesystem tools (delegated to coding agents)

Tool tier system is available via app.unified.tool_sets for future unification.
"""

from __future__ import annotations

from app.tools.definitions import (
    # KB tools
    TOOL_KB_SEARCH,
    TOOL_KB_DELETE,
    TOOL_GET_KB_STATS,
    TOOL_GET_INDEXED_ITEMS,
    TOOL_STORE_KNOWLEDGE,
    # Web
    TOOL_WEB_SEARCH,
    # KB-based repository info tools (read from Knowledge Base graph, not filesystem)
    TOOL_LIST_PROJECT_FILES,
    TOOL_GET_REPOSITORY_INFO,
    TOOL_GET_RECENT_COMMITS,
    TOOL_GET_TECHNOLOGY_STACK,
    TOOL_GET_REPOSITORY_STRUCTURE,
    TOOL_GIT_BRANCH_LIST,
    # Scheduled tasks
    TOOL_CREATE_SCHEDULED_TASK,
    # Memory
    TOOL_MEMORY_STORE,
    TOOL_MEMORY_RECALL,
    # O365 (Teams, Mail, Calendar, OneDrive)
    O365_ALL_TOOLS,
)

from app.chat.tools import (
    TOOL_DISPATCH_CODING_AGENT,
    TOOL_GET_MEETING_TRANSCRIPT,
    TOOL_LIST_MEETINGS,
    TOOL_CLASSIFY_MEETING,
    TOOL_LIST_UNCLASSIFIED_MEETINGS,
)

# Tier system available for future unified handler
from app.unified.tool_sets import ToolTier, get_tools, get_tool_names  # noqa: F401

# All tools available to background handler
ALL_BACKGROUND_TOOLS: list[dict] = [
    # KB
    TOOL_KB_SEARCH,
    TOOL_KB_DELETE,
    TOOL_GET_KB_STATS,
    TOOL_GET_INDEXED_ITEMS,
    TOOL_STORE_KNOWLEDGE,
    # Web
    TOOL_WEB_SEARCH,
    # KB-based repository info (reads from graph, not filesystem)
    TOOL_LIST_PROJECT_FILES,
    TOOL_GET_REPOSITORY_INFO,
    TOOL_GET_RECENT_COMMITS,
    TOOL_GET_TECHNOLOGY_STACK,
    TOOL_GET_REPOSITORY_STRUCTURE,
    TOOL_GIT_BRANCH_LIST,
    # Scheduled tasks
    TOOL_CREATE_SCHEDULED_TASK,
    # Memory
    TOOL_MEMORY_STORE,
    TOOL_MEMORY_RECALL,
    # Coding agent dispatch
    TOOL_DISPATCH_CODING_AGENT,
    # Meeting tools
    TOOL_GET_MEETING_TRANSCRIPT,
    TOOL_LIST_MEETINGS,
    TOOL_CLASSIFY_MEETING,
    TOOL_LIST_UNCLASSIFIED_MEETINGS,
] + O365_ALL_TOOLS
