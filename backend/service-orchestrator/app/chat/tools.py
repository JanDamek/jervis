"""Chat-specific tool definitions for foreground chat.

These tools are available ONLY in the foreground chat agentic loop.
They extend the base tools (kb_search, web_search, memory_*, store_knowledge)
with chat-specific capabilities like task management and meeting classification.

Tool categories enable intent-based filtering (see intent.py):
- CORE: always available (4 tools)
- RESEARCH: KB introspection (3 tools)
- TASK_MGMT: task lifecycle + meetings (9 tools)

Tool tier system is available via app.unified.tool_sets for future unification.
"""

from __future__ import annotations

from enum import Enum

from app.tools.definitions import (
    TOOL_WEB_SEARCH,
    TOOL_WEB_FETCH,
    TOOL_KB_SEARCH,
    TOOL_KB_DELETE,
    TOOL_STORE_KNOWLEDGE,
    TOOL_MEMORY_STORE,
    TOOL_MEMORY_RECALL,
    TOOL_LIST_AFFAIRS,
    TOOL_GET_KB_STATS,
    TOOL_GET_INDEXED_ITEMS,
)

# Tier system available for future unified handler
from app.unified.tool_sets import ToolTier, get_tools, get_tool_names  # noqa: F401


# ---------------------------------------------------------------------------
# Chat-specific tool definitions
# ---------------------------------------------------------------------------

TOOL_CREATE_BACKGROUND_TASK: dict = {
    "type": "function",
    "function": {
        "name": "create_background_task",
        "description": (
            "Vytvoř background task (úkol na pozadí). Použij když user zmíní něco co není urgentní. "
            "Task bude zpracován orchestrátorem dle priorit. "
            "DŮLEŽITÉ: Nastav is_project_task=true POUZE pokud úkol přímo souvisí s konkrétním projektem "
            "(oprava bugu, coding task). Pro osobní připomínky, upozornění, remindery NENASTAVUJ project_id "
            "a nech is_project_task=false."
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
                    "description": "Project ID — POUZE pro projektově-specifické úkoly (bugfix, coding). Pro remindery/alerty NENASTAVUJ.",
                },
                "is_project_task": {
                    "type": "boolean",
                    "description": "true = úkol souvisí s konkrétním projektem (coding, bugfix). false = osobní/obecný úkol (reminder, alert reakce).",
                    "default": False,
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
            "Pošli coding úkol na coding agenta. Default: background (asynchronně). "
            "Agent poběží asynchronně, výsledek přijde jako notifikace. "
            "Coding agent: Claude CLI (default), Kilo (alternativa). "
            "Auto = Claude pro vše."
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
                "agent_preference": {
                    "type": "string",
                    "enum": ["auto", "claude", "kilo"],
                    "description": "Preferred coding agent. 'auto' = Claude CLI (default). 'kilo' = Kilo Code.",
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
                    "enum": ["all", "DONE", "ERROR", "USER_TASK", "PROCESSING", "QUEUED"],
                    "description": "Filtr dle stavu (default: all).",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum results (default 10).",
                    "default": 10,
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
                    "enum": ["all", "DONE", "ERROR", "USER_TASK", "PROCESSING"],
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

TOOL_DISMISS_USER_TASKS: dict = {
    "type": "function",
    "function": {
        "name": "dismiss_user_tasks",
        "description": (
            "Ignoruj (dismiss) čekající user_task(y). Posune je do stavu DONE "
            "bez zpracování. Data zůstanou zachována, jen zmizí z fronty. "
            "Použij když user řekne 'ignoruj', 'zahoď', 'nepotřebuji' u user_task."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "task_ids": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Seznam task ID k ignorování.",
                },
            },
            "required": ["task_ids"],
        },
    },
}

TOOL_RETRY_FAILED_TASK: dict = {
    "type": "function",
    "function": {
        "name": "retry_failed_task",
        "description": (
            "Znovu spusť selhávající úkol (ve stavu ERROR). "
            "Resetuje stav na QUEUED — orchestrátor ho znovu vyzvedne. "
            "Použij když user chce opakovat zpracování úkolu který skončil chybou."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "task_id": {
                    "type": "string",
                    "description": "TaskDocument ID úkolu k opakování.",
                },
            },
            "required": ["task_id"],
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
            "Přepni aktivní klient/projekt v UI dropdownu. "
            "POUZE když user EXPLICITNĚ řekne 'přepni se na X', 'otevři projekt Y'. "
            "NEVOLEJ pro zjištění informací — ty máš v kontextu. "
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

TOOL_GET_MEETING_TRANSCRIPT: dict = {
    "type": "function",
    "function": {
        "name": "get_meeting_transcript",
        "description": (
            "Přečti přepis (transcript) schůzky/nahrávky. "
            "Vrátí opravený přepis (pokud existuje), jinak surový. "
            "Použij pro analýzu obsahu meetingu, shrnutí, hledání konkrétních informací."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "meeting_id": {
                    "type": "string",
                    "description": "ID meetingu (z list_meetings nebo KB).",
                },
            },
            "required": ["meeting_id"],
        },
    },
}

TOOL_LIST_MEETINGS: dict = {
    "type": "function",
    "function": {
        "name": "list_meetings",
        "description": (
            "Zobraz seznam meetingů/nahrávek. Volitelně filtruj podle klienta, projektu nebo stavu."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Filtruj podle klienta (volitelné).",
                },
                "project_id": {
                    "type": "string",
                    "description": "Filtruj podle projektu (volitelné).",
                },
                "state": {
                    "type": "string",
                    "enum": ["TRANSCRIBED", "INDEXED", "CORRECTED", "FAILED"],
                    "description": "Filtruj podle stavu (volitelné).",
                },
                "limit": {
                    "type": "integer",
                    "description": "Max počet výsledků (default 20).",
                },
            },
        },
    },
}


# ---------------------------------------------------------------------------
# Guidelines tools
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Filtering rules tools (EPIC 10)
# ---------------------------------------------------------------------------

TOOL_SET_FILTER_RULE: dict = {
    "type": "function",
    "function": {
        "name": "set_filter_rule",
        "description": (
            "Vytvoř filtrační pravidlo pro příchozí položky. "
            "Příklad: 'ignoruj emaily od noreply@' → "
            "set_filter_rule(source_type=EMAIL, condition_type=FROM_CONTAINS, "
            "condition_value='noreply@', action=IGNORE)"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "source_type": {
                    "type": "string",
                    "enum": ["EMAIL", "JIRA", "GIT", "WIKI", "CHAT", "ALL"],
                    "description": "Typ zdroje (EMAIL, JIRA, GIT, WIKI, CHAT, ALL).",
                },
                "condition_type": {
                    "type": "string",
                    "enum": ["SUBJECT_CONTAINS", "FROM_CONTAINS", "BODY_CONTAINS", "LABEL_EQUALS", "REGEX_MATCH"],
                    "description": "Typ podmínky.",
                },
                "condition_value": {
                    "type": "string",
                    "description": "Hodnota podmínky (text, regex).",
                },
                "action": {
                    "type": "string",
                    "enum": ["IGNORE", "LOW_PRIORITY", "NORMAL", "HIGH_PRIORITY", "URGENT"],
                    "description": "Akce při shody (default: IGNORE).",
                },
                "description": {
                    "type": "string",
                    "description": "Popis pravidla (volitelné).",
                },
            },
            "required": ["source_type", "condition_type", "condition_value"],
        },
    },
}

TOOL_LIST_FILTER_RULES: dict = {
    "type": "function",
    "function": {
        "name": "list_filter_rules",
        "description": "Zobraz aktivní filtrační pravidla.",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
}

TOOL_REMOVE_FILTER_RULE: dict = {
    "type": "function",
    "function": {
        "name": "remove_filter_rule",
        "description": "Odstraň filtrační pravidlo dle ID.",
        "parameters": {
            "type": "object",
            "properties": {
                "rule_id": {
                    "type": "string",
                    "description": "ID pravidla (z list_filter_rules).",
                },
            },
            "required": ["rule_id"],
        },
    },
}


# ---------------------------------------------------------------------------
# Action memory tools (EPIC 9-S4)
# ---------------------------------------------------------------------------

TOOL_CREATE_THINKING_GRAPH: dict = {
    "type": "function",
    "function": {
        "name": "create_thinking_graph",
        "description": (
            "Vytvoř nový myšlenkový graf pro plánování složitějšího úkolu (>2 kroky). "
            "Graf se zobrazí vizuálně v panelu vedle chatu. "
            "Po vytvoření přidávej kroky přes add_graph_vertex."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Stručný název celého záměru (česky).",
                },
                "client_id": {
                    "type": "string",
                    "description": "Client ID (volitelné — z kontextu).",
                },
                "project_id": {
                    "type": "string",
                    "description": "Project ID (volitelné).",
                },
            },
            "required": ["title"],
        },
    },
}

TOOL_ADD_GRAPH_VERTEX: dict = {
    "type": "function",
    "function": {
        "name": "add_graph_vertex",
        "description": (
            "Přidej krok do myšlenkového grafu. Každý krok má typ — "
            "investigator (průzkum), executor (realizace), validator (testy/ověření), "
            "reviewer (review), planner (dekompozice), setup (příprava), synthesis (spojení výsledků)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Stručný název kroku (česky).",
                },
                "description": {
                    "type": "string",
                    "description": "Co přesně se má v tomto kroku udělat.",
                },
                "vertex_type": {
                    "type": "string",
                    "enum": ["investigator", "executor", "validator", "reviewer", "planner", "setup", "synthesis"],
                    "description": "Typ kroku.",
                },
                "depends_on": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Názvy kroků na kterých tento závisí (volitelné).",
                },
            },
            "required": ["title", "description", "vertex_type"],
        },
    },
}

TOOL_UPDATE_GRAPH_VERTEX: dict = {
    "type": "function",
    "function": {
        "name": "update_graph_vertex",
        "description": "Uprav existující krok v myšlenkovém grafu.",
        "parameters": {
            "type": "object",
            "properties": {
                "vertex_id": {
                    "type": "string",
                    "description": "ID vertexu k úpravě.",
                },
                "title": {"type": "string", "description": "Nový název (volitelné)."},
                "description": {"type": "string", "description": "Nový popis (volitelné)."},
                "vertex_type": {
                    "type": "string",
                    "enum": ["investigator", "executor", "validator", "reviewer", "planner", "setup", "synthesis"],
                    "description": "Nový typ (volitelné).",
                },
            },
            "required": ["vertex_id"],
        },
    },
}

TOOL_REMOVE_GRAPH_VERTEX: dict = {
    "type": "function",
    "function": {
        "name": "remove_graph_vertex",
        "description": "Odeber krok z myšlenkového grafu.",
        "parameters": {
            "type": "object",
            "properties": {
                "vertex_id": {
                    "type": "string",
                    "description": "ID vertexu k odebrání.",
                },
            },
            "required": ["vertex_id"],
        },
    },
}

TOOL_DISPATCH_THINKING_GRAPH: dict = {
    "type": "function",
    "function": {
        "name": "dispatch_thinking_graph",
        "description": (
            "Finalizuj myšlenkový graf a spusť jeho realizaci na pozadí. "
            "Volej POUZE po explicitním souhlasu uživatele s grafem."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID (volitelné — z kontextu).",
                },
                "project_id": {
                    "type": "string",
                    "description": "Project ID (volitelné).",
                },
            },
        },
    },
}

TOOL_RUN_GRAPH_VERTEX: dict = {
    "type": "function",
    "function": {
        "name": "run_graph_vertex",
        "description": (
            "Spusť jeden krok myšlenkového grafu na pozadí (paralelně s chatem). "
            "Výsledek se vrátí do grafu automaticky. Použij pro investigátory, "
            "výzkum, analýzy — cokoliv co trvá déle a uživatel na to nečeká."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "vertex_id": {
                    "type": "string",
                    "description": "ID vertexu ke spuštění.",
                },
                "client_id": {
                    "type": "string",
                    "description": "Client ID (volitelné — z kontextu).",
                },
                "project_id": {
                    "type": "string",
                    "description": "Project ID (volitelné).",
                },
            },
            "required": ["vertex_id"],
        },
    },
}

TOOL_QUERY_ACTION_LOG: dict = {
    "type": "function",
    "function": {
        "name": "query_action_log",
        "description": (
            "Prohledej historii akcí — co jsem udělal pro uživatele. "
            "Použij pro 'co jsi dělal minulý týden', 'jaké code reviews jsi provedl', "
            "'ukaž mi poslední deploymenty'."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Co hledáš v akčním logu (volitelné).",
                },
                "action_type": {
                    "type": "string",
                    "enum": ["BACKGROUND_TASK", "CODING_DISPATCH", "CODE_REVIEW", "KB_STORE", "DEPLOYMENT"],
                    "description": "Filtr dle typu akce (volitelné).",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximální počet výsledků (default 10).",
                    "default": 10,
                },
            },
        },
    },
}


# ---------------------------------------------------------------------------
# Graph interaction tools (master graph / task sub-graphs)
# ---------------------------------------------------------------------------

TOOL_CHECK_TASK_GRAPH: dict = {
    "type": "function",
    "function": {
        "name": "check_task_graph",
        "description": (
            "Zjisti stav myšlenkového grafu (task graph) — vertexy, hrany, stav zpracování. "
            "Použij pro 'jak je na tom ten úkol', 'stav background tasku', 'co se děje s X'."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "task_id": {
                    "type": "string",
                    "description": "TaskDocument ID jehož graf chceš zkontrolovat.",
                },
            },
            "required": ["task_id"],
        },
    },
}

TOOL_ANSWER_BLOCKED_VERTEX: dict = {
    "type": "function",
    "function": {
        "name": "answer_blocked_vertex",
        "description": (
            "Odpověz na čekající otázku v myšlenkovém grafu (ASK_USER vertex). "
            "Graf pokračuje tam kde přestal poté co dostane odpověď. "
            "Použij když uživatel odpovídá na otázku z background tasku."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "task_id": {
                    "type": "string",
                    "description": "Task ID nebo 'master' pro master graf.",
                },
                "vertex_id": {
                    "type": "string",
                    "description": "ID vertexu (ASK_USER) na který odpovídáš.",
                },
                "answer": {
                    "type": "string",
                    "description": "Odpověď od uživatele.",
                },
            },
            "required": ["task_id", "vertex_id", "answer"],
        },
    },
}


# ---------------------------------------------------------------------------
# Guidelines tools
# ---------------------------------------------------------------------------

TOOL_GET_GUIDELINES: dict = {
    "type": "function",
    "function": {
        "name": "get_guidelines",
        "description": (
            "Získej pravidla a směrnice (guidelines) pro daný scope. "
            "Vrací sloučená pravidla (global → client → project). "
            "Kategorie: coding, git, review, communication, approval, general."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID (volitelné, pro client/project scope).",
                },
                "project_id": {
                    "type": "string",
                    "description": "Project ID (volitelné, pro project scope).",
                },
            },
        },
    },
}

TOOL_UPDATE_GUIDELINE: dict = {
    "type": "function",
    "function": {
        "name": "update_guideline",
        "description": (
            "Aktualizuj pravidla pro daný scope a kategorii. "
            "Scope: GLOBAL, CLIENT, PROJECT. "
            "Kategorie: coding, git, review, communication, approval, general. "
            "Pro coding kategorie: principles (list stringů = coding principy), "
            "maxFileLines, maxFunctionLines, forbiddenPatterns, requiredPatterns, namingConventions. "
            "Příklad: 'chci aby kód byl idiomatický Kotlin' → "
            "update_guideline(scope=GLOBAL, category=coding, "
            "rules={principles: ['Idiomatic Kotlin — NEVER Java-style code in Kotlin']})"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "scope": {
                    "type": "string",
                    "enum": ["GLOBAL", "CLIENT", "PROJECT"],
                    "description": "Scope pravidla.",
                },
                "category": {
                    "type": "string",
                    "enum": ["coding", "git", "review", "communication", "approval", "general"],
                    "description": "Kategorie pravidla.",
                },
                "rules": {
                    "type": "object",
                    "description": "JSON s pravidly pro danou kategorii.",
                },
                "client_id": {
                    "type": "string",
                    "description": "Client ID (povinný pro CLIENT/PROJECT scope).",
                },
                "project_id": {
                    "type": "string",
                    "description": "Project ID (povinný pro PROJECT scope).",
                },
            },
            "required": ["scope", "category", "rules"],
        },
    },
}


# ---------------------------------------------------------------------------
# Financial tools
# ---------------------------------------------------------------------------

TOOL_FINANCE_SUMMARY: dict = {
    "type": "function",
    "function": {
        "name": "finance_summary",
        "description": (
            "Finanční přehled pro klienta — příjmy, výdaje, nezaplacené faktury, po splatnosti. "
            "Vrací celkový souhrn za dané období."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID. Pokud neuvedeno, použije aktuální kontext.",
                },
                "from_date": {
                    "type": "string",
                    "description": "Počátek období (YYYY-MM-DD). Volitelné.",
                },
                "to_date": {
                    "type": "string",
                    "description": "Konec období (YYYY-MM-DD). Volitelné.",
                },
            },
        },
    },
}

TOOL_LIST_INVOICES: dict = {
    "type": "function",
    "function": {
        "name": "list_invoices",
        "description": (
            "Seznam faktur a finančních záznamů. Lze filtrovat podle stavu (NEW, MATCHED, PAID, OVERDUE) "
            "nebo typu (INVOICE_IN, INVOICE_OUT, PAYMENT, EXPENSE)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "status": {
                    "type": "string",
                    "description": "Filtr podle stavu: NEW, MATCHED, PAID, OVERDUE, CANCELLED.",
                },
                "type": {
                    "type": "string",
                    "description": "Filtr podle typu: INVOICE_IN, INVOICE_OUT, PAYMENT, EXPENSE, RECEIPT.",
                },
            },
            "required": ["client_id"],
        },
    },
}

TOOL_RECORD_PAYMENT: dict = {
    "type": "function",
    "function": {
        "name": "record_payment",
        "description": (
            "Zaznamenej platbu. Jervis se pokusí automaticky spárovat s existující fakturou "
            "podle variabilního symbolu nebo částky + protistrany."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID.",
                },
                "amount": {
                    "type": "number",
                    "description": "Částka v CZK.",
                },
                "variable_symbol": {
                    "type": "string",
                    "description": "Variabilní symbol (pro párování s fakturou).",
                },
                "counterparty_name": {
                    "type": "string",
                    "description": "Název protistrany.",
                },
                "counterparty_account": {
                    "type": "string",
                    "description": "Číslo účtu protistrany.",
                },
                "payment_date": {
                    "type": "string",
                    "description": "Datum platby (YYYY-MM-DD). Default: dnes.",
                },
                "description": {
                    "type": "string",
                    "description": "Popis platby.",
                },
            },
            "required": ["client_id", "amount"],
        },
    },
}

TOOL_LIST_CONTRACTS: dict = {
    "type": "function",
    "function": {
        "name": "list_contracts",
        "description": (
            "Seznam smluv pro klienta — aktivní kontrakty, sazby, data. "
            "Pokud active_only=true, vrací pouze aktivní smlouvy."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "client_id": {
                    "type": "string",
                    "description": "Client ID. Pokud neuvedeno, vrací smlouvy všech klientů.",
                },
                "active_only": {
                    "type": "boolean",
                    "description": "Pouze aktivní smlouvy. Default: true.",
                },
            },
        },
    },
}


# ---------------------------------------------------------------------------
# Combined tool lists for chat handler
# ---------------------------------------------------------------------------

CHAT_SPECIFIC_TOOLS: list[dict] = [
    TOOL_CREATE_BACKGROUND_TASK,
    TOOL_CREATE_THINKING_GRAPH,
    TOOL_ADD_GRAPH_VERTEX,
    TOOL_UPDATE_GRAPH_VERTEX,
    TOOL_REMOVE_GRAPH_VERTEX,
    TOOL_DISPATCH_THINKING_GRAPH,
    TOOL_RUN_GRAPH_VERTEX,
    TOOL_DISPATCH_CODING_AGENT,
    TOOL_SEARCH_TASKS,
    TOOL_GET_TASK_STATUS,
    TOOL_LIST_RECENT_TASKS,
    TOOL_RESPOND_TO_USER_TASK,
    TOOL_DISMISS_USER_TASKS,
    TOOL_RETRY_FAILED_TASK,
    TOOL_CLASSIFY_MEETING,
    TOOL_LIST_UNCLASSIFIED_MEETINGS,
    TOOL_GET_MEETING_TRANSCRIPT,
    TOOL_LIST_MEETINGS,
    TOOL_SWITCH_CONTEXT,
    TOOL_GET_GUIDELINES,
    TOOL_UPDATE_GUIDELINE,
    TOOL_SET_FILTER_RULE,
    TOOL_LIST_FILTER_RULES,
    TOOL_REMOVE_FILTER_RULE,
    TOOL_QUERY_ACTION_LOG,
    TOOL_CHECK_TASK_GRAPH,
    TOOL_ANSWER_BLOCKED_VERTEX,
]

# All tools available in foreground chat = base research + memory + chat-specific
# Note: code_search, git workspace, and filesystem tools removed —
# those are delegated to coding agents via dispatch_coding_agent.
CHAT_TOOLS: list[dict] = [
    TOOL_KB_SEARCH,
    TOOL_KB_DELETE,
    TOOL_WEB_SEARCH,
    TOOL_WEB_FETCH,
    TOOL_STORE_KNOWLEDGE,
    TOOL_MEMORY_STORE,
    TOOL_MEMORY_RECALL,
    TOOL_LIST_AFFAIRS,
    TOOL_GET_KB_STATS,
    TOOL_GET_INDEXED_ITEMS,
    *CHAT_SPECIFIC_TOOLS,
]


# ---------------------------------------------------------------------------
# Two-tier tool system: INITIAL (small) + request_tools (expand on demand)
# ---------------------------------------------------------------------------

class ToolCategory(str, Enum):
    """Tool category for on-demand expansion via request_tools.

    CORE is always included. Other categories are loaded on demand
    when the model calls request_tools(category).
    """
    PLANNING = "planning"
    TASK_MGMT = "task_mgmt"
    MEETINGS = "meetings"
    MEMORY = "memory"
    FILTERING = "filtering"
    ADMIN = "admin"
    FINANCE = "finance"


# Meta-tool: model calls this to get additional tools
TOOL_REQUEST_TOOLS: dict = {
    "type": "function",
    "function": {
        "name": "request_tools",
        "description": (
            "Zažádej o další sadu nástrojů. Máš k dispozici základní nástroje "
            "(kb_search, web_search, web_fetch, store_knowledge, dispatch_coding_agent, "
            "create_background_task, respond_to_user_task). "
            "Pro pokročilé operace zavolej tento tool s kategorií:\n"
            "- planning: myšlenkový graf (create_thinking_graph, add/update/remove vertex, dispatch)\n"
            "- task_mgmt: správa úkolů (search_tasks, get_task_status, list_recent_tasks, retry_failed_task)\n"
            "- meetings: nahrávky a meetingy (classify_meeting, list_meetings, get_meeting_transcript)\n"
            "- memory: paměť a znalosti (memory_store, memory_recall, list_affairs, get_kb_stats, kb_delete)\n"
            "- filtering: filtrační pravidla (set_filter_rule, list_filter_rules, remove_filter_rule)\n"
            "- admin: pravidla a konfigurace (get_guidelines, update_guideline, switch_context, query_action_log)\n"
            "- finance: faktury, platby, smlouvy, finanční přehledy"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "category": {
                    "type": "string",
                    "enum": ["planning", "task_mgmt", "meetings", "memory", "filtering", "admin"],
                    "description": "Kategorie nástrojů k načtení.",
                },
            },
            "required": ["category"],
        },
    },
}


# Initial tools: small core set + request_tools meta-tool (10 tools total)
CHAT_INITIAL_TOOLS: list[dict] = [
    TOOL_KB_SEARCH,
    TOOL_WEB_SEARCH,
    TOOL_WEB_FETCH,
    TOOL_STORE_KNOWLEDGE,
    TOOL_DISPATCH_CODING_AGENT,
    TOOL_CREATE_BACKGROUND_TASK,
    TOOL_RESPOND_TO_USER_TASK,
    TOOL_CHECK_TASK_GRAPH,
    TOOL_ANSWER_BLOCKED_VERTEX,
    TOOL_REQUEST_TOOLS,
]


# Expandable categories: loaded on demand via request_tools
TOOL_CATEGORIES: dict[ToolCategory, list[dict]] = {
    ToolCategory.PLANNING: [
        TOOL_CREATE_THINKING_GRAPH,
        TOOL_ADD_GRAPH_VERTEX,
        TOOL_UPDATE_GRAPH_VERTEX,
        TOOL_REMOVE_GRAPH_VERTEX,
        TOOL_DISPATCH_THINKING_GRAPH,
        TOOL_RUN_GRAPH_VERTEX,
    ],
    ToolCategory.TASK_MGMT: [
        TOOL_SEARCH_TASKS,
        TOOL_GET_TASK_STATUS,
        TOOL_LIST_RECENT_TASKS,
        TOOL_RETRY_FAILED_TASK,
        TOOL_DISMISS_USER_TASKS,
    ],
    ToolCategory.MEETINGS: [
        TOOL_CLASSIFY_MEETING,
        TOOL_LIST_UNCLASSIFIED_MEETINGS,
        TOOL_GET_MEETING_TRANSCRIPT,
        TOOL_LIST_MEETINGS,
    ],
    ToolCategory.MEMORY: [
        TOOL_MEMORY_STORE,
        TOOL_MEMORY_RECALL,
        TOOL_LIST_AFFAIRS,
        TOOL_GET_KB_STATS,
        TOOL_GET_INDEXED_ITEMS,
        TOOL_KB_DELETE,
    ],
    ToolCategory.FILTERING: [
        TOOL_SET_FILTER_RULE,
        TOOL_LIST_FILTER_RULES,
        TOOL_REMOVE_FILTER_RULE,
    ],
    ToolCategory.ADMIN: [
        TOOL_SWITCH_CONTEXT,
        TOOL_GET_GUIDELINES,
        TOOL_UPDATE_GUIDELINE,
        TOOL_QUERY_ACTION_LOG,
    ],
    ToolCategory.FINANCE: [
        TOOL_FINANCE_SUMMARY,
        TOOL_LIST_INVOICES,
        TOOL_RECORD_PAYMENT,
        TOOL_LIST_CONTRACTS,
    ],
}


# Human-readable category descriptions for tool result
TOOL_CATEGORY_DESCRIPTIONS: dict[ToolCategory, str] = {
    ToolCategory.PLANNING: "Myšlenkový graf — plánování a dekompozice složitých úkolů",
    ToolCategory.TASK_MGMT: "Správa úkolů — hledání, stav, retry",
    ToolCategory.MEETINGS: "Meetingy a nahrávky — klasifikace, přepisy, seznam",
    ToolCategory.MEMORY: "Paměť a znalosti — ukládání, vyhledávání, statistiky KB",
    ToolCategory.FILTERING: "Filtrační pravidla — nastavení automatického zpracování",
    ToolCategory.ADMIN: "Administrace — pravidla, přepínání kontextu, akční log",
    ToolCategory.FINANCE: "Finance — faktury, platby, smlouvy, finanční přehledy",
}

# Domain mapping for drift detection (tool name → semantic domain)
TOOL_DOMAINS: dict[str, str] = {
    "kb_search": "search", "kb_delete": "memory", "web_search": "search", "web_fetch": "search",
    "request_tools": "search",
    "memory_recall": "search", "get_kb_stats": "search", "get_indexed_items": "search",
    "memory_store": "memory", "store_knowledge": "memory", "list_affairs": "memory",
    "create_background_task": "task",
    "create_thinking_graph": "task", "add_graph_vertex": "task",
    "update_graph_vertex": "task", "remove_graph_vertex": "task",
    "dispatch_thinking_graph": "task", "run_graph_vertex": "task",
    "dispatch_coding_agent": "task",
    "search_tasks": "task", "get_task_status": "task",
    "list_recent_tasks": "task", "respond_to_user_task": "task",
    "retry_failed_task": "task",
    "check_task_graph": "task", "answer_blocked_vertex": "task",
    "classify_meeting": "meeting", "list_unclassified_meetings": "meeting",
    "get_meeting_transcript": "meeting", "list_meetings": "meeting",
    "switch_context": "scope",
    "get_guidelines": "guidelines", "update_guideline": "guidelines",
    "set_filter_rule": "filtering", "list_filter_rules": "filtering",
    "remove_filter_rule": "filtering",
    "query_action_log": "memory",
    "finance_summary": "finance", "list_invoices": "finance",
    "record_payment": "finance", "list_contracts": "finance",
}

# Tool name → tool definition lookup (for intent router)
_TOOL_BY_NAME: dict[str, dict] = {
    tool["function"]["name"]: tool for tool in CHAT_TOOLS
}


def select_tools_by_names(tool_names: list[str]) -> list[dict]:
    """Select tool definitions by name from CHAT_TOOLS.

    Used by intent router to build a focused tool set per category.

    Args:
        tool_names: List of tool function names (e.g., ["kb_search", "web_search"]).

    Returns:
        List of tool definition dicts (no duplicates, preserves order).
    """
    tools = []
    seen = set()
    for name in tool_names:
        if name in _TOOL_BY_NAME and name not in seen:
            tools.append(_TOOL_BY_NAME[name])
            seen.add(name)
    return tools
