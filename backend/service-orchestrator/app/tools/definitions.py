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

TOOL_STORE_KNOWLEDGE: dict = {
    "type": "function",
    "function": {
        "name": "store_knowledge",
        "description": (
            "Store new knowledge or facts into the Knowledge Base. "
            "Use this when the user teaches you something new, provides definitions, "
            "explains concepts, or shares information that should be remembered for future reference. "
            "Examples: 'BMS is Brokerage Management System', 'project uses Python 3.11', "
            "'authentication uses JWT tokens', 'deployment is on AWS'."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "subject": {
                    "type": "string",
                    "description": "Brief subject/title of the knowledge being stored (e.g., 'BMS definition', 'Tech stack info').",
                },
                "content": {
                    "type": "string",
                    "description": "The actual knowledge/fact to store. Be specific and factual.",
                },
                "category": {
                    "type": "string",
                    "description": (
                        "Category of knowledge. Choose the most specific match:\n"
                        "- preference: coding style, tooling, workflow (e.g., 'preferuji Kotlin idiomaticky')\n"
                        "- domain: business domain, industry, location (e.g., 'jsme z Palkovic', 'BMS je...')\n"
                        "- team: people, roles, processes (e.g., 'Jan je tech lead', 'Scrum s 2-week sprinty')\n"
                        "- tech_stack: frameworks, libraries, patterns (e.g., 'Kotlin Multiplatform', 'MongoDB')\n"
                        "- personal: personal info about the user (e.g., 'jmenuju se Jan')\n"
                        "- general: anything that doesn't fit above categories"
                    ),
                    "enum": ["preference", "domain", "team", "tech_stack", "personal", "general"],
                    "default": "general",
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
            "Examples: 'Check BMS code when available', 'Verify architecture matches documentation in 1 week', "
            "'Follow up on incomplete feature request'."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Brief title for the task (e.g., 'Verify BMS code against definition').",
                },
                "description": {
                    "type": "string",
                    "description": "Detailed description of what needs to be done in the future.",
                },
                "reason": {
                    "type": "string",
                    "description": "Why this task is needed (e.g., 'User provided definition but code not yet indexed').",
                },
                "schedule": {
                    "type": "string",
                    "description": "When to do this: 'when_code_available', 'in_1_day', 'in_1_week', 'in_1_month', or 'manual'.",
                    "enum": ["when_code_available", "in_1_day", "in_1_week", "in_1_month", "manual"],
                    "default": "manual",
                },
            },
            "required": ["title", "description", "reason"],
        },
    },
}

ALL_RESPOND_TOOLS: list[dict] = [
    TOOL_WEB_SEARCH,
    TOOL_KB_SEARCH,
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
ALL_RESPOND_TOOLS_FULL: list[dict] = (
    ALL_RESPOND_TOOLS + GIT_WORKSPACE_TOOLS + FILESYSTEM_TOOLS + TERMINAL_TOOLS
)


# ============================================================
# Memory Agent tools (opt-in via use_memory_agent)
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
ALL_RESPOND_TOOLS_FULL_WITH_MEMORY: list[dict] = ALL_RESPOND_TOOLS_FULL + MEMORY_TOOLS


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

# IssueTrackerAgent — KB search for context
TRACKER_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_GET_KB_STATS]

# WikiAgent — KB search for context
WIKI_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_GET_KB_STATS]

# DocumentationAgent — KB + filesystem for doc generation
DOCUMENTATION_AGENT_TOOLS: list[dict] = [
    TOOL_KB_SEARCH, TOOL_CODE_SEARCH,
    TOOL_READ_FILE, TOOL_LIST_FILES, TOOL_FIND_FILES, TOOL_GREP_FILES,
    TOOL_GET_REPOSITORY_STRUCTURE,
]

# DevOpsAgent — no tools (uses k8s API via executor)
DEVOPS_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_GET_KB_STATS]

# ProjectManagementAgent — KB search
PROJECT_MANAGEMENT_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_GET_KB_STATS]

# SecurityAgent — Joern + KB
SECURITY_AGENT_TOOLS: list[dict] = [
    TOOL_JOERN_QUICK_SCAN, TOOL_KB_SEARCH, TOOL_CODE_SEARCH,
    TOOL_READ_FILE, TOOL_GREP_FILES,
]

# CommunicationAgent — KB search
COMMUNICATION_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_WEB_SEARCH]

# EmailAgent — no tools defined yet (uses Kotlin API via executor)
EMAIL_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH]

# CalendarAgent — no tools defined yet (uses Kotlin API via executor)
CALENDAR_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH]

# AdministrativeAgent — web search + KB
ADMINISTRATIVE_AGENT_TOOLS: list[dict] = [TOOL_WEB_SEARCH, TOOL_KB_SEARCH]

# LegalAgent — KB search + web search
LEGAL_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH, TOOL_WEB_SEARCH]

# FinancialAgent — KB search
FINANCIAL_AGENT_TOOLS: list[dict] = [TOOL_KB_SEARCH]

# PersonalAgent — web + KB
PERSONAL_AGENT_TOOLS: list[dict] = [TOOL_WEB_SEARCH, TOOL_KB_SEARCH]

# LearningAgent — web + KB + code search
LEARNING_AGENT_TOOLS: list[dict] = [TOOL_WEB_SEARCH, TOOL_KB_SEARCH, TOOL_CODE_SEARCH]

# All agent tools (full tool access)
ALL_AGENT_TOOLS: list[dict] = ALL_RESPOND_TOOLS_FULL

# Backward compatibility alias
LEGACY_AGENT_TOOLS: list[dict] = ALL_AGENT_TOOLS
