"""Chat-specific tool definitions for foreground chat.

These tools are available ONLY in the foreground chat agentic loop.
They extend the base tools (kb_search, web_search, brain_*, memory_*, store_knowledge)
with chat-specific capabilities like task management and meeting classification.
"""

from __future__ import annotations

from app.tools.definitions import (
    TOOL_WEB_SEARCH,
    TOOL_KB_SEARCH,
    TOOL_STORE_KNOWLEDGE,
    TOOL_MEMORY_STORE,
    TOOL_MEMORY_RECALL,
    TOOL_LIST_AFFAIRS,
    TOOL_CODE_SEARCH,
    TOOL_GET_KB_STATS,
    TOOL_GET_INDEXED_ITEMS,
    BRAIN_TOOLS,
)


# ---------------------------------------------------------------------------
# Chat-specific tool definitions
# ---------------------------------------------------------------------------

TOOL_CREATE_BACKGROUND_TASK: dict = {
    "type": "function",
    "function": {
        "name": "create_background_task",
        "description": (
            "Vytvoř background task (úkol na pozadí). Použij když user zmíní něco co není urgentní. "
            "Task bude zpracován orchestrátorem dle priorit."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Název úkolu (stručný, česky).",
                },
                "description": {
                    "type": "string",
                    "description": "Podrobný popis co se má udělat.",
                },
                "client_id": {
                    "type": "string",
                    "description": "Client ID (z kontextu nebo se zeptej).",
                },
                "project_id": {
                    "type": "string",
                    "description": "Project ID (volitelné).",
                },
                "priority": {
                    "type": "string",
                    "enum": ["high", "medium", "low"],
                    "description": "Priorita úkolu (default: medium).",
                },
            },
            "required": ["title", "description"],
        },
    },
}

TOOL_DISPATCH_CODING_AGENT: dict = {
    "type": "function",
    "function": {
        "name": "dispatch_coding_agent",
        "description": (
            "Pošli coding úkol na Claude Agenta. Default: background (asynchronně). "
            "Agent poběží asynchronně, výsledek přijde jako notifikace."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "task_description": {
                    "type": "string",
                    "description": "Co má agent udělat.",
                },
                "client_id": {
                    "type": "string",
                    "description": "Client ID (povinný pro coding).",
                },
                "project_id": {
                    "type": "string",
                    "description": "Project ID (povinný pro coding).",
                },
            },
            "required": ["task_description", "client_id", "project_id"],
        },
    },
}

TOOL_SEARCH_TASKS: dict = {
    "type": "function",
    "function": {
        "name": "search_tasks",
        "description": (
            "Hledej úkoly dle textu. Najde VŠECHNY úkoly (running, done, failed, user_task). "
            "Použij i pro hledání čekajících user_tasks."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Hledaný text (téma, klíčová slova).",
                },
                "state": {
                    "type": "string",
                    "enum": ["all", "DONE", "ERROR", "USER_TASK", "PYTHON_ORCHESTRATING", "READY_FOR_GPU"],
                    "description": "Filtr dle stavu (default: all).",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximální počet výsledků (default 5).",
                    "default": 5,
                },
            },
            "required": ["query"],
        },
    },
}

TOOL_GET_TASK_STATUS: dict = {
    "type": "function",
    "function": {
        "name": "get_task_status",
        "description": "Zjisti stav konkrétního úkolu — stav, obsah, chybová zpráva.",
        "parameters": {
            "type": "object",
            "properties": {
                "task_id": {
                    "type": "string",
                    "description": "TaskDocument ID.",
                },
            },
            "required": ["task_id"],
        },
    },
}

TOOL_LIST_RECENT_TASKS: dict = {
    "type": "function",
    "function": {
        "name": "list_recent_tasks",
        "description": (
            "Seznam nedávných úkolů se stavem a výsledkem. "
            "Použij pro 'co jsi udělal dneska', 'stav úkolů tento týden'."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Maximální počet výsledků (default 10).",
                    "default": 10,
                },
                "state": {
                    "type": "string",
                    "enum": ["all", "DONE", "ERROR", "USER_TASK", "PYTHON_ORCHESTRATING"],
                    "description": "Filtr dle stavu (default: all).",
                },
                "since": {
                    "type": "string",
                    "enum": ["today", "this_week", "this_month"],
                    "description": "Časové období (default: today).",
                },
                "client_id": {
                    "type": "string",
                    "description": "Filtr dle klienta (volitelné).",
                },
            },
            "required": [],
        },
    },
}

TOOL_RESPOND_TO_USER_TASK: dict = {
    "type": "function",
    "function": {
        "name": "respond_to_user_task",
        "description": (
            "Odpověz na čekající user_task. Doplní odpověď do TaskDocument, "
            "změní stav na background, orchestrátor pokračuje kde přestal."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "task_id": {
                    "type": "string",
                    "description": "TaskDocument._id.",
                },
                "response": {
                    "type": "string",
                    "description": "Odpověď od usera.",
                },
            },
            "required": ["task_id", "response"],
        },
    },
}

TOOL_CLASSIFY_MEETING: dict = {
    "type": "function",
    "function": {
        "name": "classify_meeting",
        "description": "Přiřaď neklasifikovanou nahrávku ke klientovi/projektu.",
        "parameters": {
            "type": "object",
            "properties": {
                "meeting_id": {
                    "type": "string",
                    "description": "Meeting ID.",
                },
                "client_id": {
                    "type": "string",
                    "description": "Client ID pro přiřazení.",
                },
                "project_id": {
                    "type": "string",
                    "description": "Project ID (volitelné).",
                },
                "title": {
                    "type": "string",
                    "description": "Název nahrávky.",
                },
            },
            "required": ["meeting_id", "client_id"],
        },
    },
}

TOOL_SWITCH_CONTEXT: dict = {
    "type": "function",
    "function": {
        "name": "switch_context",
        "description": (
            "Přepni aktivní klient/projekt v UI dropdownu. Použij když user řekne "
            "'přepni se na X', 'otevři projekt Y', nebo když potřebuješ pracovat s jiným klientem/projektem. "
            "Stačí zadat jméno — tool si sám dohledá ID."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client": {
                    "type": "string",
                    "description": "Jméno klienta (např. 'Moneta', 'nUFO'). Nemusíš znát ID.",
                },
                "project": {
                    "type": "string",
                    "description": "Jméno projektu (volitelné, např. 'BMS-FX', 'Jervis'). Pokud neuvedeš, přepne jen klienta.",
                },
            },
            "required": ["client"],
        },
    },
}

TOOL_LIST_UNCLASSIFIED_MEETINGS: dict = {
    "type": "function",
    "function": {
        "name": "list_unclassified_meetings",
        "description": "Vrať seznam neklasifikovaných ad-hoc nahrávek.",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
}


# ---------------------------------------------------------------------------
# Combined tool lists for chat handler
# ---------------------------------------------------------------------------

CHAT_SPECIFIC_TOOLS: list[dict] = [
    TOOL_CREATE_BACKGROUND_TASK,
    TOOL_DISPATCH_CODING_AGENT,
    TOOL_SEARCH_TASKS,
    TOOL_GET_TASK_STATUS,
    TOOL_LIST_RECENT_TASKS,
    TOOL_RESPOND_TO_USER_TASK,
    TOOL_CLASSIFY_MEETING,
    TOOL_LIST_UNCLASSIFIED_MEETINGS,
    TOOL_SWITCH_CONTEXT,
]

# All tools available in foreground chat = base research + brain + memory + chat-specific
CHAT_TOOLS: list[dict] = [
    TOOL_KB_SEARCH,
    TOOL_WEB_SEARCH,
    TOOL_CODE_SEARCH,
    TOOL_STORE_KNOWLEDGE,
    TOOL_MEMORY_STORE,
    TOOL_MEMORY_RECALL,
    TOOL_LIST_AFFAIRS,
    TOOL_GET_KB_STATS,
    TOOL_GET_INDEXED_ITEMS,
    *BRAIN_TOOLS,
    *CHAT_SPECIFIC_TOOLS,
]
