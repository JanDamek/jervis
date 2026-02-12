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

TOOL_JOERN_QUICK_SCAN: dict = {
    "type": "function",
    "function": {
        "name": "joern_quick_scan",
        "description": (
            "Analyze code for security issues, complexity, or architecture patterns using Joern CPG analysis. "
            "Scan types:\n"
            "- 'security': Find SQL injection, command injection, hardcoded secrets\n"
            "- 'dataflow': Identify HTTP input sources and sensitive sinks (taint analysis)\n"
            "- 'callgraph': Method fan-out analysis, dead code detection\n"
            "- 'complexity': Cyclomatic complexity measurement, long method detection\n\n"
            "Use this when user asks about code quality, security vulnerabilities, or architectural patterns."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "scan_type": {
                    "type": "string",
                    "enum": ["security", "dataflow", "callgraph", "complexity"],
                    "description": "Type of analysis to run.",
                },
            },
            "required": ["scan_type"],
        },
    },
}

TOOL_GIT_BRANCH_LIST: dict = {
    "type": "function",
    "function": {
        "name": "git_branch_list",
        "description": (
            "List all git branches in the project repository. Returns branch names with "
            "metadata (default branch marker, file counts). Use this to validate branch "
            "references or understand available branches before planning work."
        ),
        "parameters": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
}

TOOL_GET_RECENT_COMMITS: dict = {
    "type": "function",
    "function": {
        "name": "get_recent_commits",
        "description": (
            "Get recent git commits from the project repository. Returns commit hashes, "
            "messages, authors, and dates. Use this to understand recent work, avoid "
            "duplicate implementations, or see project activity."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Maximum number of commits to return (default 10).",
                    "default": 10,
                },
                "branch": {
                    "type": "string",
                    "description": "Filter by branch name (optional, defaults to all branches).",
                },
            },
            "required": [],
        },
    },
}

TOOL_GET_TECHNOLOGY_STACK: dict = {
    "type": "function",
    "function": {
        "name": "get_technology_stack",
        "description": (
            "Get the technology stack of the project: programming languages, frameworks, "
            "libraries, and architectural patterns. Use this before designing features "
            "to ensure consistency with existing tech choices."
        ),
        "parameters": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
}

TOOL_GET_REPOSITORY_STRUCTURE: dict = {
    "type": "function",
    "function": {
        "name": "get_repository_structure",
        "description": (
            "Get the directory structure of the repository: top-level directories with "
            "file counts and brief descriptions. Use this to understand project layout "
            "before decomposing tasks or planning where to make changes."
        ),
        "parameters": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
}

TOOL_CODE_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "code_search",
        "description": (
            "Search for code patterns, functions, classes, or specific implementations "
            "in the codebase. Uses semantic search to find relevant code snippets. "
            "Optionally filter by programming language."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search query (e.g., 'authentication logic', 'error handlers', 'database queries').",
                },
                "language": {
                    "type": "string",
                    "description": "Filter by language (e.g., 'Python', 'Kotlin', 'Java', optional).",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum results to return (default 5).",
                    "default": 5,
                },
            },
            "required": ["query"],
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
    TOOL_JOERN_QUICK_SCAN,
    TOOL_GIT_BRANCH_LIST,
    TOOL_GET_RECENT_COMMITS,
    TOOL_GET_TECHNOLOGY_STACK,
    TOOL_GET_REPOSITORY_STRUCTURE,
    TOOL_CODE_SEARCH,
]
