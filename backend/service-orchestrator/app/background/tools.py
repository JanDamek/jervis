"""Background-specific tool subset.

Background tasks have access to a subset of tools:
- KB tools (search, store, stats, indexed items)
- Brain tools (Jira/Confluence CRUD)
- Web search
- Code/repository tools
- Filesystem tools (read-only)
- dispatch_coding_agent (K8s Job)
- Scheduled task creation

Background tasks do NOT have:
- ask_user (no user in background)
- memory_store/recall (foreground only)
- list_affairs (foreground only)
"""

from __future__ import annotations

from app.tools.definitions import (
    # KB tools
    TOOL_KB_SEARCH,
    TOOL_GET_KB_STATS,
    TOOL_GET_INDEXED_ITEMS,
    TOOL_STORE_KNOWLEDGE,
    # Web
    TOOL_WEB_SEARCH,
    # Repository/code tools
    TOOL_LIST_PROJECT_FILES,
    TOOL_GET_REPOSITORY_INFO,
    TOOL_GET_RECENT_COMMITS,
    TOOL_GET_TECHNOLOGY_STACK,
    TOOL_GET_REPOSITORY_STRUCTURE,
    TOOL_CODE_SEARCH,
    TOOL_GIT_BRANCH_LIST,
    # Git workspace tools
    TOOL_GIT_STATUS,
    TOOL_GIT_LOG,
    TOOL_GIT_DIFF,
    TOOL_GIT_SHOW,
    # Filesystem (read-only)
    TOOL_LIST_FILES,
    TOOL_READ_FILE,
    TOOL_FIND_FILES,
    TOOL_GREP_FILES,
    TOOL_FILE_INFO,
    # Scheduled tasks
    TOOL_CREATE_SCHEDULED_TASK,
)

from app.tools.definitions import BRAIN_TOOLS

from app.chat.tools import TOOL_DISPATCH_CODING_AGENT

# All tools available to background handler
ALL_BACKGROUND_TOOLS: list[dict] = [
    # KB
    TOOL_KB_SEARCH,
    TOOL_GET_KB_STATS,
    TOOL_GET_INDEXED_ITEMS,
    TOOL_STORE_KNOWLEDGE,
    # Web
    TOOL_WEB_SEARCH,
    # Repository
    TOOL_LIST_PROJECT_FILES,
    TOOL_GET_REPOSITORY_INFO,
    TOOL_GET_RECENT_COMMITS,
    TOOL_GET_TECHNOLOGY_STACK,
    TOOL_GET_REPOSITORY_STRUCTURE,
    TOOL_CODE_SEARCH,
    TOOL_GIT_BRANCH_LIST,
    # Git workspace
    TOOL_GIT_STATUS,
    TOOL_GIT_LOG,
    TOOL_GIT_DIFF,
    TOOL_GIT_SHOW,
    # Filesystem
    TOOL_LIST_FILES,
    TOOL_READ_FILE,
    TOOL_FIND_FILES,
    TOOL_GREP_FILES,
    TOOL_FILE_INFO,
    # Scheduled tasks
    TOOL_CREATE_SCHEDULED_TASK,
    # Coding agent dispatch
    TOOL_DISPATCH_CODING_AGENT,
] + BRAIN_TOOLS
