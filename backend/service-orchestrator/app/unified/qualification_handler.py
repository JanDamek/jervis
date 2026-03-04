"""Qualification Agent handler.

Runs an LLM agent with CORE tools to analyze KB results and prepare context for orchestrator.
Called from /qualify endpoint. Fire-and-forget — reports result to Kotlin via callback.

Flow:
1. Receive KB analysis context (summary, entities, urgency, suggested_actions)
2. Build system prompt for qualification + context preparation
3. Run LLM with CORE tools (kb_search, web_search, memory)
4. Agent decides: DONE, QUEUED, or URGENT_ALERT + prepares orchestrator context
5. POST result to Kotlin /internal/qualification-done
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
    action_type: str = ""
    estimated_complexity: str = ""
    is_assigned_to_me: bool = False
    has_future_deadline: bool = False
    suggested_deadline: str | None = None

    # KB extra fields
    suggested_agent: str | None = None
    affected_files: list[str] = Field(default_factory=list)
    related_kb_nodes: list[str] = Field(default_factory=list)

    # Recent chat topics for context
    chat_topics: list[dict[str, str]] = Field(default_factory=list)

    # Original content (from Kotlin as "content")
    content: str = ""


def _build_system_prompt(request: QualifyRequest) -> str:
    """Build system prompt for qualification agent with orchestrator context preparation."""
    topic_context = ""
    if request.chat_topics:
        topics_text = "\n".join(
            f"- {t.get('role', '?')}: {t.get('content', '')}"
            for t in request.chat_topics[-5:]
        )
        topic_context = f"""

## Aktivní témata v chatu
Uživatel v chatu nedávno řešil:
{topics_text}
Pokud příchozí data souvisí s aktivním tématem, zvyš prioritu."""

    return f"""Jsi kvalifikační agent systému Jervis. Analyzuješ příchozí data po KB indexaci a připravuješ kontext pro orchestrátor — nejen rozhoduješ, ale i shrnuješ co víš a navrhuješ postup.

## Vstupní data
- **Zdroj**: {request.source_urn}
- **Klient**: {request.client_name or request.client_id}
- **Projekt**: {request.project_name or request.project_id or "—"}
- **Shrnutí KB**: {request.summary}
- **Entity**: {', '.join(request.entities) if request.entities else '—'}
- **Navrhované akce**: {', '.join(request.suggested_actions) if request.suggested_actions else '—'}
- **Urgence**: {request.urgency or 'neuvedeno'}
- **Typ akce**: {request.action_type or 'neuvedeno'}
- **Složitost**: {request.estimated_complexity or 'neuvedeno'}
- **Přiřazeno mně**: {'ano' if request.is_assigned_to_me else 'ne'}
- **Budoucí termín**: {'ano' if request.has_future_deadline else 'ne'}{f' ({request.suggested_deadline})' if request.suggested_deadline else ''}
{topic_context}

## Tvůj úkol
1. **Prohledej KB** (`kb_search`) — co už víme o tomto tématu? Související issues, commity, kontext.
2. **Analyzuj kontext** — kdo je ovlivněn, jaká urgence, souvislost s aktuální prací.
3. **Navrhni postup** — 3-5 kroků jak by měl orchestrátor postupovat (pokud QUEUED).
4. **Rozhodni** — DONE/QUEUED/URGENT_ALERT + priorita.

### Formát odpovědi
Odpověz PŘESNĚ v tomto formátu (poslední zpráva):

**Pokud vyžaduje orchestraci:**
```
DECISION: QUEUED
PRIORITY: <1-10>
ACTION_TYPE: <CODE_FIX|CODE_FEATURE|ANALYSIS|REVIEW|DEVOPS|DOCUMENTATION|COMMUNICATION>
COMPLEXITY: <LOW|MEDIUM|HIGH|CRITICAL>
REASON: <stručné zdůvodnění>

CONTEXT:
- <co KB obsahuje k tématu>
- <související práce, commity, issues>
- <kdo je ovlivněn, jaký dopad>

APPROACH:
1. <první krok pro orchestrátor>
2. <druhý krok>
3. <třetí krok>
4. <případně další kroky>
```

**Pokud je to jen informační obsah (nevyžaduje akci):**
```
DECISION: DONE
REASON: <stručné zdůvodnění>

CONTEXT:
- <co KB obsahuje k tématu>
```

**Pokud je urgentní a uživatel je online:**
```
DECISION: URGENT_ALERT
PRIORITY: <8-10>
ACTION_TYPE: <viz výše>
COMPLEXITY: <viz výše>
ALERT: <stručná zpráva pro uživatele>
REASON: <stručné zdůvodnění>

CONTEXT:
- <kontext>

APPROACH:
1. <kroky>
```

Buď stručný ale důkladný. Max {MAX_ITERATIONS} iterací (tool calls). Nezačínej zbytečnou konverzaci — prohledej KB, analyzuj a rozhodni."""


def _parse_decision(text: str) -> dict[str, Any]:
    """Parse the agent's decision from its final message.

    Parses single-line fields (DECISION, PRIORITY, etc.) and multi-line
    sections (CONTEXT, APPROACH) delimited by section headers.
    """
    result: dict[str, Any] = {
        "decision": "QUEUED",  # default fail-safe
        "priority_score": 5,
        "reason": "",
        "alert_message": None,
        "context_summary": "",
        "suggested_approach": "",
        "action_type": "",
        "estimated_complexity": "",
    }

    # Parse multi-line sections: CONTEXT and APPROACH
    # Each section starts with its header and ends at the next section header or end of text
    section_headers = ("CONTEXT:", "APPROACH:")
    single_line_prefixes = ("DECISION:", "PRIORITY:", "REASON:", "ALERT:", "ACTION_TYPE:", "COMPLEXITY:")

    current_section: str | None = None
    section_lines: dict[str, list[str]] = {"CONTEXT": [], "APPROACH": []}

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
                if decision in ("QUEUED", "DONE", "URGENT_ALERT"):
                    result["decision"] = decision
            elif stripped.startswith("PRIORITY:"):
                try:
                    result["priority_score"] = int(stripped.split(":", 1)[1].strip())
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
        elif current_section and stripped:
            # Accumulate multi-line content under current section
            section_lines[current_section].append(stripped)

    # Join multi-line sections
    if section_lines["CONTEXT"]:
        result["context_summary"] = "\n".join(section_lines["CONTEXT"])
    if section_lines["APPROACH"]:
        result["suggested_approach"] = "\n".join(section_lines["APPROACH"])

    return result


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
    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"Analyzuj a rozhodni o následujícím obsahu:\n\n{request.content[:4000]}"},
    ]

    # Route to appropriate backend — qualification always GPU only
    estimated_tokens_count = estimate_tokens(system_prompt) + estimate_tokens(request.content[:4000]) + 500
    route = await route_request(
        capability="thinking",
        estimated_tokens=estimated_tokens_count,
        max_tier="NONE",  # hardcoded — qualification always on local GPU
        prefer_cloud=False,
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
                    "content": str(tool_result)[:4000],
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
        return decision

    # Max iterations reached — default to QUEUED
    logger.warning("QUALIFY_MAX_ITERATIONS | task=%s — defaulting to QUEUED", request.task_id)
    last_content = messages[-1].get("content", "") if messages else ""
    return _parse_decision(last_content) if "DECISION:" in last_content else {
        "decision": "QUEUED",
        "priority_score": 5,
        "reason": "Max iterations reached",
        "context_summary": "",
        "suggested_approach": "",
        "action_type": "",
        "estimated_complexity": "",
    }
