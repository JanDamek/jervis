"""Qualification Agent handler.

Runs an LLM agent with CORE tools to analyze KB results and prepare context for orchestrator.
Called from /qualify endpoint. Fire-and-forget — reports result to Kotlin via callback.

Flow:
1. Receive KB analysis context (summary, entities, urgency, suggested_actions)
2. Build system prompt for qualification + context preparation
3. Run LLM with CORE tools (kb_search, web_search, memory)
4. Agent decides: DONE, QUEUED, or URGENT_ALERT + prepares orchestrator context
5. POST result to Kotlin /internal/qualification-done

Design principle: NO content-type-specific handlers. The LLM model decides
what type of content it is (invoice, job offer, medical report, anything)
and how to handle it — using KB conventions, tools, and general reasoning.
"""

from __future__ import annotations

import logging
from typing import Any

from pydantic import BaseModel, Field

from app.config import settings, estimate_tokens
from app.llm.provider import llm_provider
from app.llm.router_client import route_request
from app.tools.executor import execute_tool
from app.unified.tool_sets import ToolTier, get_tools

logger = logging.getLogger(__name__)

MAX_ITERATIONS = 5


class QualifyRequest(BaseModel):
    """Request from Kotlin /qualify dispatch."""
    task_id: str
    client_id: str
    project_id: str | None = None
    group_id: str | None = None
    client_name: str | None = None
    project_name: str | None = None
    source_urn: str = ""
    max_openrouter_tier: str = "FREE"

    # KB extraction results
    summary: str = ""
    entities: list[str] = Field(default_factory=list)
    suggested_actions: list[str] = Field(default_factory=list)
    urgency: str = ""
    action_type: str | None = ""
    estimated_complexity: str | None = ""
    is_assigned_to_me: bool = False
    has_future_deadline: bool = False
    suggested_deadline: str | None = None

    # Attachment metadata (from email)
    has_attachments: bool = False
    attachment_count: int = 0
    attachments: list[dict] = Field(default_factory=list)  # [{filename, contentType, size, index}]

    # KB extra fields
    suggested_agent: str | None = None
    affected_files: list[str] = Field(default_factory=list)
    related_kb_nodes: list[str] = Field(default_factory=list)

    # Recent chat topics for context
    chat_topics: list[dict[str, str]] = Field(default_factory=list)

    # Original content (from Kotlin as "content")
    content: str = ""

    # Active tasks for consolidation check
    active_tasks: list[dict[str, str | None]] = Field(default_factory=list)

    # Mention detection — set when @jervis is mentioned in comments
    mentions_jervis: bool = False


def _build_system_prompt(request: QualifyRequest) -> str:
    """Build system prompt for qualification agent with orchestrator context preparation."""
    topic_context = ""
    if request.chat_topics:
        topics_text = "\n".join(
            f"- {t.get('role', '?')}: {t.get('content', '')}"
            for t in request.chat_topics[-5:]
        )
        topic_context = f"""

## Active chat topics
The user recently discussed:
{topics_text}
If incoming data relates to an active chat topic, increase priority."""

    mention_context = ""
    if request.mentions_jervis:
        mention_context = """

## ⚠️ DIRECT @JERVIS MENTION
This content contains a direct @mention of Jervis in comments.
**MANDATORY**: Always choose QUEUED or URGENT_ALERT (NEVER DONE).
A direct mention means the user explicitly requests action from Jervis."""

    active_tasks_context = ""
    if request.active_tasks:
        tasks_text = "\n".join(
            f"- [{t.get('task_id', '?')[:8]}] {t.get('type', '?')} ({t.get('state', '?')}): {t.get('task_name', '')}"
            + (f" [topic: {t['topic_id']}]" if t.get('topic_id') else "")
            for t in request.active_tasks[:20]
        )
        active_tasks_context = f"""

## Active tasks (same client)
The system is already processing these tasks:
{tasks_text}

**IMPORTANT — consolidation**: If incoming data RELATES to an active task (same topic,
reply to same matter, new information for ongoing work), choose CONSOLIDATE and specify
the target task_id. Do not create duplicates — new information joins the existing task."""

    attachment_context = ""
    if request.has_attachments and request.attachments:
        att_lines = "\n".join(
            f"  - {a.get('filename', '?')} ({a.get('contentType', '?')}, {a.get('size', 0)} bytes)"
            for a in request.attachments
        )
        attachment_context = f"""

## Attachments ({request.attachment_count} files)
{att_lines}
Attachment text has been extracted and included in the KB summary.
If you need the original binary (e.g. to store as a document), use the appropriate tool."""

    return f"""You are the qualification agent for the Jervis AI assistant system.
You analyze ALL types of incoming content after KB indexing — emails, documents,
notifications, messages, scans, attachments — and decide what to do with them.

You are GENERIC: you handle invoices, job offers, medical reports, legal documents,
personal messages, system alerts, or anything else the same way — by analyzing the
content, checking KB for conventions and related items, and making a decision.

## LANGUAGE RULES
- All internal reasoning and prompt instructions are in English.
- All user-facing text (ALERT, REASON, CONTEXT, APPROACH, task titles) MUST be in the **same language as the input content**.
- Technical identifiers (source URNs, task IDs, field names) stay as-is.
- NEVER use internal MongoDB ObjectIds or system IDs as if they were real document numbers.
  Describe items by CONTENT (e.g., "invoice from company X", "email from Jan") — never by internal ID.

## MANDATORY: Check KB conventions FIRST
Before making any decision, ALWAYS call `kb_search` with TWO queries:

1. **Convention rules** — search for user-defined rules that apply to this content:
   `kb_search(query="pravidla konvence {source_type} {client_name}", kinds=["user_knowledge_convention"])`
   This returns only chunks of kind "user_knowledge_convention" — user-defined rules like
   "průzkumy od Samsung starší měsíce = zavírat" or "newsletter = auto-done".

2. **General context** — search for related knowledge:
   `kb_search(query="{summary of the content}")`
   This returns general KB evidence (emails, meetings, issues, notes) that
   might provide context for the decision.

Apply ALL convention rules that match. Convention rules OVERRIDE defaults.
If no conventions found, proceed with default rules below.

When the user tells you a new rule in chat (e.g. "průzkumy zavírej",
"newsletter ignoruj", "emaily od X jsou vždy urgent"), SAVE it:
`store_knowledge(content="Pravidlo: ...", kind="convention")` — this makes
the rule findable by future qualifier runs via the kinds=["convention"] filter.

## Urgency rules (default, overridden by KB conventions)
**ALWAYS URGENT_ALERT:**
- Documents with immediate or near-term payment deadlines (< 7 days)
- Direct questions/mentions addressed to the user (someone asks ME specifically)
- Deadlines within 48 hours
- Security incidents, production outages
- Slack/Teams messages that are direct questions to me

**ALWAYS QUEUED (USER_TASK):**
- Action items assigned to the user
- Requests for review, approval, feedback
- New issues/tickets assigned to user's projects
- Emails/messages requiring a response
- Documents requiring user decision (invoices to approve, contracts to sign, etc.)

**ALWAYS DONE:**
- Newsletters, marketing emails, automated notifications
- System logs, CI/CD notifications (unless failure)
- Items matching "do not monitor" conventions in KB
- Purely informational content with no action needed

## Input data
- **Source**: {request.source_urn}
- **Client**: {request.client_name or request.client_id}
- **Project**: {request.project_name or request.project_id or "—"}
- **KB summary**: {request.summary}
- **Entities**: {', '.join(request.entities) if request.entities else '—'}
- **Suggested actions**: {', '.join(request.suggested_actions) if request.suggested_actions else '—'}
- **Urgency**: {request.urgency or 'not specified'}
- **Action type**: {request.action_type or 'not specified'}
- **Complexity**: {request.estimated_complexity or 'not specified'}
- **Assigned to me**: {'yes' if request.is_assigned_to_me else 'no'}
- **Future deadline**: {'yes' if request.has_future_deadline else 'no'}{f' ({request.suggested_deadline})' if request.suggested_deadline else ''}
- **@Jervis mention**: {'⚠️ YES — direct mention' if request.mentions_jervis else 'no'}
{mention_context}{topic_context}{active_tasks_context}{attachment_context}

## Your task
1. **Check KB conventions** (`kb_search` with "conventions rules") — apply learned rules FIRST.
2. **Cross-source matching** (`kb_search`) — extract ALL identifiers from content (amounts, company names,
   invoice numbers, order IDs, ticket numbers, issue keys, branch names, PR numbers, dates, email addresses,
   project names, requirement IDs) and search KB for matching entities.
   - Bank statement → find matching invoice/order/subscription → link + mark as paid
   - Payment confirmation → find pending invoice → mark as resolved
   - Reply email → find original thread → consolidate
   - Status update (Jira, GitHub, GitLab) → find related task/issue → update context
   - Code review comment → find related PR/branch/issue → link
   - Requirement/specification → find related project/epic/ticket → link
   - Meeting notes → find related project/client/action items → link
   - Slack/Teams message → find related discussion/task/issue → link
   - ANY content with identifiable references → search KB for ALL of them
3. **Related tasks** — search for tasks related to the same entities/people/topics:
   `kb_search(query="{key entities from this content}")`
   Look for active or recently completed tasks about the SAME people, topics,
   or issues. If you find a related DONE task and the new content reopens the
   matter — decide REOPEN or recommend it to the user.
4. **If match found** — create relationship in KB (`store_knowledge`), update status, decide:
   - Invoice paid → DONE + store "invoice #X paid on date Y"
   - Partial match (amount differs, unknown sender) → QUEUED for user verification
5. **If no match** — this is genuinely new content. Analyze:
   - Who is affected, what urgency, relation to current work
   - Unknown payment/transaction → USER_TASK (user must verify)
6. **Check active tasks** — does incoming data relate to an already active task? If yes → CONSOLIDATE.
7. **Suggest approach** — 3-5 steps for the orchestrator (if QUEUED).
8. **Decide** — DONE/QUEUED/URGENT_ALERT/CONSOLIDATE/ESCALATE/DECOMPOSE + priority.

## Phase 3 — re-entrant decisions (use sparingly, only when justified)

**ESCALATE** — choose when YOU cannot decide without the user's judgment.
The task transitions to state=USER_TASK and surfaces in K reakci. Use only when:
- The content explicitly requires the user to make a business decision
  (e.g. "approve this contract?", "which option do you want?")
- The required information is genuinely missing and cannot be derived from KB
- A constraint is ambiguous and multiple legitimate interpretations exist
NEVER use ESCALATE just because the work is hard or you are unsure how to start —
the orchestrator can handle complex/uncertain work via QUEUED.

**DECOMPOSE** — choose when the work is multi-stage and benefits from parallel
or ordered sub-tasks (e.g. "audit + fix + add tests" or 3 independent fixes).
The Kotlin server creates child tasks, parent → BLOCKED until all children DONE,
then the parent re-enters qualification with the children's results. Use only
when the decomposition is concrete (you can name 2–6 specific sub-tasks).
NEVER use DECOMPOSE for trivial work or when one orchestrator pass would suffice.

## Client/Project name matching
When incoming data mentions a client or project name, ALWAYS search existing ones first.
Names are often abbreviated, misspelled, or in a different language. Never assume a new
client/project needs to be created — 99% of mentions refer to existing entities.

## Verification principle

Before deciding, search KB for context: related items, follow-ups, payments, resolutions.
Think critically — is the situation current and genuinely unresolved?

If content has a deadline, payment term, or requires user decision → QUEUED or URGENT_ALERT.
If content is purely informational with no action needed → DONE.

URGENT_ALERT when: deadline < 7 days, overdue payment, direct question requiring response,
or any situation where delay causes harm (financial, legal, reputational).

### Response format
Respond EXACTLY in this format (last message):

**If incoming data relates to an existing active task:**
```
DECISION: CONSOLIDATE
TARGET_TASK_ID: <task_id of existing task>
REASON: <why it belongs to the existing task>

CONTEXT:
- <stručný popis záležitosti v češtině pro uživatele — co se řeší, kdo je zapojen>
```

**If it requires orchestration (new task):**
```
DECISION: QUEUED
PRIORITY: <1-10>
ACTION_TYPE: <TECHNICAL|ANALYSIS|REVIEW|INFRASTRUCTURE|DOCUMENTATION|COMMUNICATION|ADMINISTRATIVE>
COMPLEXITY: <LOW|MEDIUM|HIGH|CRITICAL>
REASON: <brief justification>

CONTEXT:
- <stručný popis záležitosti v češtině pro uživatele — co se řeší, kdo je zapojen, o co jde>

APPROACH:
1. <first step for orchestrator>
2. <second step>
3. <third step>
```

**If it's just informational content (no action needed):**
```
DECISION: DONE
REASON: <brief justification>

CONTEXT:
- <stručný popis záležitosti v češtině pro uživatele>
```

**If urgent and user is online:**
```
DECISION: URGENT_ALERT
PRIORITY: <8-10>
ACTION_TYPE: <see above>
COMPLEXITY: <see above>
ALERT: <brief message for the user — in the user's language>
REASON: <brief justification>

CONTEXT:
- <stručný popis záležitosti v češtině pro uživatele>

APPROACH:
1. <steps>
```

**If you must escalate to the user (Phase 3):**
```
DECISION: ESCALATE
PRIORITY: <1-10>
REASON: <why the system cannot decide alone>
PENDING_USER_QUESTION: <the exact question to ask the user — in the user's language>
USER_QUESTION_CONTEXT: <what the user needs to know to answer>
```

**If the work decomposes into sub-tasks (Phase 3):**
```
DECISION: DECOMPOSE
PRIORITY: <1-10>
REASON: <why decomposition is needed>

SUB_TASKS:
- TASK_NAME: <short title>
  CONTENT: <what the child task must do>
  PHASE: <phase name, e.g. analysis|implementation|verification>
  ORDER: <0-based ordering inside the phase>
```

**If new evidence reopens a previously closed task (Phase 5):**
```
DECISION: REOPEN
TARGET_TASK_ID: <task_id of the DONE task to reopen>
PRIORITY: <1-10>
REASON: <why the closed task should reopen — in the user's language>

CONTEXT:
- <stručný popis záležitosti>
```

Be concise but thorough. Max {MAX_ITERATIONS} iterations (tool calls). Do not start unnecessary conversation — search KB, analyze, and decide."""


def _parse_decision(text: str) -> dict[str, Any]:
    """Parse the agent's decision from its final message.

    Parses single-line fields (DECISION, PRIORITY, etc.) and multi-line
    sections (CONTEXT, APPROACH) delimited by section headers.
    """
    result: dict[str, Any] = {
        "decision": "QUEUED",  # default fail-safe
        "priority_score": 50,  # default = middle of 0-100 scale
        "reason": "",
        "alert_message": None,
        "context_summary": "",
        "suggested_approach": "",
        "action_type": "",
        "estimated_complexity": "",
    }

    # Parse multi-line sections: CONTEXT, APPROACH, SUB_TASKS (Phase 3)
    section_headers = ("CONTEXT:", "APPROACH:", "SUB_TASKS:")
    single_line_prefixes = (
        "DECISION:", "PRIORITY:", "REASON:", "ALERT:",
        "ACTION_TYPE:", "COMPLEXITY:", "TARGET_TASK_ID:",
        "PENDING_USER_QUESTION:", "USER_QUESTION_CONTEXT:",
    )

    current_section: str | None = None
    section_lines: dict[str, list[str]] = {"CONTEXT": [], "APPROACH": [], "SUB_TASKS": []}

    for line in text.splitlines():
        stripped = line.strip()

        # Skip markdown code fences
        if stripped.startswith("```"):
            continue

        # Check if this line starts a new section
        if stripped in section_headers:
            current_section = stripped.rstrip(":")
            continue

        # Check if this is a single-line field (terminates current section)
        is_single_line = any(stripped.startswith(p) for p in single_line_prefixes)

        if is_single_line:
            current_section = None  # end any active multi-line section

            if stripped.startswith("DECISION:"):
                decision = stripped.split(":", 1)[1].strip().upper()
                if decision in ("QUEUED", "DONE", "URGENT_ALERT", "CONSOLIDATE", "ESCALATE", "DECOMPOSE", "REOPEN"):
                    result["decision"] = decision
            elif stripped.startswith("PRIORITY:"):
                try:
                    raw = int(stripped.split(":", 1)[1].strip())
                    # LLM outputs 1-10, but TaskDocument uses 0-100 scale.
                    result["priority_score"] = min(raw * 10, 100)
                except ValueError:
                    pass
            elif stripped.startswith("REASON:"):
                result["reason"] = stripped.split(":", 1)[1].strip()
            elif stripped.startswith("ALERT:"):
                result["alert_message"] = stripped.split(":", 1)[1].strip()
            elif stripped.startswith("ACTION_TYPE:"):
                result["action_type"] = stripped.split(":", 1)[1].strip()
            elif stripped.startswith("COMPLEXITY:"):
                result["estimated_complexity"] = stripped.split(":", 1)[1].strip()
            elif stripped.startswith("TARGET_TASK_ID:"):
                result["target_task_id"] = stripped.split(":", 1)[1].strip()
            elif stripped.startswith("PENDING_USER_QUESTION:"):
                result["pending_user_question"] = stripped.split(":", 1)[1].strip()
            elif stripped.startswith("USER_QUESTION_CONTEXT:"):
                result["user_question_context"] = stripped.split(":", 1)[1].strip()
        elif current_section and stripped:
            # Accumulate multi-line content under current section
            section_lines[current_section].append(stripped)

    # Join multi-line sections
    if section_lines["CONTEXT"]:
        result["context_summary"] = "\n".join(section_lines["CONTEXT"])
    if section_lines["APPROACH"]:
        result["suggested_approach"] = "\n".join(section_lines["APPROACH"])
    if section_lines["SUB_TASKS"]:
        result["sub_tasks"] = _parse_sub_tasks(section_lines["SUB_TASKS"])

    return result


def _parse_sub_tasks(lines: list[str]) -> list[dict[str, Any]]:
    """Parse SUB_TASKS section into a list of {task_name, content, phase, order_in_phase}.

    Format expected:
        - TASK_NAME: <name>
          CONTENT: <text>
          PHASE: <phase>
          ORDER: <int>
        - TASK_NAME: ...
    """
    sub_tasks: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    for line in lines:
        s = line.strip().lstrip("-").strip()
        if not s:
            continue
        if s.startswith("TASK_NAME:"):
            if current and current.get("task_name"):
                sub_tasks.append(current)
            current = {"task_name": s.split(":", 1)[1].strip(), "content": "", "phase": None, "order_in_phase": 0}
        elif current is None:
            continue
        elif s.startswith("CONTENT:"):
            current["content"] = s.split(":", 1)[1].strip()
        elif s.startswith("PHASE:"):
            current["phase"] = s.split(":", 1)[1].strip() or None
        elif s.startswith("ORDER:"):
            try:
                current["order_in_phase"] = int(s.split(":", 1)[1].strip())
            except ValueError:
                pass
    if current and current.get("task_name"):
        sub_tasks.append(current)
    return sub_tasks


async def handle_qualification(request: QualifyRequest) -> dict[str, Any]:
    """Run qualification agent — LLM with CORE tools, max 5 iterations.

    Returns dict with:
        decision, priority_score, reason, alert_message,
        context_summary, suggested_approach, action_type, estimated_complexity.
    """
    from app.tools.definitions import ALL_RESPOND_TOOLS_FULL

    # Get CORE tools only
    tools = get_tools(ToolTier.CORE, ALL_RESPOND_TOOLS_FULL)

    system_prompt = _build_system_prompt(request)

    user_content = f"Analyzuj a rozhodni o následujícím obsahu:\n\n{request.content}"

    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_content},
    ]

    # Route to extraction model (8b on GPU-2) — frees GPU-1 for orchestrator/chat
    estimated_tokens_count = estimate_tokens(system_prompt) + estimate_tokens(request.content) + 500
    route = await route_request(
        capability="extraction",
        estimated_tokens=estimated_tokens_count,
        max_tier="NONE",
    )

    model_override = route.model
    api_base_override = route.api_base

    for iteration in range(MAX_ITERATIONS):
        logger.info(
            "QUALIFY_ITERATION | task=%s | iter=%d/%d",
            request.task_id, iteration + 1, MAX_ITERATIONS,
        )

        # Call LLM
        response = await llm_provider.completion(
            messages=messages,
            tools=tools if iteration < MAX_ITERATIONS - 1 else [],  # no tools on last iteration
            model_override=model_override,
            api_base_override=api_base_override,
            api_key_override=route.api_key,
        )

        assistant_msg = response.choices[0].message

        # Check for tool calls
        if assistant_msg.tool_calls:
            messages.append(assistant_msg.model_dump())

            for tool_call in assistant_msg.tool_calls:
                tool_name = tool_call.function.name
                try:
                    import json as _json
                    tool_args = _json.loads(tool_call.function.arguments)
                except Exception:
                    tool_args = {}

                logger.info(
                    "QUALIFY_TOOL | task=%s | tool=%s",
                    request.task_id, tool_name,
                )

                # Execute tool
                try:
                    tool_result = await execute_tool(
                        tool_name=tool_name,
                        arguments=tool_args,
                        client_id=request.client_id,
                        project_id=request.project_id,
                        group_id=request.group_id,
                        processing_mode="QUALIFICATION",
                        skip_approval=True,
                    )
                except Exception as e:
                    tool_result = f"Error: {e}"

                messages.append({
                    "role": "tool",
                    "tool_call_id": tool_call.id,
                    "content": str(tool_result),
                })

            continue

        # No tool calls — agent is done, parse decision
        content = assistant_msg.content or ""
        messages.append({"role": "assistant", "content": content})

        decision = _parse_decision(content)
        logger.info(
            "QUALIFY_DECISION | task=%s | decision=%s | priority=%s | reason=%s",
            request.task_id, decision["decision"], decision.get("priority_score"), decision.get("reason", "")[:100],
        )

        # Score attachment relevance (if task has attachments)
        attachment_results = await _score_attachment_relevance(request, decision)
        if attachment_results:
            decision["attachments"] = attachment_results

        await _record_incoming_vertex(request, decision)
        return decision

    # Max iterations reached — default to QUEUED
    logger.warning("QUALIFY_MAX_ITERATIONS | task=%s — defaulting to QUEUED", request.task_id)
    last_content = messages[-1].get("content", "") if messages else ""
    decision = _parse_decision(last_content) if "DECISION:" in last_content else {
        "decision": "QUEUED",
        "priority_score": 5,
        "reason": "Max iterations reached",
        "context_summary": "",
        "suggested_approach": "",
        "action_type": "",
        "estimated_complexity": "",
    }

    # Score attachment relevance (if task has attachments)
    attachment_results = await _score_attachment_relevance(request, decision)
    if attachment_results:
        decision["attachments"] = attachment_results

    await _record_incoming_vertex(request, decision)
    return decision


async def _score_attachment_relevance(request: QualifyRequest, decision: dict) -> list[dict]:
    """Score attachment relevance using LLM and upload high-scoring ones to KB.

    Reads extract records from MongoDB (attachment_extracts collection),
    scores each with the qualification LLM, and uploads relevant ones
    (score >= 0.7) to KB via the register endpoint.

    Returns list of attachment assessment dicts for qualifierPreparedContext.
    """
    if not request.has_attachments:
        return []

    from app.tools.kotlin_client import get_mongo_db

    try:
        db = await get_mongo_db()
        extracts = await db.attachment_extracts.find({
            "taskId": request.task_id,
            "tikaStatus": "SUCCESS",
        }).to_list(length=50)

        if not extracts:
            logger.info("QUALIFY_ATTACHMENTS | task=%s | no SUCCESS extracts found", request.task_id)
            return []

        logger.info(
            "QUALIFY_ATTACHMENTS | task=%s | scoring %d extracts",
            request.task_id, len(extracts),
        )

        task_context = decision.get("context_summary", request.summary) or request.content
        results = []

        for extract in extracts:
            filename = extract.get("filename", "unknown")
            extracted_text = extract.get("extractedText", "")
            if not extracted_text:
                continue

            # Score relevance using a simple structured prompt (non-reasoning model)
            prompt = (
                f"Úkol: {request.content}\n"
                f"Kontext: {task_context}\n\n"
                f"Obsah přílohy '{filename}':\n"
                f"{extracted_text}\n\n"
                f"Je tato příloha relevantní pro splnění úkolu?\n"
                f"Odpověz JSON: {{\"relevant\": true/false, \"score\": 0.0-1.0, \"reason\": \"...\"}}"
            )

            try:
                route = await route_request(
                    capability="extraction",
                    estimated_tokens=estimate_tokens(prompt) + 200,
                    max_tier="NONE",
                )

                response = await llm_provider.completion(
                    messages=[
                        {"role": "system", "content": "Odpovídej pouze platným JSON."},
                        {"role": "user", "content": prompt},
                    ],
                    tools=[],
                    model_override=route.model,
                    api_base_override=route.api_base,
                    api_key_override=route.api_key,
                )

                import json as _json
                content = response.choices[0].message.content or "{}"
                # Strip markdown code fences if present
                content = content.strip()
                if content.startswith("```"):
                    content = "\n".join(content.split("\n")[1:])
                if content.endswith("```"):
                    content = "\n".join(content.split("\n")[:-1])
                content = content.strip()

                scored = _json.loads(content)
                score = float(scored.get("score", 0.0))
                reason = scored.get("reason", "")

                # Update extract record
                update_fields = {
                    "relevanceScore": score,
                    "relevanceReason": reason,
                    "updatedAt": __import__("datetime").datetime.now(__import__("datetime").timezone.utc),
                }

                # Upload to KB if relevant (score >= 0.7)
                if score >= 0.7:
                    try:
                        import httpx
                        from app.config import settings as app_settings

                        kb_url = getattr(app_settings, "knowledgebase_write_url", None) or \
                                 getattr(app_settings, "kb_write_url", None)

                        if kb_url and extract.get("filePath"):
                            # Register pre-stored attachment with KB
                            async with httpx.AsyncClient(timeout=120) as client:
                                resp = await client.post(
                                    f"{kb_url}/api/v1/documents/register",
                                    json={
                                        "clientId": request.client_id,
                                        "projectId": request.project_id or "",
                                        "filename": filename,
                                        "mimeType": extract.get("mimeType", "application/octet-stream"),
                                        "sizeBytes": 0,
                                        "storagePath": extract["filePath"],
                                        "title": f"Attachment: {filename}",
                                        "description": reason,
                                        "category": "OTHER",
                                        "tags": ["email-attachment", "qualifier-approved"],
                                    },
                                )
                                if resp.status_code == 200:
                                    kb_doc = resp.json()
                                    update_fields["kbUploaded"] = True
                                    update_fields["kbDocId"] = kb_doc.get("id", "")
                                    logger.info(
                                        "QUALIFY_ATTACHMENT_UPLOADED | task=%s | file=%s | score=%.2f | kbDocId=%s",
                                        request.task_id, filename, score, kb_doc.get("id", ""),
                                    )
                    except Exception as e:
                        logger.warning("Failed to upload attachment %s to KB: %s", filename, e)

                await db.attachment_extracts.update_one(
                    {"_id": extract["_id"]},
                    {"$set": update_fields},
                )

                results.append({
                    "filename": filename,
                    "kbDocId": update_fields.get("kbDocId"),
                    "relevanceScore": score,
                    "relevanceReason": reason,
                })

                logger.info(
                    "QUALIFY_ATTACHMENT_SCORED | task=%s | file=%s | score=%.2f | reason=%s",
                    request.task_id, filename, score, reason[:100],
                )

            except Exception as e:
                logger.warning("Failed to score attachment %s: %s", filename, e)
                continue

        return results

    except Exception as e:
        logger.warning("Failed to score attachments for task %s: %s", request.task_id, e)
        return []


async def _record_incoming_vertex(request: QualifyRequest, decision: dict) -> None:
    """Record an INCOMING vertex in Paměťový graf for qualified items."""
    if decision.get("decision") == "DONE":
        return  # No vertex for items that don't need processing

    try:
        from app.agent.persistence import agent_store
        from app.agent.graph import add_incoming_vertex

        memory_graph = await agent_store.get_or_create_memory_graph()
        add_incoming_vertex(
            memory_graph,
            task_id=request.task_id,
            title=decision.get("context_summary", request.summary)[:80] or request.task_id,
            prepared_context=decision.get("suggested_approach", ""),
            client_id=request.client_id,
            client_name=request.client_name or "",
            project_id=request.project_id,
            project_name=request.project_name or "",
            urgency=request.urgency or "normal",
        )
        agent_store.mark_dirty(memory_graph.task_id)
    except Exception as e:
        logger.warning("Failed to record INCOMING vertex for task %s: %s", request.task_id, e)
