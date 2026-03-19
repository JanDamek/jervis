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

TOOL_WEB_FETCH: dict = {
    "type": "function",
    "function": {
        "name": "web_fetch",
        "description": (
            "Fetch the content of a web page (URL) and return its text. "
            "Use after web_search to read the actual page content, or directly "
            "when you know the URL. Returns cleaned text (HTML tags stripped). "
            "Useful for: reading existing websites, checking if a URL is live, "
            "scraping page content for analysis or comparison."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The URL to fetch (must start with http:// or https://).",
                },
                "max_length": {
                    "type": "integer",
                    "description": "Maximum text length to return in characters (default 10000).",
                    "default": 10000,
                },
            },
            "required": ["url"],
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

TOOL_KB_DELETE: dict = {
    "type": "function",
    "function": {
        "name": "kb_delete",
        "description": (
            "Delete incorrect or outdated entries from the Knowledge Base by sourceUrn. "
            "Use this when you discover that information in KB is wrong, outdated, or "
            "when the user tells you a previous answer was incorrect and the bad data "
            "came from KB. Requires the sourceUrn (visible in kb_search results)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "source_urn": {
                    "type": "string",
                    "description": "The sourceUrn of the KB entry to delete (from kb_search results).",
                },
                "reason": {
                    "type": "string",
                    "description": "Why this entry is being deleted (for audit log).",
                },
            },
            "required": ["source_urn", "reason"],
        },
    },
}

TOOL_GET_INDEXED_ITEMS: dict = {
    "type": "function",
    "function": {
        "name": "get_indexed_items",
        "description": (
            "Get a summary of what has been indexed into the Knowledge Base for this project. "
            "Returns counts and examples of indexed content by source type (git commits, "
            "issue tracker items, documents, emails, chat messages, etc.). "
            "Use this to understand what information is available before searching."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "item_type": {
                    "type": "string",
                    "description": "Filter by source type: 'git', 'issues', 'documents', 'email', 'chat', or 'all' (default).",
                    "enum": ["all", "git", "issues", "documents", "email", "chat"],
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

TOOL_STORE_KNOWLEDGE: dict = {
    "type": "function",
    "function": {
        "name": "store_knowledge",
        "description": (
            "Store new knowledge or facts into the Knowledge Base. "
            "Use this when the user teaches you something new, provides definitions, "
            "explains concepts, confirms requirements, or shares information that should be remembered. "
            "IMPORTANT: During requirement discussions, store each confirmed decision/requirement "
            "with category 'specification'. This ensures nothing is lost when the user later says "
            "'implement it' — all decisions can be reconstructed from KB.\n"
            "Examples: 'BMS is Brokerage Management System', 'project uses Python 3.11', "
            "'Platform decision: Android + iOS', 'Storage: PostgreSQL for book catalog'."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "subject": {
                    "type": "string",
                    "description": "Brief subject/title of the knowledge being stored (e.g., 'BMS definition', 'Platform decision').",
                },
                "content": {
                    "type": "string",
                    "description": (
                        "The actual knowledge/fact to store. Include enough detail and reasoning "
                        "to reconstruct the decision later. No artificial length limits."
                    ),
                },
                "category": {
                    "type": "string",
                    "description": (
                        "Category of knowledge. Choose the most specific match:\n"
                        "- specification: project requirement, design decision, feature decision "
                        "(e.g., 'Platform: Android + iOS', 'Storage: PostgreSQL', 'Auth: OAuth2')\n"
                        "- preference: coding style, tooling, workflow (e.g., 'preferuji Kotlin idiomaticky')\n"
                        "- domain: business domain, industry, location (e.g., 'jsme z Palkovic', 'BMS je...')\n"
                        "- team: people, roles, processes (e.g., 'Jan je tech lead', 'Scrum s 2-week sprinty')\n"
                        "- tech_stack: frameworks, libraries, patterns (e.g., 'Kotlin Multiplatform', 'MongoDB')\n"
                        "- personal: personal info about the user (e.g., 'jmenuju se Jan')\n"
                        "- general: anything that doesn't fit above categories"
                    ),
                    "enum": ["specification", "preference", "domain", "team", "tech_stack", "personal", "general"],
                    "default": "general",
                },
                "target_project_name": {
                    "type": "string",
                    "description": (
                        "Optional: name of a DIFFERENT project this knowledge applies to. "
                        "Use when the user references another project during discussion "
                        "(e.g., 'this logging solution would work in project XYZ'). "
                        "The knowledge will be tagged for both the current and target project."
                    ),
                },
            },
            "required": ["subject", "content"],
        },
    },
}

TOOL_ASK_USER: dict = {
    "type": "function",
    "function": {
        "name": "ask_user",
        "description": (
            "Ask the user a clarification question when you need more information "
            "to complete the task. Use this when the query is ambiguous, you need "
            "a preference choice, or missing critical details. The execution will "
            "pause until the user responds. Use sparingly — only when you truly "
            "cannot proceed without user input."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "question": {
                    "type": "string",
                    "description": "The question to ask the user. Be specific and concise.",
                },
            },
            "required": ["question"],
        },
    },
}

TOOL_CREATE_SCHEDULED_TASK: dict = {
    "type": "function",
    "function": {
        "name": "create_scheduled_task",
        "description": (
            "Create a scheduled task or reminder for future work. "
            "Use this when you need to do something later: verify information when code becomes available, "
            "check back on incomplete data, follow up on user requests, or schedule future analysis. "
            "Examples: 'Ověřit BMS kód proti definici', 'Zkontrolovat architekturu za týden'. "
            "IMPORTANT: Title and description MUST be in Czech. "
            "NEVER use internal system IDs (MongoDB ObjectIds like 698778e6e5e2160711bcdd83) as document/invoice numbers — "
            "describe items by their real content (sender name, email subject, filename)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Brief title in CZECH (e.g., 'Ověřit BMS kód proti definici'). Never use internal IDs.",
                },
                "description": {
                    "type": "string",
                    "description": "Detailed description in CZECH of what needs to be done in the future.",
                },
                "reason": {
                    "type": "string",
                    "description": "Why this task is needed, in CZECH (e.g., 'Uživatel definoval ale kód zatím neindexován').",
                },
                "schedule": {
                    "type": "string",
                    "description": "When to do this: 'when_code_available', 'in_1_day', 'in_1_week', 'in_1_month', or 'manual'.",
                    "enum": ["when_code_available", "in_1_day", "in_1_week", "in_1_month", "manual"],
                    "default": "manual",
                },
                "scheduled_at": {
                    "type": "string",
                    "description": (
                        "Specific date/time in ISO-8601 format (e.g., '2026-03-09T15:00:00'). "
                        "Use this instead of 'schedule' when user specifies an exact time. "
                        "Takes precedence over 'schedule' if both provided."
                    ),
                },
                "urgency": {
                    "type": "string",
                    "description": (
                        "Task urgency when it fires: 'urgent' sends URGENT_ALERT to user, 'normal' creates background task. "
                        "Only use 'urgent' after verifying the full context — search KB for related items, "
                        "follow-ups, and resolutions to confirm the issue is real, current, and unhandled."
                    ),
                    "enum": ["urgent", "normal"],
                    "default": "normal",
                },
            },
            "required": ["title", "description", "reason"],
        },
    },
}
TOOL_WEB_CRAWL: dict = {
    "type": "function",
    "function": {
        "name": "web_crawl",
        "description": (
            "Crawl a website and ingest its content into the Knowledge Base. "
            "Follows links up to specified depth. Use for indexing client websites, "
            "project documentation, wikis, or any web content the project needs to know about. "
            "NOT for general documentation (Spring, React, etc.) — only project-specific content. "
            "Content is scoped to the current client/project in KB."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The starting URL to crawl (must start with http:// or https://).",
                },
                "max_depth": {
                    "type": "integer",
                    "description": "How deep to follow links (default 2). 1 = only the given page, 2 = page + linked pages.",
                    "default": 2,
                },
                "allow_external": {
                    "type": "boolean",
                    "description": "Whether to follow links to other domains (default false).",
                    "default": False,
                },
            },
            "required": ["url"],
        },
    },
}

ALL_RESPOND_TOOLS: list[dict] = [
    TOOL_WEB_SEARCH,
    TOOL_WEB_FETCH,
    TOOL_WEB_CRAWL,
    TOOL_KB_SEARCH,
    TOOL_KB_DELETE,
    TOOL_STORE_KNOWLEDGE,
    TOOL_ASK_USER,
    TOOL_CREATE_SCHEDULED_TASK,
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

# Git workspace tools (execute in agent workspace directory)
TOOL_GIT_STATUS: dict = {
    "type": "function",
    "function": {
        "name": "git_status",
        "description": "Get git status of the workspace (modified files, staged changes, current branch)",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
}

TOOL_GIT_LOG: dict = {
    "type": "function",
    "function": {
        "name": "git_log",
        "description": "Get git commit history with optional branch and limit",
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Number of commits to show (default 10)",
                },
                "branch": {
                    "type": "string",
                    "description": "Branch name (default current branch)",
                },
            },
        },
    },
}

TOOL_GIT_DIFF: dict = {
    "type": "function",
    "function": {
        "name": "git_diff",
        "description": "Show differences between commits, branches, or working directory",
        "parameters": {
            "type": "object",
            "properties": {
                "commit1": {
                    "type": "string",
                    "description": "First commit/branch (optional, defaults to HEAD)",
                },
                "commit2": {
                    "type": "string",
                    "description": "Second commit/branch (optional, shows working dir changes if omitted)",
                },
                "file_path": {
                    "type": "string",
                    "description": "Specific file to diff (optional)",
                },
            },
        },
    },
}

TOOL_GIT_SHOW: dict = {
    "type": "function",
    "function": {
        "name": "git_show",
        "description": "Show commit details and changes for a specific commit",
        "parameters": {
            "type": "object",
            "properties": {
                "commit": {
                    "type": "string",
                    "description": "Commit hash or reference (e.g., HEAD, HEAD~1)",
                },
            },
            "required": ["commit"],
        },
    },
}

TOOL_GIT_BLAME: dict = {
    "type": "function",
    "function": {
        "name": "git_blame",
        "description": "Show who last modified each line of a file (authorship info)",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to file relative to workspace root",
                },
            },
            "required": ["file_path"],
        },
    },
}

GIT_WORKSPACE_TOOLS: list[dict] = [
    TOOL_GIT_STATUS,
    TOOL_GIT_LOG,
    TOOL_GIT_DIFF,
    TOOL_GIT_SHOW,
    TOOL_GIT_BLAME,
]

# Filesystem tools (execute in agent workspace directory)
TOOL_LIST_FILES: dict = {
    "type": "function",
    "function": {
        "name": "list_files",
        "description": "List files and directories in the workspace or a subdirectory",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Relative path within workspace (default: root '.')",
                    "default": ".",
                },
                "show_hidden": {
                    "type": "boolean",
                    "description": "Include hidden files (default: false)",
                    "default": False,
                },
            },
        },
    },
}

TOOL_READ_FILE: dict = {
    "type": "function",
    "function": {
        "name": "read_file",
        "description": "Read contents of a file in the workspace",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to file relative to workspace root",
                },
                "max_lines": {
                    "type": "integer",
                    "description": "Maximum number of lines to read (default: 1000)",
                    "default": 1000,
                },
            },
            "required": ["file_path"],
        },
    },
}

TOOL_FIND_FILES: dict = {
    "type": "function",
    "function": {
        "name": "find_files",
        "description": "Find files by name pattern or glob in the workspace",
        "parameters": {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "Filename pattern (supports wildcards: *.py, **/*.kt, etc.)",
                },
                "path": {
                    "type": "string",
                    "description": "Directory to search in (default: workspace root)",
                    "default": ".",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results (default: 100)",
                    "default": 100,
                },
            },
            "required": ["pattern"],
        },
    },
}

TOOL_GREP_FILES: dict = {
    "type": "function",
    "function": {
        "name": "grep_files",
        "description": "Search for text/regex pattern in files within the workspace",
        "parameters": {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "Text or regex pattern to search for",
                },
                "file_pattern": {
                    "type": "string",
                    "description": "File glob to search in (e.g., '*.py', '**/*.kt')",
                    "default": "*",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of matches (default: 50)",
                    "default": 50,
                },
                "context_lines": {
                    "type": "integer",
                    "description": "Number of context lines around match (default: 2)",
                    "default": 2,
                },
            },
            "required": ["pattern"],
        },
    },
}

TOOL_FILE_INFO: dict = {
    "type": "function",
    "function": {
        "name": "file_info",
        "description": "Get metadata about a file or directory (size, modification time, type)",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Path to file/directory relative to workspace root",
                },
            },
            "required": ["path"],
        },
    },
}

FILESYSTEM_TOOLS: list[dict] = [
    TOOL_LIST_FILES,
    TOOL_READ_FILE,
    TOOL_FIND_FILES,
    TOOL_GREP_FILES,
    TOOL_FILE_INFO,
]

# Terminal tool (execute shell commands in workspace)
TOOL_EXECUTE_COMMAND: dict = {
    "type": "function",
    "function": {
        "name": "execute_command",
        "description": (
            "Execute a shell command in the workspace directory. "
            "Supported safe commands: ls, cat, head, tail, wc, grep, find, echo, pwd, "
            "tree, file, diff, patch, make, npm, yarn, pip, python, node, javac, kotlinc, "
            "mvn, gradle, cargo, go. "
            "Use this for build operations, testing, or examining workspace state."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "Shell command to execute (will be run in workspace directory)",
                },
                "timeout": {
                    "type": "integer",
                    "description": "Timeout in seconds (default: 30, max: 300)",
                    "default": 30,
                },
            },
            "required": ["command"],
        },
    },
}

TERMINAL_TOOLS: list[dict] = [
    TOOL_EXECUTE_COMMAND,
]

ALL_RESPOND_TOOLS_WITH_GIT: list[dict] = ALL_RESPOND_TOOLS + GIT_WORKSPACE_TOOLS
ALL_RESPOND_TOOLS_FULL_BASE: list[dict] = (
    ALL_RESPOND_TOOLS + GIT_WORKSPACE_TOOLS + FILESYSTEM_TOOLS + TERMINAL_TOOLS
)


# ============================================================
# Memory Agent tools
# ============================================================

TOOL_MEMORY_STORE: dict = {
    "type": "function",
    "function": {
        "name": "memory_store",
        "description": (
            "Store a fact, decision, or piece of information into working memory. "
            "Information is immediately available for recall and will be persisted to KB. "
            "Use this when the user tells you something important: orders, deadlines, "
            "contacts, preferences, procedures, or any fact worth remembering."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "subject": {
                    "type": "string",
                    "description": "Brief subject/title (e.g., 'eBay order status', 'meeting deadline').",
                },
                "content": {
                    "type": "string",
                    "description": "The fact or information to remember.",
                },
                "category": {
                    "type": "string",
                    "description": "Category: fact, decision, order, deadline, contact, preference, procedure.",
                    "enum": ["fact", "decision", "order", "deadline", "contact", "preference", "procedure"],
                    "default": "fact",
                },
                "priority": {
                    "type": "string",
                    "description": "Write priority: critical (sync), high (fast), normal (best-effort).",
                    "enum": ["critical", "high", "normal"],
                    "default": "normal",
                },
            },
            "required": ["subject", "content"],
        },
    },
}

TOOL_MEMORY_RECALL: dict = {
    "type": "function",
    "function": {
        "name": "memory_recall",
        "description": (
            "Search working memory (recent facts, write buffer) and Knowledge Base "
            "for relevant information. Use this to recall previously stored facts, "
            "check affair details, or find information from past conversations."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "What to search for (e.g., 'eBay order', 'meeting notes').",
                },
                "scope": {
                    "type": "string",
                    "description": (
                        "Search scope: "
                        "'current' = active affair + write buffer only, "
                        "'all' = all affairs + write buffer + KB, "
                        "'kb_only' = Knowledge Base only."
                    ),
                    "enum": ["current", "all", "kb_only"],
                    "default": "all",
                },
            },
            "required": ["query"],
        },
    },
}

TOOL_LIST_AFFAIRS: dict = {
    "type": "function",
    "function": {
        "name": "list_affairs",
        "description": (
            "List all active and parked affairs (thematic contexts). "
            "Shows titles, status, key facts, and pending actions for each affair. "
            "Use this to understand what topics are being tracked."
        ),
        "parameters": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
}

MEMORY_TOOLS: list[dict] = [TOOL_MEMORY_STORE, TOOL_MEMORY_RECALL, TOOL_LIST_AFFAIRS]

# ============================================================
# Environment management tools (K8s test environments)
# ============================================================

TOOL_ENVIRONMENT_LIST: dict = {
    "type": "function",
    "function": {
        "name": "environment_list",
        "description": (
            "List K8s test environments. Shows environment names, namespaces, states, "
            "and component counts. Use to find existing environments before creating new ones."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Filter by client ID (optional, uses current client if empty).",
                },
            },
            "required": [],
        },
    },
}

TOOL_ENVIRONMENT_GET: dict = {
    "type": "function",
    "function": {
        "name": "environment_get",
        "description": (
            "Get detailed information about a specific environment including all components, "
            "ports, env vars, property mappings, and agent instructions."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "The environment ID.",
                },
            },
            "required": ["environment_id"],
        },
    },
}

TOOL_ENVIRONMENT_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "environment_create",
        "description": (
            "Create a new K8s test environment. Creates the definition in DB (state=PENDING). "
            "Add components with environment_add_component, then deploy with environment_deploy."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "Human-readable environment name.",
                },
                "namespace": {
                    "type": "string",
                    "description": "K8s namespace (auto-generated from name if empty).",
                },
                "tier": {
                    "type": "string",
                    "enum": ["DEV", "STAGING", "PROD"],
                    "description": "Environment tier (default DEV).",
                },
                "description": {
                    "type": "string",
                    "description": "Optional environment description.",
                },
                "agent_instructions": {
                    "type": "string",
                    "description": "Free-text instructions for agents working with this environment.",
                },
                "storage_size_gi": {
                    "type": "integer",
                    "description": "PVC storage size in Gi (default 5).",
                    "default": 5,
                },
            },
            "required": ["name"],
        },
    },
}

TOOL_ENVIRONMENT_ADD_COMPONENT: dict = {
    "type": "function",
    "function": {
        "name": "environment_add_component",
        "description": (
            "Add a component to an environment. Types: POSTGRESQL, MONGODB, REDIS, RABBITMQ, "
            "KAFKA, ELASTICSEARCH, ORACLE, MYSQL, MINIO, CUSTOM_INFRA, PROJECT. "
            "Infrastructure components get default images, ports, and env vars from templates."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "The environment ID.",
                },
                "name": {
                    "type": "string",
                    "description": "Component name (used as K8s service/deployment name).",
                },
                "component_type": {
                    "type": "string",
                    "description": "Component type.",
                    "enum": [
                        "POSTGRESQL", "MONGODB", "REDIS", "RABBITMQ", "KAFKA",
                        "ELASTICSEARCH", "ORACLE", "MYSQL", "MINIO", "CUSTOM_INFRA", "PROJECT",
                    ],
                },
                "image": {
                    "type": "string",
                    "description": "Docker image override (uses template default if empty).",
                },
                "version": {
                    "type": "string",
                    "description": "Version hint to pick from template (e.g., '17', '8.0').",
                },
                "env_vars": {
                    "type": "string",
                    "description": "Additional env vars as JSON string (merged with defaults).",
                },
                "source_repo": {
                    "type": "string",
                    "description": "Git repo URL (for PROJECT type).",
                },
                "source_branch": {
                    "type": "string",
                    "description": "Git branch (for PROJECT type).",
                },
                "dockerfile_path": {
                    "type": "string",
                    "description": "Path to Dockerfile in repo (for PROJECT type).",
                },
            },
            "required": ["environment_id", "name", "component_type"],
        },
    },
}

TOOL_ENVIRONMENT_CONFIGURE: dict = {
    "type": "function",
    "function": {
        "name": "environment_configure",
        "description": (
            "Update configuration of an existing component in an environment. "
            "Only provided fields are updated. Use environment_sync after to apply changes."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "The environment ID.",
                },
                "component_name": {
                    "type": "string",
                    "description": "Name or ID of the component to configure.",
                },
                "image": {
                    "type": "string",
                    "description": "New Docker image.",
                },
                "env_vars": {
                    "type": "string",
                    "description": "Additional env vars as JSON string (merged with existing).",
                },
                "cpu_limit": {
                    "type": "string",
                    "description": "K8s CPU limit (e.g., '500m').",
                },
                "memory_limit": {
                    "type": "string",
                    "description": "K8s memory limit (e.g., '512Mi').",
                },
                "source_repo": {
                    "type": "string",
                    "description": "Git repo URL (for PROJECT type).",
                },
                "source_branch": {
                    "type": "string",
                    "description": "Git branch (for PROJECT type).",
                },
                "dockerfile_path": {
                    "type": "string",
                    "description": "Path to Dockerfile in repo (for PROJECT type).",
                },
            },
            "required": ["environment_id", "component_name"],
        },
    },
}

TOOL_ENVIRONMENT_DEPLOY: dict = {
    "type": "function",
    "function": {
        "name": "environment_deploy",
        "description": (
            "Provision/deploy an environment to K8s. Creates namespace, PVC, "
            "and deploys infrastructure components. Environment must be PENDING, STOPPED, or ERROR."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "The environment ID to deploy.",
                },
            },
            "required": ["environment_id"],
        },
    },
}

TOOL_ENVIRONMENT_STOP: dict = {
    "type": "function",
    "function": {
        "name": "environment_stop",
        "description": (
            "Stop/deprovision an environment. Tears down K8s resources but "
            "preserves the definition in DB for re-deployment."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "The environment ID to stop.",
                },
            },
            "required": ["environment_id"],
        },
    },
}

TOOL_ENVIRONMENT_STATUS: dict = {
    "type": "function",
    "function": {
        "name": "environment_status",
        "description": (
            "Get deployment status of an environment: per-component readiness, "
            "replica counts, and error messages."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "The environment ID.",
                },
            },
            "required": ["environment_id"],
        },
    },
}

TOOL_ENVIRONMENT_SYNC: dict = {
    "type": "function",
    "function": {
        "name": "environment_sync",
        "description": (
            "Re-apply K8s manifests from DB for a running environment. "
            "Use after modifying component config to apply changes to running deployments."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "The environment ID to sync.",
                },
            },
            "required": ["environment_id"],
        },
    },
}

TOOL_ENVIRONMENT_DELETE: dict = {
    "type": "function",
    "function": {
        "name": "environment_delete",
        "description": (
            "Delete an environment and its K8s namespace. WARNING: Permanently removes "
            "all K8s resources in the namespace."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "The environment ID to delete.",
                },
            },
            "required": ["environment_id"],
        },
    },
}

TOOL_ENVIRONMENT_CLONE: dict = {
    "type": "function",
    "function": {
        "name": "environment_clone",
        "description": (
            "Clone an existing environment to a new scope (different client, group, or project). "
            "Creates a fresh copy with PENDING state and reset runtime state. "
            "Useful for promoting environments across tiers (DEV→STAGING→PROD) "
            "or replicating setups across projects."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "Source environment ID to clone from.",
                },
                "new_name": {
                    "type": "string",
                    "description": "Name for the cloned environment.",
                },
                "new_namespace": {
                    "type": "string",
                    "description": "K8s namespace for the clone (auto-generated from name if empty).",
                },
                "new_tier": {
                    "type": "string",
                    "enum": ["DEV", "STAGING", "PROD"],
                    "description": "Tier for the clone (inherits from source if empty).",
                },
                "target_client_id": {
                    "type": "string",
                    "description": "Move clone to a different client (uses source client if empty).",
                },
                "target_project_id": {
                    "type": "string",
                    "description": "Assign clone to a specific project (uses source project if empty).",
                },
            },
            "required": ["environment_id", "new_name"],
        },
    },
}

TOOL_ENVIRONMENT_ADD_MAPPING: dict = {
    "type": "function",
    "function": {
        "name": "environment_add_property_mapping",
        "description": (
            "Add an environment variable mapping from an infrastructure component to a project. "
            "Maps an ENV var to a value derived from infra (host, port, credentials). "
            "Use template placeholders: {host}, {port}, {name}, {env:VAR_NAME}."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "The environment ID.",
                },
                "project_component": {
                    "type": "string",
                    "description": "ID of the project component receiving the env var.",
                },
                "property_name": {
                    "type": "string",
                    "description": "ENV var name (e.g., DATABASE_URL, SPRING_DATASOURCE_URL).",
                },
                "target_component": {
                    "type": "string",
                    "description": "ID of the infrastructure component (source of connection info).",
                },
                "value_template": {
                    "type": "string",
                    "description": "Value template with placeholders ({host}, {port}, {env:VAR_NAME}).",
                },
            },
            "required": ["environment_id", "project_component", "property_name", "target_component", "value_template"],
        },
    },
}

TOOL_ENVIRONMENT_AUTO_SUGGEST_MAPPINGS: dict = {
    "type": "function",
    "function": {
        "name": "environment_auto_suggest_mappings",
        "description": (
            "Auto-generate property mappings for all PROJECT x INFRA component pairs. "
            "Uses predefined templates (JDBC URLs, Redis URIs, etc.). "
            "Skips existing mappings. Run after adding all components."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "environment_id": {
                    "type": "string",
                    "description": "The environment ID.",
                },
            },
            "required": ["environment_id"],
        },
    },
}

TOOL_ENVIRONMENT_KEEP_RUNNING: dict = {
    "type": "function",
    "function": {
        "name": "environment_keep_running",
        "description": (
            "Mark the current environment to stay running after the task completes. "
            "By default environments are auto-stopped when the coding task finishes. "
            "Use this when the user wants to keep it running for manual testing. "
            "Call with enabled=false to revert to auto-stop behavior."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "enabled": {
                    "type": "boolean",
                    "description": "true = keep running after task, false = auto-stop (default).",
                },
            },
            "required": ["enabled"],
        },
    },
}

ENVIRONMENT_TOOLS: list[dict] = [
    TOOL_ENVIRONMENT_LIST,
    TOOL_ENVIRONMENT_GET,
    TOOL_ENVIRONMENT_CREATE,
    TOOL_ENVIRONMENT_CLONE,
    TOOL_ENVIRONMENT_ADD_COMPONENT,
    TOOL_ENVIRONMENT_CONFIGURE,
    TOOL_ENVIRONMENT_ADD_MAPPING,
    TOOL_ENVIRONMENT_AUTO_SUGGEST_MAPPINGS,
    TOOL_ENVIRONMENT_DEPLOY,
    TOOL_ENVIRONMENT_STOP,
    TOOL_ENVIRONMENT_STATUS,
    TOOL_ENVIRONMENT_SYNC,
    TOOL_ENVIRONMENT_DELETE,
    TOOL_ENVIRONMENT_KEEP_RUNNING,
]


# ============================================================
# Issue Tracker tools (GitHub Issues / GitLab Issues)
# ============================================================

TOOL_CREATE_ISSUE: dict = {
    "type": "function",
    "function": {
        "name": "create_issue",
        "description": (
            "Create a new issue on the project's bug tracker (GitHub Issues or GitLab Issues). "
            "Returns the issue key and URL."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Issue title"},
                "description": {"type": "string", "description": "Issue body/description (markdown supported)"},
                "labels": {"type": "string", "description": "Comma-separated labels (e.g. 'bug,priority:high')"},
            },
            "required": ["title"],
        },
    },
}

TOOL_UPDATE_ISSUE: dict = {
    "type": "function",
    "function": {
        "name": "update_issue",
        "description": (
            "Update an existing issue — change title, description, state (open/closed), or labels. "
            "Only provided fields are changed. Use state='closed' to close an issue. "
            "Labels replace all existing labels on the issue."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "issue_key": {"type": "string", "description": "Issue number (e.g. '#6', '6')"},
                "title": {"type": "string", "description": "New title (omit to keep current)"},
                "description": {"type": "string", "description": "New description (omit to keep current)"},
                "state": {"type": "string", "enum": ["open", "closed"], "description": "New state"},
                "labels": {"type": "string", "description": "Comma-separated labels to SET (replaces existing)"},
            },
            "required": ["issue_key"],
        },
    },
}

TOOL_ADD_ISSUE_COMMENT: dict = {
    "type": "function",
    "function": {
        "name": "add_issue_comment",
        "description": "Add a comment to an existing issue on the project's bug tracker.",
        "parameters": {
            "type": "object",
            "properties": {
                "issue_key": {"type": "string", "description": "Issue number (e.g. '#6', '6')"},
                "comment": {"type": "string", "description": "Comment body (markdown supported)"},
            },
            "required": ["issue_key", "comment"],
        },
    },
}

TOOL_LIST_ISSUES: dict = {
    "type": "function",
    "function": {
        "name": "list_issues",
        "description": "List all issues from the project's bug tracker (GitHub Issues or GitLab Issues).",
        "parameters": {"type": "object", "properties": {}},
    },
}

ISSUE_TOOLS: list[dict] = [
    TOOL_CREATE_ISSUE, TOOL_UPDATE_ISSUE, TOOL_ADD_ISSUE_COMMENT, TOOL_LIST_ISSUES,
]

ALL_RESPOND_TOOLS_FULL: list[dict] = ALL_RESPOND_TOOLS_FULL_BASE + MEMORY_TOOLS + [TOOL_ENVIRONMENT_KEEP_RUNNING] + ISSUE_TOOLS


# ============================================================
# Project Management Tools — SETUP vertex + MCP
# ============================================================

TOOL_CREATE_CLIENT: dict = {
    "type": "function",
    "function": {
        "name": "create_client",
        "description": (
            "Create a new client (organization / workspace). "
            "ONLY use when the user EXPLICITLY requests creation of a new client. "
            "BEFORE calling this, ALWAYS search existing clients first (get_clients_projects) — "
            "the name may just be spelled differently (abbreviation, typo, different language). "
            "99% of the time, the user means an EXISTING client."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "Client name (must be unique).",
                },
                "description": {
                    "type": "string",
                    "description": "Optional client description.",
                },
            },
            "required": ["name"],
        },
    },
}

TOOL_CREATE_PROJECT: dict = {
    "type": "function",
    "function": {
        "name": "create_project",
        "description": (
            "Create a new project within a client. "
            "ONLY use when the user EXPLICITLY requests creation of a new project. "
            "BEFORE calling this, ALWAYS search existing projects first (get_clients_projects) — "
            "the name may just be spelled differently, abbreviated, or in a different language. "
            "99% of the time, the user refers to an EXISTING project."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "ID of the client that owns this project.",
                },
                "name": {
                    "type": "string",
                    "description": "Project name.",
                },
                "description": {
                    "type": "string",
                    "description": "Optional project description.",
                },
            },
            "required": ["client_id", "name"],
        },
    },
}

TOOL_CREATE_CONNECTION: dict = {
    "type": "function",
    "function": {
        "name": "create_connection",
        "description": (
            "Create a new external service connection (GitHub, GitLab, Atlassian, etc.). "
            "Optionally link it to a client by providing client_id."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "Connection name (must be unique).",
                },
                "provider": {
                    "type": "string",
                    "description": "Service provider: GITHUB, GITLAB, ATLASSIAN, GOOGLE_WORKSPACE, GENERIC_EMAIL, SLACK, MICROSOFT_TEAMS, DISCORD.",
                },
                "auth_type": {
                    "type": "string",
                    "description": "Authentication type: NONE, BASIC, BEARER, OAUTH2.",
                    "default": "BEARER",
                },
                "base_url": {
                    "type": "string",
                    "description": "API base URL (leave empty for cloud providers).",
                },
                "bearer_token": {
                    "type": "string",
                    "description": "Bearer / personal access token.",
                },
                "is_cloud": {
                    "type": "boolean",
                    "description": "Use provider's default cloud URL.",
                    "default": False,
                },
                "client_id": {
                    "type": "string",
                    "description": "Client ID to link this connection to (adds to client's connectionIds).",
                },
            },
            "required": ["name", "provider"],
        },
    },
}

TOOL_CREATE_GIT_REPOSITORY: dict = {
    "type": "function",
    "function": {
        "name": "create_git_repository",
        "description": (
            "Create a new git repository on GitHub or GitLab via the provider API. "
            "Uses the specified connection's bearer token."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID whose connection will be used.",
                },
                "name": {
                    "type": "string",
                    "description": "Repository name.",
                },
                "description": {
                    "type": "string",
                    "description": "Optional repository description.",
                },
                "connection_id": {
                    "type": "string",
                    "description": "Specific connection ID to use (empty = auto-detect REPOSITORY connection).",
                },
                "is_private": {
                    "type": "boolean",
                    "description": "Whether the repository should be private.",
                    "default": True,
                },
            },
            "required": ["client_id", "name"],
        },
    },
}

TOOL_UPDATE_PROJECT: dict = {
    "type": "function",
    "function": {
        "name": "update_project",
        "description": (
            "Update an existing project's fields (e.g., set git remote URL after repo creation)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "project_id": {
                    "type": "string",
                    "description": "ID of the project to update.",
                },
                "description": {
                    "type": "string",
                    "description": "New project description.",
                },
                "git_remote_url": {
                    "type": "string",
                    "description": "Git remote URL (clone URL) to associate with the project.",
                },
            },
            "required": ["project_id"],
        },
    },
}

TOOL_INIT_WORKSPACE: dict = {
    "type": "function",
    "function": {
        "name": "init_workspace",
        "description": (
            "Initialize (clone) the workspace for a project. "
            "Triggers an async git clone of the project's repository."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "project_id": {
                    "type": "string",
                    "description": "ID of the project to initialize workspace for.",
                },
            },
            "required": ["project_id"],
        },
    },
}

TOOL_GET_STACK_RECOMMENDATIONS: dict = {
    "type": "function",
    "function": {
        "name": "get_stack_recommendations",
        "description": (
            "Get technology stack recommendations for a project based on requirements. "
            "Returns structured recommendations with pros/cons for architecture, platforms, "
            "storage, and features. Use this BEFORE asking the user to confirm choices. "
            "Pass the FULL accumulated requirements from the conversation."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "requirements": {
                    "type": "string",
                    "description": (
                        "Full description of project requirements accumulated from the conversation. "
                        "Include all mentioned features, platforms, storage needs, integrations, etc."
                    ),
                },
            },
            "required": ["requirements"],
        },
    },
}

# Keep backward-compatible alias
TOOL_LIST_TEMPLATES = TOOL_GET_STACK_RECOMMENDATIONS

PROJECT_MANAGEMENT_TOOLS: list[dict] = [
    TOOL_CREATE_CLIENT,
    TOOL_CREATE_PROJECT,
    TOOL_CREATE_CONNECTION,
    TOOL_CREATE_GIT_REPOSITORY,
    TOOL_UPDATE_PROJECT,
    TOOL_INIT_WORKSPACE,
    TOOL_GET_STACK_RECOMMENDATIONS,
]


# ============================================================
# Per-agent tool sets (multi-agent delegation system)
# Each specialist agent gets ONLY its own tools.
# ============================================================

# ResearchAgent — full access to KB, web, filesystem, git (read-only)
RESEARCH_AGENT_TOOLS: list[dict] = ALL_RESPOND_TOOLS_FULL

# CodingAgent — workspace + git tools for delegating to coding agents
CODING_AGENT_TOOLS: list[dict] = GIT_WORKSPACE_TOOLS + FILESYSTEM_TOOLS + TERMINAL_TOOLS

# GitAgent — git workspace operations + approval gate
GIT_AGENT_TOOLS: list[dict] = GIT_WORKSPACE_TOOLS + FILESYSTEM_TOOLS

# CodeReviewAgent — git diff + KB + filesystem read
CODE_REVIEW_AGENT_TOOLS: list[dict] = [
    TOOL_GIT_DIFF, TOOL_GIT_SHOW, TOOL_GIT_LOG, TOOL_GIT_BLAME,
    TOOL_KB_SEARCH, TOOL_CODE_SEARCH,
    TOOL_READ_FILE, TOOL_LIST_FILES, TOOL_FIND_FILES, TOOL_GREP_FILES,
]

# TestAgent — terminal + filesystem (for running tests, reading results)
TEST_AGENT_TOOLS: list[dict] = TERMINAL_TOOLS + FILESYSTEM_TOOLS

# IssueTrackerAgent — KB search + issue CRUD for cross-project work
TRACKER_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_GET_KB_STATS] + ISSUE_TOOLS

# WikiAgent — KB search for context
WIKI_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_GET_KB_STATS]

# DocumentationAgent — KB + filesystem for doc generation
DOCUMENTATION_AGENT_TOOLS: list[dict] = [
    TOOL_KB_SEARCH, TOOL_CODE_SEARCH,
    TOOL_READ_FILE, TOOL_LIST_FILES, TOOL_FIND_FILES, TOOL_GREP_FILES,
    TOOL_GET_REPOSITORY_STRUCTURE,
]

# DevOpsAgent — environment management + KB
DEVOPS_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_GET_KB_STATS] + ENVIRONMENT_TOOLS

# ProjectManagementAgent — KB search for planning
PROJECT_MANAGEMENT_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_GET_KB_STATS]

# SecurityAgent — Joern + KB
SECURITY_AGENT_TOOLS: list[dict] = [
    TOOL_JOERN_QUICK_SCAN, TOOL_KB_SEARCH, TOOL_CODE_SEARCH,
    TOOL_READ_FILE, TOOL_GREP_FILES,
]

# NOTE: O365-dependent agent tool sets are defined after O365 tool groups (below O365_ALL_TOOLS)

# LearningAgent — web + KB + code search
LEARNING_AGENT_TOOLS: list[dict] = [TOOL_WEB_SEARCH, TOOL_KB_SEARCH, TOOL_CODE_SEARCH]

# All agent tools (full tool access)
ALL_AGENT_TOOLS: list[dict] = ALL_RESPOND_TOOLS_FULL


# ============================================================
# O365 tools (Teams, Outlook, Calendar, OneDrive via O365 Gateway)
# ============================================================

# -- Teams --

TOOL_O365_TEAMS_LIST_CHATS: dict = {
    "type": "function",
    "function": {
        "name": "o365_teams_list_chats",
        "description": (
            "List recent Microsoft Teams chats for a client. "
            "Returns chat list with topic, type, and last message preview. "
            "Requires an active O365 browser session for the client."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID (JERVIS client).",
                },
                "top": {
                    "type": "integer",
                    "description": "Number of chats to return (max 50, default 20).",
                    "default": 20,
                },
            },
            "required": ["client_id"],
        },
    },
}

TOOL_O365_TEAMS_READ_CHAT: dict = {
    "type": "function",
    "function": {
        "name": "o365_teams_read_chat",
        "description": (
            "Read messages from a specific Microsoft Teams chat. "
            "Returns messages with sender, timestamp, and content."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "chat_id": {
                    "type": "string",
                    "description": "Chat ID (from o365_teams_list_chats).",
                },
                "top": {
                    "type": "integer",
                    "description": "Number of messages to return (default 20).",
                    "default": 20,
                },
            },
            "required": ["client_id", "chat_id"],
        },
    },
}

TOOL_O365_TEAMS_SEND_MESSAGE: dict = {
    "type": "function",
    "function": {
        "name": "o365_teams_send_message",
        "description": (
            "Send a message to a Microsoft Teams chat. "
            "Supports plain text and HTML content."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "chat_id": {
                    "type": "string",
                    "description": "Chat ID (from o365_teams_list_chats).",
                },
                "content": {
                    "type": "string",
                    "description": "Message content.",
                },
                "content_type": {
                    "type": "string",
                    "enum": ["text", "html"],
                    "description": "Content type (default 'text').",
                    "default": "text",
                },
            },
            "required": ["client_id", "chat_id", "content"],
        },
    },
}

TOOL_O365_TEAMS_LIST_TEAMS: dict = {
    "type": "function",
    "function": {
        "name": "o365_teams_list_teams",
        "description": "List Microsoft Teams the user is a member of.",
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
            },
            "required": ["client_id"],
        },
    },
}

TOOL_O365_TEAMS_LIST_CHANNELS: dict = {
    "type": "function",
    "function": {
        "name": "o365_teams_list_channels",
        "description": "List channels in a Microsoft Teams team.",
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "team_id": {
                    "type": "string",
                    "description": "Team ID (from o365_teams_list_teams).",
                },
            },
            "required": ["client_id", "team_id"],
        },
    },
}

TOOL_O365_TEAMS_READ_CHANNEL: dict = {
    "type": "function",
    "function": {
        "name": "o365_teams_read_channel",
        "description": "Read messages from a Microsoft Teams channel.",
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "team_id": {
                    "type": "string",
                    "description": "Team ID.",
                },
                "channel_id": {
                    "type": "string",
                    "description": "Channel ID (from o365_teams_list_channels).",
                },
                "top": {
                    "type": "integer",
                    "description": "Number of messages to return (default 20).",
                    "default": 20,
                },
            },
            "required": ["client_id", "team_id", "channel_id"],
        },
    },
}

TOOL_O365_TEAMS_SEND_CHANNEL_MESSAGE: dict = {
    "type": "function",
    "function": {
        "name": "o365_teams_send_channel_message",
        "description": "Send a message to a Microsoft Teams channel.",
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "team_id": {
                    "type": "string",
                    "description": "Team ID.",
                },
                "channel_id": {
                    "type": "string",
                    "description": "Channel ID.",
                },
                "content": {
                    "type": "string",
                    "description": "Message content.",
                },
            },
            "required": ["client_id", "team_id", "channel_id", "content"],
        },
    },
}

TOOL_O365_SESSION_STATUS: dict = {
    "type": "function",
    "function": {
        "name": "o365_session_status",
        "description": (
            "Check O365 session status for a client. "
            "Returns session state, token age, last refresh time, "
            "and noVNC URL if manual login is needed."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
            },
            "required": ["client_id"],
        },
    },
}

# -- Outlook Mail --

TOOL_O365_MAIL_LIST: dict = {
    "type": "function",
    "function": {
        "name": "o365_mail_list",
        "description": (
            "List recent emails from Outlook for a client. "
            "Returns subject, sender, date, and body preview."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "top": {
                    "type": "integer",
                    "description": "Number of emails to return (default 20).",
                    "default": 20,
                },
                "folder": {
                    "type": "string",
                    "description": "Mail folder: inbox, sentitems, drafts, etc. (default 'inbox').",
                    "default": "inbox",
                },
            },
            "required": ["client_id"],
        },
    },
}

TOOL_O365_MAIL_READ: dict = {
    "type": "function",
    "function": {
        "name": "o365_mail_read",
        "description": "Read a specific email message with full body content.",
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "message_id": {
                    "type": "string",
                    "description": "Message ID (from o365_mail_list).",
                },
            },
            "required": ["client_id", "message_id"],
        },
    },
}

TOOL_O365_MAIL_SEND: dict = {
    "type": "function",
    "function": {
        "name": "o365_mail_send",
        "description": "Send an email via Outlook.",
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "to": {
                    "type": "string",
                    "description": "Comma-separated recipient email addresses.",
                },
                "subject": {
                    "type": "string",
                    "description": "Email subject.",
                },
                "body": {
                    "type": "string",
                    "description": "Email body content.",
                },
                "cc": {
                    "type": "string",
                    "description": "Optional comma-separated CC addresses.",
                    "default": "",
                },
                "content_type": {
                    "type": "string",
                    "enum": ["text", "html"],
                    "description": "Content type (default 'text').",
                    "default": "text",
                },
            },
            "required": ["client_id", "to", "subject", "body"],
        },
    },
}

# -- Calendar --

TOOL_O365_CALENDAR_EVENTS: dict = {
    "type": "function",
    "function": {
        "name": "o365_calendar_events",
        "description": (
            "List calendar events for a client. "
            "If start/end date-time are provided, uses calendarView for that range. "
            "Otherwise returns upcoming events."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "top": {
                    "type": "integer",
                    "description": "Number of events to return (default 20).",
                    "default": 20,
                },
                "start_date_time": {
                    "type": "string",
                    "description": "ISO 8601 start (e.g. '2026-03-12T00:00:00Z'). Empty for upcoming.",
                    "default": "",
                },
                "end_date_time": {
                    "type": "string",
                    "description": "ISO 8601 end (e.g. '2026-03-19T23:59:59Z'). Empty for upcoming.",
                    "default": "",
                },
            },
            "required": ["client_id"],
        },
    },
}

TOOL_O365_CALENDAR_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "o365_calendar_create",
        "description": "Create a calendar event in Outlook.",
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "subject": {
                    "type": "string",
                    "description": "Event subject/title.",
                },
                "start_date_time": {
                    "type": "string",
                    "description": "Start time in ISO 8601 (e.g. '2026-03-15T10:00:00').",
                },
                "start_time_zone": {
                    "type": "string",
                    "description": "IANA timezone (e.g. 'Europe/Prague').",
                },
                "end_date_time": {
                    "type": "string",
                    "description": "End time in ISO 8601.",
                },
                "end_time_zone": {
                    "type": "string",
                    "description": "IANA timezone.",
                },
                "location": {
                    "type": "string",
                    "description": "Optional location name.",
                    "default": "",
                },
                "body": {
                    "type": "string",
                    "description": "Optional event description.",
                    "default": "",
                },
                "attendees": {
                    "type": "string",
                    "description": "Optional comma-separated attendee emails.",
                    "default": "",
                },
                "is_online_meeting": {
                    "type": "boolean",
                    "description": "Create as Teams meeting (default false).",
                    "default": False,
                },
            },
            "required": [
                "client_id", "subject",
                "start_date_time", "start_time_zone",
                "end_date_time", "end_time_zone",
            ],
        },
    },
}

# -- OneDrive / SharePoint --

TOOL_O365_FILES_LIST: dict = {
    "type": "function",
    "function": {
        "name": "o365_files_list",
        "description": "List files and folders in OneDrive.",
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "path": {
                    "type": "string",
                    "description": "Folder path relative to root (e.g. 'Documents/Reports'), or 'root'.",
                    "default": "root",
                },
                "top": {
                    "type": "integer",
                    "description": "Max items to return (default 50).",
                    "default": 50,
                },
            },
            "required": ["client_id"],
        },
    },
}

TOOL_O365_FILES_DOWNLOAD: dict = {
    "type": "function",
    "function": {
        "name": "o365_files_download",
        "description": (
            "Get download info for a OneDrive file. "
            "Returns metadata including download URL."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "item_id": {
                    "type": "string",
                    "description": "Drive item ID (from o365_files_list).",
                },
            },
            "required": ["client_id", "item_id"],
        },
    },
}

TOOL_O365_FILES_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "o365_files_search",
        "description": "Search for files in OneDrive by name or content.",
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "query": {
                    "type": "string",
                    "description": "Search query (searches file names and content).",
                },
                "top": {
                    "type": "integer",
                    "description": "Max results (default 25).",
                    "default": 25,
                },
            },
            "required": ["client_id", "query"],
        },
    },
}

# -- Tool groups --

O365_TEAMS_TOOLS: list[dict] = [
    TOOL_O365_TEAMS_LIST_CHATS,
    TOOL_O365_TEAMS_READ_CHAT,
    TOOL_O365_TEAMS_SEND_MESSAGE,
    TOOL_O365_TEAMS_LIST_TEAMS,
    TOOL_O365_TEAMS_LIST_CHANNELS,
    TOOL_O365_TEAMS_READ_CHANNEL,
    TOOL_O365_TEAMS_SEND_CHANNEL_MESSAGE,
    TOOL_O365_SESSION_STATUS,
]

O365_MAIL_TOOLS: list[dict] = [
    TOOL_O365_MAIL_LIST,
    TOOL_O365_MAIL_READ,
    TOOL_O365_MAIL_SEND,
]

O365_CALENDAR_TOOLS: list[dict] = [
    TOOL_O365_CALENDAR_EVENTS,
    TOOL_O365_CALENDAR_CREATE,
]

O365_FILES_TOOLS: list[dict] = [
    TOOL_O365_FILES_LIST,
    TOOL_O365_FILES_DOWNLOAD,
    TOOL_O365_FILES_SEARCH,
]

O365_ALL_TOOLS: list[dict] = (
    O365_TEAMS_TOOLS + O365_MAIL_TOOLS + O365_CALENDAR_TOOLS + O365_FILES_TOOLS
)

# CommunicationAgent — KB + Teams (chats, channels, messages)
COMMUNICATION_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_WEB_SEARCH] + O365_TEAMS_TOOLS

# EmailAgent — KB + Outlook mail
EMAIL_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH] + O365_MAIL_TOOLS

# CalendarAgent — KB + Calendar
CALENDAR_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH] + O365_CALENDAR_TOOLS

# AdministrativeAgent — web search + KB + O365 (full access for admin tasks)
ADMINISTRATIVE_AGENT_TOOLS: list[dict] = [TOOL_WEB_SEARCH, TOOL_KB_SEARCH] + O365_ALL_TOOLS

# LegalAgent — KB search + web search
LEGAL_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_WEB_SEARCH]

# FinancialAgent — KB search
FINANCIAL_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH]

# PersonalAgent — web + KB + O365 (personal assistant needs full O365)
PERSONAL_AGENT_TOOLS: list[dict] = [TOOL_WEB_SEARCH, TOOL_KB_SEARCH] + O365_ALL_TOOLS


# ============================================================
# Task Queue tools (for graph agent PLANNER — cross-project priority)
# ============================================================

TOOL_TASK_QUEUE_INSPECT: dict = {
    "type": "function",
    "function": {
        "name": "task_queue_inspect",
        "description": (
            "Inspect the background task queue across all clients and projects. "
            "Returns queued tasks ordered by priority (highest first). "
            "Use this to understand what work is pending, identify dependencies, "
            "and decide priority scores for new tasks."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Optional: filter by client ID. Omit to see all clients.",
                },
                "limit": {
                    "type": "integer",
                    "description": "Maximum number of tasks to return (default 20).",
                    "default": 20,
                },
            },
            "required": [],
        },
    },
}

TOOL_TASK_QUEUE_SET_PRIORITY: dict = {
    "type": "function",
    "function": {
        "name": "task_queue_set_priority",
        "description": (
            "Set the priority score (0–100) for a background task. "
            "Higher score = processed sooner. Default is 50. "
            "Use after inspecting the queue to optimize execution order "
            "based on dependencies, urgency, and cross-project impact."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "task_id": {
                    "type": "string",
                    "description": "The ID of the task to reprioritize.",
                },
                "priority_score": {
                    "type": "integer",
                    "description": "Priority score 0–100. Higher = more urgent. Default 50.",
                    "minimum": 0,
                    "maximum": 100,
                },
            },
            "required": ["task_id", "priority_score"],
        },
    },
}

QUEUE_TOOLS: list[dict] = [TOOL_TASK_QUEUE_INSPECT, TOOL_TASK_QUEUE_SET_PRIORITY]


# ---------------------------------------------------------------------------
# MongoDB self-management tools
# ---------------------------------------------------------------------------

TOOL_MONGO_LIST_COLLECTIONS: dict = {
    "type": "function",
    "function": {
        "name": "mongo_list_collections",
        "description": "Seznam všech MongoDB kolekcí v Jervis databázi.",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
}

TOOL_MONGO_GET_DOCUMENT: dict = {
    "type": "function",
    "function": {
        "name": "mongo_get_document",
        "description": (
            "Načti dokument(y) z MongoDB kolekce. "
            "Vrací JSON. Limit default 10."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "collection": {
                    "type": "string",
                    "description": "Název kolekce (e.g. 'clients', 'projects', 'cloud_model_policies').",
                },
                "filter": {
                    "type": "object",
                    "description": "MongoDB filter (e.g. {\"_id\": \"...\"}, {\"name\": \"Moneta\"}).",
                },
                "limit": {
                    "type": "integer",
                    "description": "Max počet výsledků (default 10).",
                    "default": 10,
                },
            },
            "required": ["collection"],
        },
    },
}

TOOL_MONGO_UPDATE_DOCUMENT: dict = {
    "type": "function",
    "function": {
        "name": "mongo_update_document",
        "description": (
            "Zapiš/aktualizuj dokument v MongoDB. "
            "Posílej CELÝ JSON dokumentu (upsert = nahradí nebo vytvoří). "
            "POZOR: Po zápisu se automaticky invaliduje cache v Kotlin serveru."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "collection": {
                    "type": "string",
                    "description": "Název kolekce.",
                },
                "filter": {
                    "type": "object",
                    "description": "MongoDB filter pro identifikaci dokumentu.",
                },
                "update": {
                    "type": "object",
                    "description": "MongoDB update operace (e.g. {\"$set\": {\"field\": \"value\"}}).",
                },
                "upsert": {
                    "type": "boolean",
                    "description": "Vytvořit pokud neexistuje (default false).",
                },
            },
            "required": ["collection", "filter", "update"],
        },
    },
}

MONGO_TOOLS: list[dict] = [
    TOOL_MONGO_LIST_COLLECTIONS,
    TOOL_MONGO_GET_DOCUMENT,
    TOOL_MONGO_UPDATE_DOCUMENT,
]

# Backward compatibility alias
LEGACY_AGENT_TOOLS: list[dict] = ALL_AGENT_TOOLS
