"""Plan Delegations node — LLM-driven agent selection and DAG construction.

Reads the evidence pack, session memory, and procedural memory to decide
which specialist agents to invoke and in what order. Produces an
ExecutionPlan with parallel groups.
"""

from __future__ import annotations

import json
import logging
import uuid

from app.agents.registry import AgentRegistry
from app.config import settings
from app.context.procedural_memory import procedural_memory
from app.context.session_memory import session_memory_store
from app.graph.nodes._helpers import llm_with_cloud_fallback, parse_json_response
from app.models import (
    CodingTask,
    DelegationMessage,
    DelegationState,
    DelegationStatus,
    DomainType,
    ExecutionPlan,
    SessionEntry,
)
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)


async def plan_delegations(state: dict) -> dict:
    """Analyse the task and produce an execution plan of agent delegations.

    State reads:
        task, evidence_pack, rules, chat_history, response_language
    State writes:
        execution_plan, delegation_states, domain, session_memory
    """
    task = CodingTask(**state["task"])
    evidence = state.get("evidence_pack", {})
    response_language = state.get("response_language", "en")

    # --- Progress ---
    await _report(task, "Planning delegations — analysing task and selecting agents…", 30)

    # --- Load session memory ---
    session_payload = await session_memory_store.load(
        client_id=task.client_id,
        project_id=task.project_id,
    )
    session_entries = [e.model_dump() for e in session_payload.entries[-20:]]

    # --- Check procedural memory ---
    procedure = None
    if settings.use_procedural_memory:
        # Detect trigger pattern from the task category / domain
        trigger = _detect_trigger(state)
        if trigger:
            procedure = await procedural_memory.find_procedure(
                trigger_pattern=trigger,
                client_id=task.client_id,
            )
            if procedure:
                logger.info(
                    "Found procedure for trigger=%s (success_rate=%.2f, usage=%d)",
                    trigger, procedure.success_rate, procedure.usage_count,
                )

    # --- Build the LLM planning prompt ---
    registry = AgentRegistry.instance()
    capabilities = registry.get_capability_summary()

    system_prompt = _build_system_prompt(capabilities)
    user_prompt = _build_user_prompt(
        task=task,
        evidence=evidence,
        session_entries=session_entries,
        procedure=procedure,
        response_language=response_language,
    )

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]

    # --- LLM call ---
    response = await llm_with_cloud_fallback(
        state=state,
        messages=messages,
        task_type="planning",
        max_tokens=4096,
    )
    content = response.choices[0].message.content or ""
    plan_data = parse_json_response(content)

    if not plan_data or "delegations" not in plan_data:
        logger.warning("Failed to parse delegation plan, falling back to legacy")
        return _fallback_plan(task, response_language)

    # --- Build ExecutionPlan ---
    delegations: list[DelegationMessage] = []
    delegation_states: dict[str, dict] = {}

    for i, d in enumerate(plan_data.get("delegations", [])):
        did = f"d-{task.id}-{i}-{uuid.uuid4().hex[:6]}"
        msg = DelegationMessage(
            delegation_id=did,
            depth=0,
            agent_name=d.get("agent", "legacy"),
            task_summary=d.get("task", ""),
            context=d.get("context", ""),
            constraints=d.get("constraints", []),
            expected_output=d.get("expected_output", ""),
            response_language=response_language,
            client_id=task.client_id,
            project_id=task.project_id,
        )
        delegations.append(msg)
        delegation_states[did] = DelegationState(
            delegation_id=did,
            agent_name=msg.agent_name,
            status=DelegationStatus.PENDING,
        ).model_dump()

    # Parse parallel groups (list of lists of indices → delegation IDs)
    parallel_groups: list[list[str]] = []
    raw_groups = plan_data.get("parallel_groups", [])
    if raw_groups:
        for group in raw_groups:
            ids = []
            for idx in group:
                if isinstance(idx, int) and idx < len(delegations):
                    ids.append(delegations[idx].delegation_id)
            if ids:
                parallel_groups.append(ids)
    else:
        # Default: all sequential (each delegation in its own group)
        parallel_groups = [[d.delegation_id] for d in delegations]

    detected_domain = plan_data.get("domain", "code")
    try:
        domain = DomainType(detected_domain)
    except ValueError:
        domain = DomainType.CODE

    plan = ExecutionPlan(
        delegations=delegations,
        parallel_groups=parallel_groups,
        domain=domain,
    )

    await _report(
        task,
        f"Plan: {len(delegations)} delegation(s) across {len(parallel_groups)} group(s) [{domain.value}]",
        35,
    )

    return {
        "execution_plan": plan.model_dump(),
        "delegation_states": delegation_states,
        "domain": domain.value,
        "session_memory": session_entries,
        "response_language": response_language,
    }


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _detect_trigger(state: dict) -> str | None:
    """Heuristic: detect a procedural memory trigger from the task."""
    category = state.get("task_category", "")
    action = state.get("task_action", "")
    query = state.get("task", {}).get("query", "").lower()

    # Source-based triggers (email, issue, etc.)
    source_type = state.get("source_type", "")
    if source_type:
        return f"{source_type}_indexed"

    # Category-based
    if category == "advice":
        return "advice_query"
    if category == "epic":
        return "epic_execution"
    if "review" in query or "code review" in query:
        return "code_review"
    if "deploy" in query:
        return "deployment"
    if action == "code":
        return "coding_task"
    return None


def _build_system_prompt(capabilities: str) -> str:
    return f"""You are the Orchestrator Planner. Your job is to analyse a task and decide which specialist agents to invoke.

{capabilities}

Respond with a JSON object:
{{
  "domain": "<DomainType value>",
  "delegations": [
    {{
      "agent": "<agent name>",
      "task": "<what this agent should do>",
      "context": "<relevant context to pass>",
      "constraints": ["<constraint>"],
      "expected_output": "<what we expect back>"
    }}
  ],
  "parallel_groups": [[0], [1, 2], [3]]
}}

Rules:
- parallel_groups contains lists of delegation indices that can run concurrently
- Groups execute sequentially: group[0] finishes before group[1] starts
- Within a group, delegations run in parallel
- Use the minimum number of agents needed
- Prefer specific agents over the legacy fallback
- If the task is simple advice, use just one "research" or "legacy" agent"""


def _build_user_prompt(
    task: CodingTask,
    evidence: dict,
    session_entries: list[dict],
    procedure,
    response_language: str,
) -> str:
    parts = [
        f"## Task\nQuery: {task.query}",
        f"Client: {task.client_id}, Project: {task.project_id or 'none'}",
        f"Response language: {response_language}",
    ]

    if evidence:
        kb_results = evidence.get("kb_results", [])
        if kb_results:
            parts.append(f"\n## KB Evidence ({len(kb_results)} results)")
            for r in kb_results[:5]:
                parts.append(f"- {r.get('summary', r.get('title', 'untitled'))}")

        facts = evidence.get("facts", [])
        if facts:
            parts.append(f"\n## Facts\n" + "\n".join(f"- {f}" for f in facts[:10]))

    if session_entries:
        parts.append("\n## Recent Session Memory")
        for e in session_entries[-5:]:
            parts.append(f"- [{e.get('source', '?')}] {e.get('summary', '')}")

    if procedure:
        parts.append(f"\n## Known Procedure (trigger={procedure.trigger_pattern})")
        for step in procedure.procedure_steps:
            parts.append(f"- {step.agent}: {step.action}")
        parts.append(f"Success rate: {procedure.success_rate:.0%}, Used {procedure.usage_count}×")

    return "\n".join(parts)


def _fallback_plan(task: CodingTask, response_language: str) -> dict:
    """Produce a minimal single-agent fallback plan."""
    did = f"d-{task.id}-fallback-{uuid.uuid4().hex[:6]}"
    msg = DelegationMessage(
        delegation_id=did,
        depth=0,
        agent_name="legacy",
        task_summary=task.query,
        response_language=response_language,
        client_id=task.client_id,
        project_id=task.project_id,
    )
    plan = ExecutionPlan(
        delegations=[msg],
        parallel_groups=[[did]],
        domain=DomainType.CODE,
    )
    return {
        "execution_plan": plan.model_dump(),
        "delegation_states": {
            did: DelegationState(
                delegation_id=did,
                agent_name="legacy",
            ).model_dump(),
        },
        "domain": DomainType.CODE.value,
        "session_memory": [],
        "response_language": response_language,
    }


async def _report(task: CodingTask, message: str, percent: int) -> None:
    """Send progress report to Kotlin server."""
    try:
        await kotlin_client.report_progress(
            task_id=task.id,
            client_id=task.client_id,
            node="plan_delegations",
            message=message,
            percent=percent,
        )
    except Exception:
        pass  # Non-critical
