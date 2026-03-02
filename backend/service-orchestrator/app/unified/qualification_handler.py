"""Qualification Agent handler.

Runs an LLM agent with CORE tools to analyze KB results and make routing decisions.
Called from /qualify endpoint. Fire-and-forget — reports result to Kotlin via callback.

Flow:
1. Receive KB analysis context (summary, entities, urgency, suggested_actions)
2. Build system prompt for qualification
3. Run LLM with CORE tools (kb_search, web_search, memory)
4. Agent decides: DONE, READY_FOR_GPU, or URGENT_ALERT
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
    """Build system prompt for qualification agent."""
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

    return f"""Jsi kvalifikační agent systému Jervis. Analyzuješ příchozí data po KB indexaci a rozhoduješ, zda vyžadují orchestraci (READY_FOR_GPU) nebo jsou jen informační (DONE).

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
1. **Prohledej KB** (`kb_search`) — co už víme o tomto tématu? Existuje kontext, který pomůže rozhodnout?
2. **Zvaž urgenci** — termíny, přiřazení, klíčová slova, aktivní chat téma
3. **Rozhodni**:

### Rozhodnutí
Odpověz PŘESNĚ v jednom z těchto formátů (poslední zpráva):

**Pokud vyžaduje orchestraci (analýza, coding, odpověď):**
```
DECISION: READY_FOR_GPU
PRIORITY: <1-10>
REASON: <stručné zdůvodnění>
```

**Pokud je to jen informační obsah (nevyžaduje akci):**
```
DECISION: DONE
REASON: <stručné zdůvodnění>
```

**Pokud je urgentní a uživatel je online:**
```
DECISION: URGENT_ALERT
ALERT: <stručná zpráva pro uživatele>
REASON: <stručné zdůvodnění>
```

Buď stručný. Max {MAX_ITERATIONS} iterací (tool calls). Nezačínej zbytečnou konverzaci — analyzuj a rozhodni."""


def _parse_decision(text: str) -> dict[str, Any]:
    """Parse the agent's decision from its final message."""
    result: dict[str, Any] = {
        "decision": "READY_FOR_GPU",  # default fail-safe
        "priority_score": 5,
        "reason": "",
        "alert_message": None,
    }

    for line in text.splitlines():
        line = line.strip()
        if line.startswith("DECISION:"):
            decision = line.split(":", 1)[1].strip().upper()
            if decision in ("READY_FOR_GPU", "DONE", "URGENT_ALERT"):
                result["decision"] = decision
        elif line.startswith("PRIORITY:"):
            try:
                result["priority_score"] = int(line.split(":", 1)[1].strip())
            except ValueError:
                pass
        elif line.startswith("REASON:"):
            result["reason"] = line.split(":", 1)[1].strip()
        elif line.startswith("ALERT:"):
            result["alert_message"] = line.split(":", 1)[1].strip()

    return result


async def handle_qualification(request: QualifyRequest) -> dict[str, Any]:
    """Run qualification agent — LLM with CORE tools, max 5 iterations.

    Returns dict with decision, priority_score, reason.
    """
    from app.tools.definitions import ALL_RESPOND_TOOLS_FULL

    # Get CORE tools only
    tools = get_tools(ToolTier.CORE, ALL_RESPOND_TOOLS_FULL)

    system_prompt = _build_system_prompt(request)
    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"Analyzuj a rozhodni o následujícím obsahu:\n\n{request.content[:2000]}"},
    ]

    # Route to appropriate backend
    estimated_tokens_count = estimate_tokens(system_prompt) + estimate_tokens(request.content[:2000]) + 500
    route = await route_request(
        estimated_tokens=estimated_tokens_count,
        use_case="qualification",
        max_openrouter_tier=request.max_openrouter_tier,
        prefer_cloud=False,  # qualification prefers GPU
    )

    model_override = route.get("model") if route else None
    api_base_override = route.get("api_base") if route else None

    for iteration in range(MAX_ITERATIONS):
        logger.info(
            "QUALIFY_ITERATION | task=%s | iter=%d/%d",
            request.task_id, iteration + 1, MAX_ITERATIONS,
        )

        # Call LLM
        response = await llm_provider.complete(
            messages=messages,
            tools=tools if iteration < MAX_ITERATIONS - 1 else [],  # no tools on last iteration
            model_override=model_override,
            api_base_override=api_base_override,
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

    # Max iterations reached — default to READY_FOR_GPU
    logger.warning("QUALIFY_MAX_ITERATIONS | task=%s — defaulting to READY_FOR_GPU", request.task_id)
    last_content = messages[-1].get("content", "") if messages else ""
    return _parse_decision(last_content) if "DECISION:" in last_content else {
        "decision": "READY_FOR_GPU",
        "priority_score": 5,
        "reason": "Max iterations reached",
    }
