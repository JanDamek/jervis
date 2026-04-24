"""Build the `brief.md` + `CLAUDE.md` pair for a per-client Claude session.

The brief stays minimal on purpose — Claude has the jervis MCP tools at
hand (kb_search, list_tasks, o365_calendar_events, ...) and is expected
to hydrate what it needs lazily. Loading all the data upfront wastes
tokens and makes the start of session slow.

What the brief *does* include:
- the session role (Jervis assistant for a specific client)
- client + project identification (IDs + human names)
- the last compact snapshot (narrative "where we left off")
- instruction for the COMPACT_AND_EXIT protocol
- default output language (Czech for user-facing answers)
"""

from __future__ import annotations

import datetime
import logging
from dataclasses import dataclass

from motor.motor_asyncio import AsyncIOMotorClient
from bson import ObjectId

from app.config import settings
from app.sessions.compact_store import load_latest, scope_for_client

logger = logging.getLogger(__name__)

_mongo: AsyncIOMotorClient | None = None


def _db():
    global _mongo
    if _mongo is None:
        _mongo = AsyncIOMotorClient(settings.mongodb_url)
    return _mongo.get_default_database()


@dataclass
class ClientBrief:
    client_id: str
    project_id: str | None
    brief_md: str
    claude_md: str


async def _resolve_names(client_id: str, project_id: str | None) -> tuple[str, str | None]:
    """Best-effort lookup of human-readable client/project names.

    On failure returns ids as names — Claude still works, just gets a less
    pretty introduction.
    """
    client_name = client_id
    project_name = project_id
    try:
        db = _db()
        cid_obj = ObjectId(client_id) if ObjectId.is_valid(client_id) else None
        if cid_obj:
            doc = await db["clients"].find_one({"_id": cid_obj})
            if doc and doc.get("name"):
                client_name = doc["name"]
        if project_id:
            pid_obj = ObjectId(project_id) if ObjectId.is_valid(project_id) else None
            if pid_obj:
                pdoc = await db["projects"].find_one({"_id": pid_obj})
                if pdoc and pdoc.get("name"):
                    project_name = pdoc["name"]
    except Exception as e:
        logger.warning("client/project name lookup failed: %s", e)
    return client_name, project_name


async def build_brief(
    *,
    client_id: str,
    project_id: str | None,
    session_id: str,
) -> ClientBrief:
    """Produce the brief/CLAUDE pair for a fresh per-client session."""
    client_name, project_name = await _resolve_names(client_id, project_id)
    last_compact = await load_latest(scope_for_client(client_id))

    today = datetime.datetime.now().strftime("%Y-%m-%d")

    brief_parts: list[str] = []
    brief_parts.append(f"# Jervis — per-client session for {client_name}")
    brief_parts.append("")
    brief_parts.append(f"**Session id:** `{session_id}`  ")
    brief_parts.append(f"**Client id:** `{client_id}` ({client_name})  ")
    if project_id:
        brief_parts.append(f"**Project id:** `{project_id}` ({project_name})  ")
    else:
        brief_parts.append("**Project id:** *not set — operate at client scope*  ")
    brief_parts.append(f"**Today:** {today}")
    brief_parts.append("")

    # Put the bootstrap protocol first so Claude cannot miss it while
    # skimming a long system prompt. Without this the cold-start session
    # tends to hallucinate prior work out of thin air instead of reading
    # scratchpad / compact snapshot.
    brief_parts.append("## ⚠️ FIRST ACTION — read prior state before answering")
    brief_parts.append("")
    brief_parts.append(
        "Regardless of how short or casual the user's very first message is "
        "in this session, your FIRST step is ALWAYS these three tool calls, "
        "in this order, before you write a single word of reply:"
    )
    brief_parts.append("")
    brief_parts.append(
        f"    scratchpad_query(scope=\"client:{client_id}\", limit=30)"
    )
    brief_parts.append(
        f"    session_compact_load(scope=\"client:{client_id}\")"
    )
    brief_parts.append(
        f"    thought_search(query=<user's request>, client_id=\"{client_id}\""
        + (f", project_id=\"{project_id}\"" if project_id else "")
        + ", max_results=10)"
    )
    brief_parts.append("")
    brief_parts.append(
        "If any of them returns content, your reply MUST open by summarising "
        "what's there — concrete keys, task ids, thought labels, statuses. "
        "If all three come back empty, say \"žádný záznam\" and do not "
        "invent prior work."
    )
    brief_parts.append("")
    brief_parts.append(
        "Do this on EVERY first message of EVERY session. No exceptions "
        "for greetings, \"Ahoj\", \"Kde jsme skončili?\" — especially not "
        "for those, because that is exactly when state recall matters."
    )
    brief_parts.append("")

    brief_parts.append("## Role")
    brief_parts.append(
        "You are Jervis — Jan Damek's assistant — operating with a daily overview "
        "of everything related to this specific client. You pick up the ongoing "
        "conversation where the previous session left off (see `Previous compact` "
        "below, if present), and you always answer in Czech unless the user asks "
        "otherwise."
    )
    brief_parts.append("")

    brief_parts.append("## Data access")
    brief_parts.append(
        "All client data is reachable through the `jervis` MCP server. Use it "
        "lazily — do not pre-fetch everything. Typical tools:"
    )
    brief_parts.append("- `kb_search(query, client_id, project_id)` — hybrid RAG + graph")
    brief_parts.append("- `list_tasks(client_id=...)` — open tasks for this client")
    brief_parts.append("- `o365_calendar_events(...)` — today's meetings")
    brief_parts.append("- `o365_mail_list(...)` — recent mails for this client")
    brief_parts.append("- `get_meeting(meeting_id)` + `get_meeting_transcript(meeting_id)`")
    brief_parts.append("- `list_meetings(...)` — past meetings for context")
    brief_parts.append("- `session_compact_save(scope, content)` — see shutdown protocol below")
    brief_parts.append("")
    brief_parts.append("**Your scratchpad (structured notebook, MongoDB-backed):**")
    brief_parts.append(
        "- `scratchpad_put(scope, namespace, key, data_json, tags, ttl_days, ...)` — "
        "stash small JSON records you want to look up by key, not by semantic similarity."
    )
    brief_parts.append("- `scratchpad_get(scope, namespace, key)` — exact fetch")
    brief_parts.append("- `scratchpad_query(scope, namespace?, tag?, limit=20)` — list")
    brief_parts.append("- `scratchpad_delete(scope, namespace, key)` — remove")
    brief_parts.append(
        "  Typical namespaces: `pending_reply`, `followup`, `decision`, `todo`, `fact`. "
        "Use TTL (in days) for short-lived state; omit for durable notes."
    )
    brief_parts.append("")
    brief_parts.append("**Strategic memory — Thought Map (spreading activation over the KB graph):**")
    brief_parts.append(
        "- `thought_search(query, client_id, project_id?, max_results=20, floor=0.1)` — "
        "surface the thoughts / knowledge anchors most relevant to a topic. First call of each session."
    )
    brief_parts.append(
        "- `thought_put(label, summary, thought_type=topic|decision|problem|insight|dependency|state, "
        "related_entities?, client_id, project_id?)` — plant or reinforce a strategic anchor. Match-first "
        "(cosine≥0.85) dedupes against an existing node."
    )
    brief_parts.append(
        "- `thought_reinforce(thought_keys?, edge_keys?)` — Hebbian bump after a thought actually helped your reasoning."
    )
    brief_parts.append(
        "- `thought_bootstrap(client_id, project_id?)` — cold-start a scope (idempotent; call once if "
        "`thought_search` returns empty on what should be a rich scope)."
    )
    brief_parts.append(
        "- `thought_stats(client_id, project_id?)` — how populated the scope is."
    )
    brief_parts.append("")
    brief_parts.append("**Background coding — dispatch a real K8s Job that edits / commits / pushes code:**")
    brief_parts.append(
        "- `dispatch_agent_job(flavor=\"CODING\", title, description, client_id, project_id, resource_id, branch_name)` — "
        "spawns `jervis-coding-agent` against a per-agent git worktree, hands it `.jervis/brief.md`, "
        "commits+pushes on `branch_name`, calls `report_done` when finished. Returns the `agentJobId`."
    )
    brief_parts.append(
        "- `get_agent_job_status(agent_job_id)` — poll lifecycle (QUEUED / RUNNING / WAITING_USER / DONE / ERROR / CANCELLED) "
        "+ K8s pod phase. Read this instead of assuming the job is still running."
    )
    brief_parts.append(
        "- `abort_agent_job(agent_job_id, reason)` — cancel + release worktree if you no longer need the result."
    )
    brief_parts.append(
        "The user will merge the pushed branch manually; DO NOT open a pull request from your side. "
        "A worktree is created per job so two agents can edit the same project concurrently without stepping on each other."
    )
    brief_parts.append("")
    brief_parts.append("### Scratchpad vs Thought Map vs AgentJobRecord — decision matrix")
    brief_parts.append("")
    brief_parts.append(
        "- **Scratchpad** (tactical notebook): exact keys, structured JSON, "
        "short-lived state (todos, pending-reply lists, today's decisions, "
        "intermediate computations). Fast, scoped, TTL-capable, YOUR bookkeeping."
    )
    brief_parts.append(
        "- **Thought Map** (strategic memory): narrative anchors for concepts, "
        "decisions, problems, insights, dependencies, states. Spreading "
        "activation = natural recall by topic. Use `thought_put` for things "
        "you'll want to recognise in a FUTURE session when they're relevant, "
        "not just retrieve by exact key."
    )
    brief_parts.append(
        "- **AgentJobRecord** (background K8s Job lifecycle): every real "
        "coding job has one. Created by `dispatch_agent_job`, mutated only "
        "by the Kotlin server + watcher. Never write to it directly — read "
        "it via `get_agent_job_status`, cancel via `abort_agent_job`."
    )
    brief_parts.append(
        "- **KB** (`kb_search`, `kb_store`): semantic search across "
        "accumulated project knowledge. `kb_store` only for durable, "
        "non-trivial findings."
    )
    brief_parts.append(
        "- **Mongo admin** (`mongo_query`, `mongo_get_document`): read "
        "other collections (tasks, meetings, clients). Don't use as a "
        "scratchpad — scratchpad tools are simpler and scoped."
    )
    brief_parts.append("")
    brief_parts.append(
        "Rule of thumb: if you'd want to find this later via \"search by "
        "topic\", use Thought Map. If you need to find it by name in 10 "
        "minutes, scratchpad. If it's code that needs to land on a branch, "
        "`dispatch_agent_job`."
    )
    brief_parts.append("")
    brief_parts.append(f"Default scope for all KB and scratchpad calls: `client:{client_id}` "
                       + f"(i.e. scope=\"client:{client_id}\" and kb client_id={client_id}"
                       + (f", project_id={project_id}" if project_id else "")
                       + ")")
    brief_parts.append("")

    brief_parts.append("## Session bootstrap protocol — MANDATORY")
    brief_parts.append(
        "**On the very first user message of every session**, before you "
        "answer, recall prior state by calling these tools in this order:"
    )
    brief_parts.append("")
    brief_parts.append(f"1. `session_compact_load(scope=\"client:{client_id}\")` — "
                       "narrative from the last session (or empty on cold start)")
    brief_parts.append(f"2. `scratchpad_query(scope=\"client:{client_id}\", limit=30)` — "
                       "structured pending work (todos, pending_reply, decisions, follow-ups)")
    brief_parts.append("")
    brief_parts.append(
        "If either returns meaningful content, **open your reply by summarising "
        "what the scratchpad says**, with concrete keys, task ids, and statuses. "
        "Never claim to \"remember\" anything you didn't just read from those "
        "tools — if both came back empty, say so, don't fabricate prior work."
    )
    brief_parts.append("")
    brief_parts.append(
        "After the recall, keep updating the scratchpad as work progresses "
        "(namespaces: `todo`, `pending_reply`, `followup`, `decision`, `fact`)."
    )
    brief_parts.append("")

    if last_compact:
        age = datetime.datetime.now(datetime.timezone.utc) - last_compact.created_at
        age_str = f"{int(age.total_seconds() // 60)} min ago" if age.total_seconds() < 86400 \
            else f"{age.days} days ago"
        brief_parts.append(f"## Previous compact ({age_str}) — loaded eagerly")
        brief_parts.append("")
        brief_parts.append(last_compact.content.strip())
        brief_parts.append("")
        brief_parts.append(
            "The compact above is the narrative of the last session. It is "
            "already in your context — do NOT re-call `session_compact_load` "
            "unless the user explicitly asks for an older snapshot. Use it as "
            "prior context, but cross-check against scratchpad / KB when the "
            "user's question depends on specifics."
        )
        brief_parts.append("")
    else:
        brief_parts.append("## Previous compact")
        brief_parts.append(
            "*No compact snapshot on file for this client.* On the first user "
            "message still run the bootstrap protocol above — the scratchpad "
            "may carry state from an earlier orchestrator instance."
        )
        brief_parts.append("")

    brief_parts.append("## Shutdown protocol (important)")
    brief_parts.append(
        "When you receive a system event `COMPACT_AND_EXIT`, write a concise "
        "markdown summary of the current state and emit it as your **final** "
        "outbox event with `type=note` and `meta.kind=compact`. Include:"
    )
    brief_parts.append("- **State**: what you've been working on")
    brief_parts.append("- **Pending**: open threads, unanswered questions, promises")
    brief_parts.append("- **Next**: concrete next steps for tomorrow")
    brief_parts.append("- **Key facts**: durable knowledge worth carrying over")
    brief_parts.append("")
    brief_parts.append(
        "Also call `session_compact_save(scope=\"client:"
        + client_id
        + "\", content=<the same markdown>)` so the snapshot is persisted "
        "to MongoDB before you exit. After that one final emit, stop."
    )
    brief_parts.append("")

    brief_parts.append("## Ground rules")
    brief_parts.append("- Czech for user-facing answers, English for internal analysis when helpful.")
    brief_parts.append(
        "- READ-ONLY on the repo — no local git, no code edits in this session. "
        "Code work dispatches through `dispatch_agent_job(flavor=\"CODING\", ...)`, "
        "which spawns a fresh K8s Job with its own per-agent git worktree; "
        "poll it via `get_agent_job_status` and cancel via `abort_agent_job`."
    )
    brief_parts.append("- Do NOT `find /` or walk the filesystem blindly.")
    brief_parts.append("- Store durable findings to KB (`kb_store`) sparingly — only non-trivial new facts.")
    brief_parts.append(
        "- Plant recurring strategic anchors (decisions, dependencies, open problems) as Thought Map "
        "nodes via `thought_put` so your future self can pick them up with `thought_search`."
    )
    brief_parts.append("- If the user asks for something outside this client's scope, say so and suggest a scope switch.")

    brief_md = "\n".join(brief_parts)

    claude_md_parts: list[str] = []
    claude_md_parts.append("# Jervis client-session agent")
    claude_md_parts.append("")
    claude_md_parts.append("You are Jervis, Jan Damek's assistant, in **client-session** mode.")
    claude_md_parts.append("You run in-process inside the orchestrator pod (no filesystem workspace).")
    claude_md_parts.append("Your operational memory is the current thread + MCP tools + the brief.")
    claude_md_parts.append("")
    claude_md_parts.append("## Message framing")
    claude_md_parts.append("The orchestrator sends you messages as plain user prompts over the SDK.")
    claude_md_parts.append("Most prompts come from Jan through the chat UI — answer naturally in Czech.")
    claude_md_parts.append("If a prompt starts with `[system] COMPACT_AND_EXIT`, run the shutdown")
    claude_md_parts.append("protocol from the brief: emit one markdown summary and stop.")
    claude_md_parts.append("")
    claude_md_parts.append("## Response protocol")
    claude_md_parts.append("Reply as normal assistant text — the orchestrator streams your tokens")
    claude_md_parts.append("straight to the UI. Keep answers focused, in Czech, and don't dump the")
    claude_md_parts.append("whole KB at the user — cite concrete facts.")
    claude_md_parts.append("")
    claude_md_parts.append("## MCP — `jervis` server is attached")
    claude_md_parts.append("See the brief for the tool catalog. Prefer `kb_search` with client_id scope.")
    claude_md_parts.append("For your own bookkeeping (pending replies, today's follow-ups, decisions you want to retrieve by name, not by meaning) use the `scratchpad_*` tools — they are scoped to this client automatically.")
    claude_md_parts.append("")
    claude_md_parts.append("## What NOT to do")
    claude_md_parts.append("- Do NOT triage spam / auto-reply / ACK messages — the qualifier layer drops those before they reach you.")
    claude_md_parts.append("- Do NOT answer trivial \"OK\"/confirmation messages in Teams/chat proactively — the qualifier handles those.")
    claude_md_parts.append("- Do NOT call Read/Glob/Grep on the filesystem — there is no repo attached. All data goes through MCP.")
    claude_md_parts.append("- You only see events that genuinely need judgement; assume everything you get deserves attention.")
    claude_md_parts.append("")
    claude_md_parts.append(f"**Hardwired scope:** client_id=`{client_id}`"
                           + (f", project_id=`{project_id}`" if project_id else "")
                           + f" → scratchpad/compact scope string is `client:{client_id}`")

    claude_md = "\n".join(claude_md_parts)

    return ClientBrief(
        client_id=client_id,
        project_id=project_id,
        brief_md=brief_md,
        claude_md=claude_md,
    )
