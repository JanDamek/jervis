"""Tool execution, descriptions, and resolution for chat handler.

Responsibilities:
- Describe tool calls for thinking events
- Execute tools (base + chat-specific via strategy map)
- Resolve switch_context (name → ID, ambiguity handling)
- Resolve client/project names from cached runtime context

Tool call parsing: app.tools.ollama_parsing (shared with background handler).
"""
from __future__ import annotations

import asyncio
import json
import logging

from app.chat.system_prompt import RuntimeContext
from app.tools.executor import execute_tool, _TOOL_EXECUTION_TIMEOUT_S
from app.tools.ollama_parsing import extract_tool_calls  # noqa: F401 — re-exported for callers

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Tool call descriptions (for thinking events)
# ---------------------------------------------------------------------------


_TOOL_DESCRIPTIONS = {
    "kb_search": lambda a: f"Hledám v KB: {a.get('query', '')}",
    "web_search": lambda a: f"Hledám na webu: {a.get('query', '')}",
    "web_fetch": lambda a: f"Čtu stránku: {a.get('url', '')[:80]}",
    "request_tools": lambda a: f"Načítám nástroje: {a.get('category', '')}",
    "store_knowledge": lambda a: f"Ukládám znalost: {a.get('subject', '')}",
    "memory_store": lambda a: f"Zapamatuji si: {a.get('subject', '')}",
    "memory_recall": lambda a: f"Vzpomínám: {a.get('query', '')}",
    "list_affairs": lambda _: "Kontroluji aktivní témata",
    "get_kb_stats": lambda _: "Zjišťuji statistiky KB",
    "get_indexed_items": lambda _: "Kontroluji indexovaný obsah",
    "create_background_task": lambda a: f"Vytvářím úkol: {a.get('title', '')}",
    "create_thinking_graph": lambda a: f"Vytvářím myšlenkový graf: {a.get('title', '')}",
    "add_graph_vertex": lambda a: f"Přidávám krok: {a.get('title', '')}",
    "update_graph_vertex": lambda a: f"Upravuji krok: {a.get('vertex_id', '')}",
    "remove_graph_vertex": lambda a: f"Odebírám krok: {a.get('vertex_id', '')}",
    "dispatch_thinking_graph": lambda _: "Spouštím realizaci grafu na pozadí",
    "run_graph_vertex": lambda a: f"Spouštím krok na pozadí: {a.get('vertex_id', '')}",
    "dispatch_coding_agent": lambda _: "Odesílám coding task na agenta",
    "search_user_tasks": lambda a: f"Hledám úkoly: {a.get('query', '')}",
    "search_tasks": lambda a: f"Hledám úkoly: {a.get('query', '')}",
    "respond_to_user_task": lambda a: f"Odpovídám na úkol: {a.get('task_id', '')}",
    "get_task_status": lambda a: f"Kontroluji stav úkolu: {a.get('task_id', '')}",
    "list_recent_tasks": lambda _: "Kontroluji nedávné úkoly",
    "retry_failed_task": lambda a: f"Opakuji selhávající úkol: {a.get('task_id', '')}",
    "classify_meeting": lambda a: f"Klasifikuji nahrávku: {a.get('meeting_id', '')}",
    "list_unclassified_meetings": lambda _: "Kontroluji neklasifikované nahrávky",
    "get_meeting_transcript": lambda a: f"Čtu přepis meetingu: {a.get('meeting_id', '')}",
    "list_meetings": lambda a: f"Hledám meetingy{' pro ' + a.get('client_id', '') if a.get('client_id') else ''}",
    "switch_context": lambda a: f"Přepínám na: {a.get('client', '')} {a.get('project', '')}".strip(),
    "set_filter_rule": lambda a: f"Vytvářím filtr: {a.get('condition_type', '')} = {a.get('condition_value', '')}",
    "list_filter_rules": lambda _: "Kontroluji filtrační pravidla",
    "remove_filter_rule": lambda a: f"Odstraňuji pravidlo: {a.get('rule_id', '')}",
    "query_action_log": lambda a: f"Prohledávám akční log: {a.get('query', 'vše')}",
    "get_guidelines": lambda a: f"Načítám pravidla: {a.get('scope', 'GLOBAL')}",
    "update_guideline": lambda a: f"Aktualizuji pravidla: {a.get('category', '?')} ({a.get('scope', 'GLOBAL')})",
    "check_task_graph": lambda a: f"Kontroluji graf úkolu: {a.get('task_id', '')}",
    "answer_blocked_vertex": lambda a: f"Odpovídám na otázku v grafu: {a.get('vertex_id', '')}",
    "finance_summary": lambda a: f"Finanční přehled{' pro ' + a.get('client_id', '') if a.get('client_id') else ''}",
    "list_invoices": lambda a: f"Seznam faktur: {a.get('client_id', '')}",
    "record_payment": lambda a: f"Zaznamenávám platbu: {a.get('amount', 0)} CZK",
    "list_contracts": lambda a: f"Seznam smluv{' pro ' + a.get('client_id', '') if a.get('client_id') else ''}",
    "log_time": lambda a: f"Zapisuji čas: {a.get('hours', 0)}h",
    "check_capacity": lambda _: "Kontroluji kapacitu",
    "time_summary": lambda a: f"Přehled času{' pro ' + a.get('client_id', '') if a.get('client_id') else ''}",
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
    "create_thinking_graph",
    "add_graph_vertex",
    "update_graph_vertex",
    "remove_graph_vertex",
    "dispatch_thinking_graph",
    "run_graph_vertex",
    "dispatch_coding_agent",
    "search_user_tasks",
    "search_tasks",
    "get_task_status",
    "list_recent_tasks",
    "respond_to_user_task",
    "retry_failed_task",
    "classify_meeting",
    "list_unclassified_meetings",
    "get_meeting_transcript",
    "list_meetings",
    "get_guidelines",
    "update_guideline",
    "set_filter_rule",
    "list_filter_rules",
    "remove_filter_rule",
    "query_action_log",
    "check_task_graph",
    "answer_blocked_vertex",
    "finance_summary",
    "list_invoices",
    "record_payment",
    "list_contracts",
    "log_time",
    "check_capacity",
    "time_summary",
}


_GRAPH_TOOLS = {
    "create_thinking_graph", "add_graph_vertex", "update_graph_vertex",
    "remove_graph_vertex", "dispatch_thinking_graph", "run_graph_vertex",
}


async def execute_chat_tool(
    tool_name: str,
    arguments: dict,
    active_client_id: str | None,
    active_project_id: str | None,
    group_id: str | None = None,
    session_id: str | None = None,
) -> str:
    """Execute a tool call, routing between base tools and chat-specific tools."""
    if tool_name in _CHAT_SPECIFIC_TOOLS:
        return await _execute_chat_specific_tool(tool_name, arguments, active_client_id, active_project_id, session_id)

    return await execute_tool(
        tool_name=tool_name,
        arguments=arguments,
        client_id=active_client_id or "",
        project_id=active_project_id,
        processing_mode="FOREGROUND",
        group_id=group_id,
    )


# Strategy map: tool_name → handler coroutine
async def _handle_create_background_task(args, client_id, project_id, kotlin_client):
    import re
    _OID_RE = re.compile(r"^[0-9a-fA-F]{24}$")

    # Client: context first, then LLM arg
    effective_client_id = client_id or args.get("client_id")
    if effective_client_id and not _OID_RE.match(effective_client_id):
        return f"Chyba: client_id '{effective_client_id}' není platné ObjectId. Vyber klienta přes UI."

    # Project: ONLY use if LLM explicitly provides it or task is project-specific.
    # Don't blindly inherit chat context project — personal tasks (reminders, alerts)
    # would incorrectly land under the currently-viewed project and block its queue
    # if workspace is broken.
    llm_project = args.get("project_id")
    if llm_project and _OID_RE.match(llm_project):
        effective_project_id = llm_project
    elif llm_project:
        # LLM tried to provide but it's not valid ObjectId — ignore
        effective_project_id = None
    else:
        # LLM didn't provide project_id — use context only for project-specific tasks
        # (coding, bugfix). For reminders/alerts, project is not relevant.
        effective_project_id = project_id if args.get("is_project_task", False) else None

    result = await kotlin_client.create_background_task(
        title=args["title"],
        description=args["description"],
        client_id=effective_client_id,
        project_id=effective_project_id,
        priority=args.get("priority", "medium"),
    )
    return f"Background task created: {result}"


async def _handle_create_thinking_graph(args, client_id, project_id, _kotlin_client):
    """Create a new thinking graph (AgentGraph) for the chat session."""
    from app.chat.thinking_graph import create_graph, get_active_graph
    # session_id is injected by _execute_chat_specific_tool
    session_id = args.pop("__session_id__", None)
    if not session_id:
        return "Chyba: interní chyba — chybí session_id."

    # Guard: if session already has an active graph, refuse and point to add_graph_vertex
    existing = await get_active_graph(session_id)
    if existing:
        root = existing.vertices.get(existing.root_vertex_id)
        existing_title = root.title if root else existing.id
        vertex_count = len(existing.vertices)
        return (
            f"CHYBA: Už existuje aktivní graf '{existing_title}' ({vertex_count} kroků). "
            f"NEVYTVÁŘEJ nový — přidej kroky přes add_graph_vertex nebo uprav existující přes update_graph_vertex. "
            f"Jeden problém = jeden graf."
        )

    effective_client_id = client_id or args.get("client_id")
    effective_project_id = project_id or args.get("project_id")
    graph = await create_graph(
        title=args["title"],
        session_id=session_id,
        client_id=effective_client_id,
        project_id=effective_project_id,
    )
    vertex_count = len(graph.vertices)
    return f"Myšlenkový graf '{args['title']}' vytvořen ({vertex_count} vrcholů). Přidávej kroky přes add_graph_vertex."


async def _handle_add_graph_vertex(args, _client_id, _project_id, _kotlin_client):
    """Add a vertex to the active thinking graph."""
    from app.chat.thinking_graph import add_vertex
    session_id = args.pop("__session_id__", None)
    if not session_id:
        return "Chyba: interní chyba — chybí session_id."
    try:
        graph, vertex = await add_vertex(
            session_id=session_id,
            title=args["title"],
            description=args["description"],
            vertex_type=args.get("vertex_type", "executor"),
            depends_on=args.get("depends_on"),
        )
        deps = ", ".join(args.get("depends_on", [])) or "root"
        return (
            f"Krok '{vertex.title}' ({vertex.id}) přidán do grafu. "
            f"Typ: {vertex.vertex_type.value}, závisí na: {deps}. "
            f"Celkem {len(graph.vertices)} kroků."
        )
    except ValueError as e:
        return f"Chyba: {e}"


async def _handle_update_graph_vertex(args, _client_id, _project_id, _kotlin_client):
    """Update an existing vertex in the active thinking graph."""
    from app.chat.thinking_graph import update_vertex
    session_id = args.pop("__session_id__", None)
    if not session_id:
        return "Chyba: interní chyba — chybí session_id."
    try:
        graph, vertex = await update_vertex(
            session_id=session_id,
            vertex_id=args["vertex_id"],
            title=args.get("title"),
            description=args.get("description"),
            vertex_type=args.get("vertex_type"),
        )
        return f"Krok '{vertex.title}' ({vertex.id}) aktualizován."
    except ValueError as e:
        return f"Chyba: {e}"


async def _handle_remove_graph_vertex(args, _client_id, _project_id, _kotlin_client):
    """Remove a vertex from the active thinking graph."""
    from app.chat.thinking_graph import remove_vertex
    session_id = args.pop("__session_id__", None)
    if not session_id:
        return "Chyba: interní chyba — chybí session_id."
    try:
        graph = await remove_vertex(
            session_id=session_id,
            vertex_id=args["vertex_id"],
        )
        return f"Krok '{args['vertex_id']}' odebrán. Zbývá {len(graph.vertices)} kroků."
    except ValueError as e:
        return f"Chyba: {e}"


async def _handle_dispatch_thinking_graph(args, client_id, project_id, kotlin_client):
    """Finalize the thinking graph and dispatch as a background task."""
    from app.chat.thinking_graph import dispatch_graph
    session_id = args.pop("__session_id__", None)
    if not session_id:
        return "Chyba: interní chyba — chybí session_id."
    try:
        task_id = await dispatch_graph(
            session_id=session_id,
            kotlin_client=kotlin_client,
            client_id=client_id or args.get("client_id"),
            project_id=project_id or args.get("project_id"),
        )
        return f"Myšlenkový graf odeslán k realizaci. Task ID: {task_id}"
    except ValueError as e:
        return f"Chyba: {e}"


async def _handle_run_graph_vertex(args, client_id, project_id, kotlin_client):
    """Dispatch a single vertex to run in background, results flow back into the graph."""
    from app.chat.thinking_graph import run_vertex
    session_id = args.pop("__session_id__", None)
    if not session_id:
        return "Chyba: interní chyba — chybí session_id."
    try:
        task_id = await run_vertex(
            session_id=session_id,
            vertex_id=args["vertex_id"],
            kotlin_client=kotlin_client,
            client_id=client_id or args.get("client_id"),
            project_id=project_id or args.get("project_id"),
        )
        return f"Krok '{args['vertex_id']}' spuštěn na pozadí. Task ID: {task_id}. Výsledek se vrátí do grafu."
    except ValueError as e:
        return f"Chyba: {e}"


async def _handle_dispatch_coding_agent(args, client_id, project_id, kotlin_client):
    # Context IDs have priority over LLM-provided args (LLM often sends names instead of ObjectIds)
    effective_client_id = client_id or args.get("client_id")
    effective_project_id = project_id or args.get("project_id")
    # dispatch_coding_agent needs project_id (for git workspace)
    if not effective_project_id:
        return "Chyba: project_id je povinný pro dispatch coding agenta. Vyber projekt přes UI."
    # Validate ObjectId format if provided
    import re
    _OID_RE = re.compile(r"^[0-9a-fA-F]{24}$")
    if effective_client_id and not _OID_RE.match(effective_client_id):
        return f"Chyba: client_id '{effective_client_id}' není platné ObjectId. Vyber klienta přes UI."
    if not _OID_RE.match(effective_project_id):
        return f"Chyba: project_id '{effective_project_id}' není platné ObjectId. Vyber projekt přes UI."
    result = await kotlin_client.dispatch_coding_agent(
        task_description=args["task_description"],
        client_id=effective_client_id,
        project_id=effective_project_id,
        agent_preference=args.get("agent_preference", "auto"),
    )

    # Link to memory graph immediately so the task appears in UI right away
    # (orchestrator also links when it picks up the task, but there can be a delay)
    try:
        if isinstance(result, dict) and result.get("taskId"):
            from app.agent.persistence import agent_store
            await agent_store.link_thinking_graph(
                task_id=result["taskId"],
                sub_graph_id="",
                title=args.get("task_description", "")[:80] or "Coding task",
                completed=False,
                failed=False,
                result_summary="",
                client_id=effective_client_id or "",
                project_id=effective_project_id,
            )
    except Exception as e:
        logger.warning("Failed to link dispatched coding task to memory graph: %s", e)

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
    # LLM sometimes sends user_task_id instead of task_id — accept both
    task_id = args.get("task_id") or args.get("user_task_id")
    if not task_id:
        return "Error: task_id is required"
    result = await kotlin_client.respond_to_user_task(
        task_id=task_id,
        response=args["response"],
    )
    return f"User task responded: {result}"


async def _handle_dismiss_user_tasks(args, _client_id, _project_id, kotlin_client):
    task_ids = args.get("task_ids", [])
    if not task_ids:
        return "Error: task_ids is required"
    result = await kotlin_client.dismiss_user_tasks(task_ids)
    return f"Dismissed: {result}"


async def _handle_retry_failed_task(args, _client_id, _project_id, kotlin_client):
    task_id = args.get("task_id")
    if not task_id:
        return "Chyba: task_id je povinný."
    result = await kotlin_client.retry_failed_task(task_id)
    return f"Retry result: {result}"


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


async def _handle_get_meeting_transcript(args, _client_id, _project_id, kotlin_client):
    meeting_id = args.get("meeting_id")
    if not meeting_id:
        return "Chyba: meeting_id je povinný."
    return await kotlin_client.get_meeting_transcript(meeting_id)


async def _handle_list_meetings(args, client_id, project_id, kotlin_client):
    return await kotlin_client.list_meetings(
        client_id=args.get("client_id") or client_id or "",
        project_id=args.get("project_id") or project_id,
        state=args.get("state"),
        limit=args.get("limit", 20),
    )


async def _handle_get_guidelines(args, client_id, project_id, kotlin_client):
    effective_client_id = args.get("client_id") or client_id
    effective_project_id = args.get("project_id") or project_id
    return await kotlin_client.get_guidelines(
        scope="GLOBAL" if not effective_client_id else ("CLIENT" if not effective_project_id else "PROJECT"),
        client_id=effective_client_id,
        project_id=effective_project_id,
    )


async def _handle_update_guideline(args, client_id, project_id, kotlin_client):
    scope = args.get("scope", "GLOBAL")
    category = args.get("category")
    rules = args.get("rules", {})
    effective_client_id = args.get("client_id") or client_id
    effective_project_id = args.get("project_id") or project_id

    if not category:
        return "Chyba: category je povinný parametr."
    if scope in ("CLIENT", "PROJECT") and not effective_client_id:
        return "Chyba: client_id je povinný pro CLIENT/PROJECT scope."
    if scope == "PROJECT" and not effective_project_id:
        return "Chyba: project_id je povinný pro PROJECT scope."

    return await kotlin_client.update_guideline(
        scope=scope,
        category=category,
        rules=rules,
        client_id=effective_client_id,
        project_id=effective_project_id,
    )


async def _handle_set_filter_rule(args, client_id, project_id, kotlin_client):
    source_type = args.get("source_type", "ALL")
    condition_type = args.get("condition_type")
    condition_value = args.get("condition_value")
    action = args.get("action", "IGNORE")
    description = args.get("description")

    if not condition_type or not condition_value:
        return "Chyba: condition_type a condition_value jsou povinné."

    return await kotlin_client.set_filter_rule(
        source_type=source_type,
        condition_type=condition_type,
        condition_value=condition_value,
        action=action,
        description=description,
        client_id=client_id,
        project_id=project_id,
    )


async def _handle_list_filter_rules(_args, client_id, project_id, kotlin_client):
    return await kotlin_client.list_filter_rules(
        client_id=client_id,
        project_id=project_id,
    )


async def _handle_remove_filter_rule(args, _client_id, _project_id, kotlin_client):
    rule_id = args.get("rule_id")
    if not rule_id:
        return "Chyba: rule_id je povinný."
    return await kotlin_client.remove_filter_rule(rule_id=rule_id)


async def _handle_query_action_log(args, client_id, _project_id, _kotlin_client):
    if not client_id:
        return "Chyba: client_id je povinný pro dotaz na akční log."
    from app.memory.action_log import query_action_log
    return await query_action_log(
        client_id=client_id,
        query=args.get("query", ""),
        action_type=args.get("action_type"),
        project_id=args.get("project_id", _project_id),
        max_results=args.get("max_results", 10),
    )


async def _handle_check_task_graph(args, _client_id, _project_id, _kotlin_client):
    """Check state of a task's thinking graph."""
    from app.agent.persistence import agent_store
    from app.agent.graph import get_stats, find_blocked_vertices

    task_id = args.get("task_id", "")
    if not task_id:
        return "Chyba: chybí task_id."

    # Try RAM cache first, then DB
    graph = agent_store.get_cached_subgraph(task_id)
    if not graph:
        graph = await agent_store.load(task_id)
    if not graph:
        return f"Graf pro úkol {task_id} nenalezen."

    stats = get_stats(graph)
    blocked = find_blocked_vertices(graph)

    parts = [
        f"Graf úkolu {task_id}:",
        f"  Stav: {graph.status.value}",
        f"  Vrcholů: {stats['total_vertices']} (hran: {stats['total_edges']})",
        f"  Stavy: {stats['vertex_statuses']}",
        f"  Tokeny: {stats['total_tokens']}, LLM volání: {stats['total_llm_calls']}",
    ]
    if blocked:
        parts.append(f"  ČEKÁ NA ODPOVĚĎ ({len(blocked)} vertexů):")
        for v in blocked:
            parts.append(f"    - [{v.id}] {v.description}")
    return "\n".join(parts)


async def _handle_answer_blocked_vertex(args, _client_id, _project_id, _kotlin_client):
    """Answer a blocked ASK_USER vertex to unblock graph processing."""
    from app.agent.persistence import agent_store

    task_id = args.get("task_id", "")
    vertex_id = args.get("vertex_id", "")
    answer = args.get("answer", "")

    if not task_id or not vertex_id or not answer:
        return "Chyba: chybí task_id, vertex_id nebo answer."

    success = await agent_store.resume_blocked_vertex(task_id, vertex_id, answer)
    if success:
        return f"Vertex {vertex_id} odemčen odpovědí. Graf pokračuje ve zpracování."
    return f"Nepodařilo se odemknout vertex {vertex_id} — buď neexistuje nebo není blokovaný."


async def _handle_finance_summary(args, client_id, _project_id, kotlin_client):
    """Get financial summary for a client."""
    cid = args.get("client_id") or client_id
    if not cid:
        return "Chyba: client_id je povinný."
    from_date = args.get("from_date")
    to_date = args.get("to_date")
    params = {"client_id": cid}
    if from_date:
        params["from"] = from_date
    if to_date:
        params["to"] = to_date
    return await kotlin_client.get("/internal/finance/summary", params=params)


async def _handle_list_invoices(args, _client_id, _project_id, kotlin_client):
    """List financial records for a client."""
    cid = args.get("client_id")
    if not cid:
        return "Chyba: client_id je povinný."
    status = args.get("status")
    type_ = args.get("type")
    params = {"client_id": cid}
    if status:
        params["status"] = status
    if type_:
        params["type"] = type_
    return await kotlin_client.get("/internal/finance/records", params=params)


async def _handle_record_payment(args, client_id, _project_id, kotlin_client):
    """Record a payment and attempt auto-matching."""
    import datetime

    cid = args.get("client_id") or client_id
    if not cid:
        return "Chyba: client_id je povinný."
    amount = args.get("amount")
    if not amount:
        return "Chyba: amount je povinný."
    payload = {
        "clientId": cid,
        "type": "PAYMENT",
        "amount": float(amount),
        "amountCzk": float(amount),
        "variableSymbol": args.get("variable_symbol"),
        "counterpartyName": args.get("counterparty_name"),
        "counterpartyAccount": args.get("counterparty_account"),
        "paymentDate": args.get("payment_date") or datetime.date.today().isoformat(),
        "sourceUrn": "chat",
        "description": args.get("description", ""),
    }
    return await kotlin_client.post("/internal/finance/record", json=payload)


async def _handle_list_contracts(args, client_id, _project_id, kotlin_client):
    """List contracts for a client."""
    cid = args.get("client_id") or client_id
    active_only = args.get("active_only", True)
    params = {}
    if cid:
        params["client_id"] = cid
    if active_only:
        params["active_only"] = "true"
    return await kotlin_client.get("/internal/finance/contracts", params=params)


async def _handle_log_time(args, client_id, _project_id, kotlin_client):
    """Log time entry."""
    cid = args.get("client_id") or client_id
    if not cid:
        return "Chyba: client_id je povinný."
    hours = args.get("hours")
    if not hours:
        return "Chyba: hours je povinný."
    payload = {
        "clientId": cid,
        "hours": float(hours),
        "description": args.get("description", ""),
        "date": args.get("date"),
        "source": "MANUAL",
    }
    return await kotlin_client.post("/internal/time/log", json=payload)


async def _handle_check_capacity(args, _client_id, _project_id, kotlin_client):
    """Check capacity snapshot."""
    resp = await kotlin_client.get("/internal/time/capacity")
    hours_needed = args.get("hours_needed")
    if hours_needed and resp:
        try:
            import json as _json
            data = _json.loads(resp) if isinstance(resp, str) else resp
            available = data.get("availableHours", 0)
            needed = float(hours_needed)
            if needed > available:
                return f"{resp}\n\n⚠️ Kapacita nedostačuje: potřeba {needed}h/týden, dostupné {available}h/týden."
        except Exception:
            pass
    return resp


async def _handle_time_summary(args, client_id, _project_id, kotlin_client):
    """Time summary for period."""
    params = {}
    cid = args.get("client_id") or client_id
    if cid:
        params["client_id"] = cid
    if args.get("from_date"):
        params["from"] = args["from_date"]
    if args.get("to_date"):
        params["to"] = args["to_date"]
    return await kotlin_client.get("/internal/time/summary", params=params)


_TOOL_HANDLER_MAP = {
    "create_background_task": _handle_create_background_task,
    "create_thinking_graph": _handle_create_thinking_graph,
    "add_graph_vertex": _handle_add_graph_vertex,
    "update_graph_vertex": _handle_update_graph_vertex,
    "remove_graph_vertex": _handle_remove_graph_vertex,
    "dispatch_thinking_graph": _handle_dispatch_thinking_graph,
    "run_graph_vertex": _handle_run_graph_vertex,
    "dispatch_coding_agent": _handle_dispatch_coding_agent,
    "search_user_tasks": _handle_search_tasks,
    "search_tasks": _handle_search_tasks,
    "get_task_status": _handle_get_task_status,
    "list_recent_tasks": _handle_list_recent_tasks,
    "respond_to_user_task": _handle_respond_to_user_task,
    "dismiss_user_tasks": _handle_dismiss_user_tasks,
    "retry_failed_task": _handle_retry_failed_task,
    "classify_meeting": _handle_classify_meeting,
    "list_unclassified_meetings": _handle_list_unclassified_meetings,
    "get_meeting_transcript": _handle_get_meeting_transcript,
    "list_meetings": _handle_list_meetings,
    "get_guidelines": _handle_get_guidelines,
    "update_guideline": _handle_update_guideline,
    "set_filter_rule": _handle_set_filter_rule,
    "list_filter_rules": _handle_list_filter_rules,
    "remove_filter_rule": _handle_remove_filter_rule,
    "query_action_log": _handle_query_action_log,
    "check_task_graph": _handle_check_task_graph,
    "answer_blocked_vertex": _handle_answer_blocked_vertex,
    "finance_summary": _handle_finance_summary,
    "list_invoices": _handle_list_invoices,
    "record_payment": _handle_record_payment,
    "list_contracts": _handle_list_contracts,
    "log_time": _handle_log_time,
    "check_capacity": _handle_check_capacity,
    "time_summary": _handle_time_summary,
}


async def _execute_chat_specific_tool(
    tool_name: str,
    arguments: dict,
    active_client_id: str | None,
    active_project_id: str | None,
    session_id: str | None = None,
) -> str:
    """Execute chat-specific tools via Kotlin internal API using strategy map."""

    try:
        from app.tools.kotlin_client import kotlin_client

        # Inject session_id for map tools (they need it to find active graph)
        if tool_name in _GRAPH_TOOLS and session_id:
            arguments["__session_id__"] = session_id

        handler = _TOOL_HANDLER_MAP.get(tool_name)
        if not handler:
            return f"Unknown chat tool: {tool_name}"
        return await asyncio.wait_for(
            handler(arguments, active_client_id, active_project_id, kotlin_client),
            timeout=_TOOL_EXECUTION_TIMEOUT_S,
        )

    except asyncio.TimeoutError:
        logger.warning("Chat tool %s timed out after %ds", tool_name, _TOOL_EXECUTION_TIMEOUT_S)
        return f"Error: Tool '{tool_name}' timed out after {_TOOL_EXECUTION_TIMEOUT_S}s."
    except Exception as e:
        logger.warning("Chat tool %s failed: %s", tool_name, e)
        return f"Error executing {tool_name}: {e}"


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
