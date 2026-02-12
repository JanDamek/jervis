"""OpenAI function-calling tool schemas for the respond node.

These are passed to litellm via the `tools` parameter so the LLM
can request web searches and KB lookups during the agentic loop.
"""

TOOL_WEB_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "web_search",
        "description": (
            "Search the internet using SearXNG. Use this tool to find current "
            "information such as business locations, phone numbers, opening hours, "
            "news, prices, or any factual data you don't have in your training data."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query in the most effective language for the topic.",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results to return (default 5).",
                    "default": 5,
                },
            },
            "required": ["query"],
        },
    },
}

TOOL_KB_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "kb_search",
        "description": (
            "Search the internal Knowledge Base for project documentation, "
            "architecture decisions, coding conventions, meeting notes, and other "
            "ingested content. Use this when the user's question relates to their "
            "projects, codebase, or internal documentation."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Semantic search query describing what you're looking for.",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results to return (default 5).",
                    "default": 5,
                },
            },
            "required": ["query"],
        },
    },
}

TOOL_GET_INDEXED_ITEMS: dict = {
    "type": "function",
    "function": {
        "name": "get_indexed_items",
        "description": (
            "Get a summary of what has been indexed into the Knowledge Base for this project. "
            "Returns counts and examples of git commits, JIRA issues, Confluence pages, emails, "
            "and other indexed content. Use this to understand what information is available "
            "before searching for specific items."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "item_type": {
                    "type": "string",
                    "description": "Filter by type: 'git', 'jira', 'confluence', 'email', or 'all' (default).",
                    "enum": ["all", "git", "jira", "confluence", "email"],
                    "default": "all",
                },
                "limit": {
                    "type": "integer",
                    "description": "Max items to return per type (default 10).",
                    "default": 10,
                },
            },
            "required": [],
        },
    },
}

TOOL_GET_KB_STATS: dict = {
    "type": "function",
    "function": {
        "name": "get_kb_stats",
        "description": (
            "Get statistics about the Knowledge Base content for this client/project. "
            "Returns: total documents, graph nodes (files, classes, functions), "
            "branches, commits count, issue tracker stats. Use this FIRST to understand "
            "what data is available before answering questions."
        ),
        "parameters": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
}

TOOL_LIST_PROJECT_FILES: dict = {
    "type": "function",
    "function": {
        "name": "list_project_files",
        "description": (
            "List files in the project's repository from the Knowledge Base graph. "
            "Shows file paths, programming languages, and branch information. "
            "Use this to answer questions about project structure, file types, or what files exist."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "branch": {
                    "type": "string",
                    "description": "Filter by branch name (optional, defaults to all branches).",
                },
                "file_pattern": {
                    "type": "string",
                    "description": "Filter by file pattern (e.g., '*.py', 'src/*', optional).",
                },
                "limit": {
                    "type": "integer",
                    "description": "Max files to return (default 50).",
                    "default": 50,
                },
            },
            "required": [],
        },
    },
}

TOOL_GET_REPOSITORY_INFO: dict = {
    "type": "function",
    "function": {
        "name": "get_repository_info",
        "description": (
            "Get information about the project's repository: branches, default branch, "
            "recent commits, technology stack, and file statistics. Use this to understand "
            "the overall repository structure and status."
        ),
        "parameters": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
}

ALL_RESPOND_TOOLS: list[dict] = [
    TOOL_WEB_SEARCH,
    TOOL_KB_SEARCH,
    TOOL_GET_INDEXED_ITEMS,
    TOOL_GET_KB_STATS,
    TOOL_LIST_PROJECT_FILES,
    TOOL_GET_REPOSITORY_INFO,
]
