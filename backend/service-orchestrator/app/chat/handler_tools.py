"""Tool execution, parsing, and resolution for chat handler.

Responsibilities:
- Extract tool calls from LLM response (incl. Ollama JSON workaround)
- Describe tool calls for thinking events
- Execute tools (base + chat-specific via strategy map)
- Resolve switch_context (name → ID, ambiguity handling)
- Resolve client/project names from cached runtime context
"""
from __future__ import annotations

import json
import logging
import re
import uuid

from app.chat.system_prompt import RuntimeContext
from app.tools.executor import execute_tool

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Tool call extraction (Ollama JSON workaround)
# ---------------------------------------------------------------------------


class _ToolCall:
    """Lightweight tool call object for Ollama JSON workaround."""

    def __init__(self, tc_dict: dict):
        self.id = tc_dict.get("id", str(uuid.uuid4())[:8])
        self.type = tc_dict.get("type", "function")

        class Function:
            def __init__(self, f_dict):
                self.name = f_dict.get("name", "")
                self.arguments = json.dumps(f_dict.get("arguments", {}))
        self.function = Function(tc_dict.get("function", {}))


def extract_tool_calls(message) -> tuple[list, str | None]:
    """Extract tool calls from LLM response, including Ollama JSON workaround.

    Returns (tool_calls, remaining_text).

    Handles:
    1. Standard litellm tool_calls field
    2. Ollama JSON-in-content {"tool_calls": [...]}
    3. JSON embedded in markdown ```json blocks
    4. Pure text (no tools)
    """
    tool_calls = getattr(message, "tool_calls", None)
    if tool_calls:
        return tool_calls, message.content

    if not message.content:
        return [], None

    content = message.content.strip()

    # Pure JSON {"tool_calls": [...]}
    try:
        parsed = json.loads(content)
        if isinstance(parsed, dict) and "tool_calls" in parsed:
            logger.info("Chat: parsing tool_calls from JSON content (Ollama workaround)")
            calls = [_ToolCall(tc) for tc in parsed["tool_calls"]]
            logger.info("Chat: extracted %d tool calls from JSON", len(calls))
            return calls, None
    except (json.JSONDecodeError, KeyError, TypeError):
        pass

    # JSON in markdown ```json block
    md_match = re.search(r'```(?:json)?\s*(\{.*?"tool_calls".*?\})\s*```', content, re.DOTALL)
    if md_match:
        try:
            parsed = json.loads(md_match.group(1))
            remaining = content[:md_match.start()] + content[md_match.end():]
            remaining = remaining.strip() or None
            calls = [_ToolCall(tc) for tc in parsed["tool_calls"]]
            logger.info("Chat: extracted %d tool calls from markdown JSON block", len(calls))
            return calls, remaining
        except (json.JSONDecodeError, KeyError, TypeError):
            pass

    return [], content


# ---------------------------------------------------------------------------
# Tool call descriptions (for thinking events)
# ---------------------------------------------------------------------------


_TOOL_DESCRIPTIONS = {
    "kb_search": lambda a: f"Hledám v KB: {a.get('query', '')}",
    "web_search": lambda a: f"Hledám na webu: {a.get('query', '')}",
    "code_search": lambda a: f"Hledám v kódu: {a.get('query', '')}",
    "store_knowledge": lambda a: f"Ukládám znalost: {a.get('subject', '')}",
    "memory_store": lambda a: f"Zapamatuji si: {a.get('subject', '')}",
    "memory_recall": lambda a: f"Vzpomínám: {a.get('query', '')}",
    "list_affairs": lambda _: "Kontroluji aktivní témata",
    "get_kb_stats": lambda _: "Zjišťuji statistiky KB",
    "get_indexed_items": lambda _: "Kontroluji indexovaný obsah",
    "brain_create_issue": lambda a: f"Vytvářím issue: {a.get('summary', '')}",
    "brain_update_issue": lambda a: f"Aktualizuji issue: {a.get('issue_key', '')}",
    "brain_add_comment": lambda a: f"Přidávám komentář k: {a.get('issue_key', '')}",
    "brain_transition_issue": lambda a: f"Měním stav: {a.get('issue_key', '')} → {a.get('transition_name', '')}",
    "brain_search_issues": lambda a: f"Hledám v Jiře: {a.get('jql', '')}",
    "brain_create_page": lambda a: f"Vytvářím stránku: {a.get('title', '')}",
    "brain_update_page": lambda a: f"Aktualizuji stránku: {a.get('page_id', '')}",
    "brain_search_pages": lambda a: f"Hledám v Confluence: {a.get('query', '')}",
    "create_background_task": lambda a: f"Vytvářím úkol: {a.get('title', '')}",
    "dispatch_coding_agent": lambda _: "Odesílám coding task na agenta",
    "search_user_tasks": lambda a: f"Hledám úkoly: {a.get('query', '')}",
    "search_tasks": lambda a: f"Hledám úkoly: {a.get('query', '')}",
    "respond_to_user_task": lambda a: f"Odpovídám na úkol: {a.get('task_id', '')}",
    "get_task_status": lambda a: f"Kontroluji stav úkolu: {a.get('task_id', '')}",
    "list_recent_tasks": lambda _: "Kontroluji nedávné úkoly",
    "classify_meeting": lambda a: f"Klasifikuji nahrávku: {a.get('meeting_id', '')}",
    "list_unclassified_meetings": lambda _: "Kontroluji neklasifikované nahrávky",
    "switch_context": lambda a: f"Přepínám na: {a.get('client', '')} {a.get('project', '')}".strip(),
}


def describe_tool_call(name: str, args: dict) -> str:
    """Human-readable description of a tool call for thinking events."""
    fn = _TOOL_DESCRIPTIONS.get(name)
    if fn:
        return fn(args)
    return f"Zpracovávám: {name}"


# ---------------------------------------------------------------------------
# Tool execution
# ---------------------------------------------------------------------------

# Chat-specific tools dispatched via Kotlin internal API
_CHAT_SPECIFIC_TOOLS = {
    "create_background_task",
    "dispatch_coding_agent",
    "search_user_tasks",
    "search_tasks",
    "get_task_status",
    "list_recent_tasks",
    "respond_to_user_task",
    "classify_meeting",
    "list_unclassified_meetings",
}


async def execute_chat_tool(
    tool_name: str,
    arguments: dict,
    active_client_id: str | None,
    active_project_id: str | None,
) -> str:
    """Execute a tool call, routing between base tools and chat-specific tools."""
    if tool_name in _CHAT_SPECIFIC_TOOLS:
        return await _execute_chat_specific_tool(tool_name, arguments, active_client_id, active_project_id)

    return await execute_tool(
        tool_name=tool_name,
        arguments=arguments,
        client_id=active_client_id or "",
        project_id=active_project_id,
        processing_mode="FOREGROUND",
    )


# Strategy map: tool_name → handler coroutine
async def _handle_create_background_task(args, client_id, project_id, kotlin_client):
    effective_client_id = args.get("client_id") or client_id
    if not effective_client_id:
        return "Chyba: client_id je povinný pro vytvoření background tasku. Zeptej se uživatele na klienta."
    result = await kotlin_client.create_background_task(
        title=args["title"],
        description=args["description"],
        client_id=effective_client_id,
        project_id=args.get("project_id", project_id),
        priority=args.get("priority", "medium"),
    )
    return f"Background task created: {result}"


async def _handle_dispatch_coding_agent(args, client_id, project_id, kotlin_client):
    effective_client_id = args.get("client_id") or client_id
    effective_project_id = args.get("project_id") or project_id
    if not effective_client_id or not effective_project_id:
        return "Chyba: client_id a project_id jsou povinné pro dispatch coding agenta. Zeptej se uživatele."
    result = await kotlin_client.dispatch_coding_agent(
        task_description=args["task_description"],
        client_id=effective_client_id,
        project_id=effective_project_id,
    )
    return f"Coding agent dispatched: {result}"


async def _handle_search_tasks(args, _client_id, _project_id, kotlin_client):
    return await kotlin_client.search_tasks(
        query=args["query"],
        state=args.get("state"),
        max_results=args.get("max_results", 5),
    )


async def _handle_get_task_status(args, _client_id, _project_id, kotlin_client):
    return await kotlin_client.get_task_status(args["task_id"])


async def _handle_list_recent_tasks(args, _client_id, _project_id, kotlin_client):
    return await kotlin_client.list_recent_tasks(
        limit=args.get("limit", 10),
        state=args.get("state"),
        since=args.get("since", "today"),
        client_id=args.get("client_id"),
    )


async def _handle_respond_to_user_task(args, _client_id, _project_id, kotlin_client):
    result = await kotlin_client.respond_to_user_task(
        task_id=args["task_id"],
        response=args["response"],
    )
    return f"User task responded: {result}"


async def _handle_classify_meeting(args, _client_id, _project_id, kotlin_client):
    result = await kotlin_client.classify_meeting(
        meeting_id=args["meeting_id"],
        client_id=args["client_id"],
        project_id=args.get("project_id"),
        title=args.get("title"),
    )
    return f"Meeting classified: {result}"


async def _handle_list_unclassified_meetings(_args, _client_id, _project_id, kotlin_client):
    return await kotlin_client.list_unclassified_meetings()


_TOOL_HANDLER_MAP = {
    "create_background_task": _handle_create_background_task,
    "dispatch_coding_agent": _handle_dispatch_coding_agent,
    "search_user_tasks": _handle_search_tasks,
    "search_tasks": _handle_search_tasks,
    "get_task_status": _handle_get_task_status,
    "list_recent_tasks": _handle_list_recent_tasks,
    "respond_to_user_task": _handle_respond_to_user_task,
    "classify_meeting": _handle_classify_meeting,
    "list_unclassified_meetings": _handle_list_unclassified_meetings,
}


async def _execute_chat_specific_tool(
    tool_name: str,
    arguments: dict,
    active_client_id: str | None,
    active_project_id: str | None,
) -> str:
    """Execute chat-specific tools via Kotlin internal API using strategy map."""
    try:
        from app.tools.kotlin_client import kotlin_client

        handler = _TOOL_HANDLER_MAP.get(tool_name)
        if handler:
            return await handler(arguments, active_client_id, active_project_id, kotlin_client)
        return f"Unknown chat tool: {tool_name}"

    except Exception as e:
        logger.warning("Chat tool %s failed: %s", tool_name, e)
        return f"Tool error: {e}"


# ---------------------------------------------------------------------------
# switch_context resolution
# ---------------------------------------------------------------------------


def resolve_switch_context(arguments: dict, ctx: RuntimeContext) -> dict:
    """Resolve client/project names to IDs from cached runtime context.

    Returns dict with:
      - client_id, client_name, project_id, project_name (on success)
      - message: human-readable result or error for LLM
    """
    client_name_query = (arguments.get("client") or "").strip().lower()
    project_name_query = (arguments.get("project") or "").strip().lower()

    if not client_name_query:
        available = ", ".join(c.get("name", "?") for c in ctx.clients_projects)
        return {"message": f"Chybí jméno klienta. Dostupní klienti: {available}"}

    # Prefer exact match, then substring. Ambiguous → ask user.
    exact_match = None
    substring_matches: list[dict] = []
    for c in ctx.clients_projects:
        cname = (c.get("name") or "").lower()
        if cname == client_name_query:
            exact_match = c
            break
        elif client_name_query in cname:
            substring_matches.append(c)

    if exact_match:
        matched_client = exact_match
    elif len(substring_matches) == 1:
        matched_client = substring_matches[0]
    elif len(substring_matches) > 1:
        ambiguous_names = ", ".join(c.get("name", "?") for c in substring_matches)
        return {
            "message": (
                f"'{arguments.get('client')}' odpovídá více klientům: {ambiguous_names}. "
                f"Upřesni, kterého myslíš."
            ),
        }
    else:
        matched_client = None

    if not matched_client:
        available = ", ".join(c.get("name", "?") for c in ctx.clients_projects)
        return {
            "message": (
                f"Klient '{arguments.get('client')}' nenalezen. "
                f"Dostupní klienti: {available}"
            ),
        }

    client_id = matched_client["id"]
    client_name = matched_client.get("name", "")
    result = {"client_id": client_id, "client_name": client_name, "message": f"Přepnuto na {client_name}"}

    # Resolve project — same exact-then-substring logic
    if project_name_query:
        projects = matched_client.get("projects", [])
        matched_project = None
        project_substring_matches: list[dict] = []
        for p in projects:
            pname = (p.get("name") or "").lower()
            if pname == project_name_query:
                matched_project = p
                break
            elif project_name_query in pname:
                project_substring_matches.append(p)

        if not matched_project:
            if len(project_substring_matches) == 1:
                matched_project = project_substring_matches[0]
            elif len(project_substring_matches) > 1:
                ambiguous_projects = ", ".join(p.get("name", "?") for p in project_substring_matches)
                result["message"] = (
                    f"Přepnuto na {client_name}, ale '{arguments.get('project')}' odpovídá "
                    f"více projektům: {ambiguous_projects}. Upřesni, který myslíš."
                )
                return result

        if matched_project:
            result["project_id"] = matched_project["id"]
            result["project_name"] = matched_project.get("name", "")
            result["message"] = f"Přepnuto na {client_name} / {result['project_name']}"
        else:
            available_projects = ", ".join(p.get("name", "?") for p in projects)
            result["message"] = (
                f"Přepnuto na {client_name}, ale projekt '{arguments.get('project')}' "
                f"nenalezen. Dostupné projekty: {available_projects}"
            )

    return result


def resolve_client_name(client_id: str | None, ctx: RuntimeContext) -> str | None:
    """Resolve client name from cached runtime context."""
    if not client_id or not ctx:
        return None
    for c in ctx.clients_projects:
        if c.get("id") == client_id:
            return c.get("name")
    return None


def resolve_project_name(client_id: str | None, project_id: str | None, ctx: RuntimeContext) -> str | None:
    """Resolve project name from cached runtime context."""
    if not client_id or not project_id or not ctx:
        return None
    for c in ctx.clients_projects:
        if c.get("id") == client_id:
            for p in c.get("projects", []):
                if p.get("id") == project_id:
                    return p.get("name")
    return None


def resolve_client_projects_json(client_id: str | None, ctx: RuntimeContext) -> str:
    """Return JSON array of projects for the given client from cached runtime context."""
    if not client_id or not ctx:
        return "[]"
    for c in ctx.clients_projects:
        if c.get("id") == client_id:
            return json.dumps(c.get("projects", []))
    return "[]"
