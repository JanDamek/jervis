"""plan_delegations node — LLM-driven agent selection and DAG construction.

Takes the evidence pack + session/procedural memory, asks the LLM to select
which agents to delegate to and in what order. Outputs an ExecutionPlan.

This is the "brain" of the new delegation system:
1. Reads Session Memory (recent decisions for this client/project)
2. Reads Procedural Memory (learned workflows for similar tasks)
3. Gets agent capabilities from AgentRegistry
4. Asks LLM to decompose task into delegations
5. Builds ExecutionPlan with parallel groups
"""

from __future__ import annotations

import json
import logging
import uuid

from app.agents.registry import AgentRegistry
from app.config import settings
from app.graph.nodes._helpers import llm_with_cloud_fallback
from app.models import (
    CodingTask,
    DelegationMessage,
    DomainType,
    ExecutionPlan,
    ModelTier,
)

logger = logging.getLogger(__name__)


async def plan_delegations(state: dict) -> dict:
    """Plan which agents to delegate to and build execution DAG.

    Input state:
        task, evidence_pack, response_language, domain, session_memory, rules

    Output state:
        execution_plan, delegation_states
    """
    task = CodingTask(**state["task"])
    evidence = state.get("evidence_pack", {})
    response_language = state.get("response_language", "en")
    domain = state.get("domain", "code")
    session_memory = state.get("session_memory", [])
    rules = state.get("rules", {})

    # Get agent capabilities
    registry = AgentRegistry.instance()
    capabilities_text = registry.get_capability_summary()

    # Check Procedural Memory for learned workflow
    procedure_context = ""
    if settings.use_procedural_memory:
        try:
            from app.context.procedural_memory import procedural_memory
            procedure = await procedural_memory.find_procedure(
                trigger_pattern=_classify_trigger(task.query, domain),
                client_id=task.client_id,
            )
            if procedure:
                steps_text = "\n".join(
                    f"  {i+1}. {s.agent}: {s.action}"
                    for i, s in enumerate(procedure.procedure_steps)
                )
                procedure_context = (
                    f"\n## Learned Procedure (success rate: {procedure.success_rate:.0%})\n"
                    f"Trigger: {procedure.trigger_pattern}\n"
                    f"Steps:\n{steps_text}\n"
                    f"Source: {procedure.source}\n"
                    f"\nConsider following this learned workflow, but adapt as needed."
                )
        except Exception as e:
            logger.debug("Procedural memory lookup failed: %s", e)

    # Session Memory context
    session_context = ""
    if session_memory:
        entries_text = "\n".join(
            f"- [{e.get('source', '?')}] {e.get('summary', '')}"
            for e in session_memory[-10:]  # Last 10 entries
        )
        session_context = f"\n## Recent Decisions (Session Memory)\n{entries_text}\n"

    # Evidence summary
    evidence_context = ""
    if evidence:
        kb_count = len(evidence.get("kb_results", []))
        facts = evidence.get("facts", [])
        evidence_context = f"\n## Evidence\n- KB results: {kb_count}\n"
        if facts:
            evidence_context += "- Key facts:\n" + "\n".join(f"  - {f}" for f in facts[:5])

    # Constraints from rules
    constraints_text = ""
    if rules:
        forbidden = rules.get("forbidden_files", [])
        if forbidden:
            constraints_text += f"\nForbidden files: {', '.join(forbidden)}"
        max_files = rules.get("max_changed_files", 20)
        constraints_text += f"\nMax changed files: {max_files}"

    # Build planning prompt
    system_prompt = (
        "You are the Jervis Orchestrator planning engine. Your job is to decompose "
        "a user request into a list of agent delegations.\n\n"
        f"{capabilities_text}\n"
        "Rules:\n"
        "- Select the MINIMUM number of agents needed\n"
        "- Group independent delegations for parallel execution\n"
        "- Sequential dependencies must be in separate groups\n"
        "- Each delegation has: agent_name, task_summary, expected_output\n"
        "- For simple queries that need only one agent, create a single delegation\n"
        "- Always respond in valid JSON\n"
        f"{procedure_context}"
        f"{session_context}"
        f"{evidence_context}"
    )

    user_prompt = (
        f"User request: {task.query}\n"
        f"Domain: {domain}\n"
        f"Client: {task.client_name or task.client_id}\n"
        f"Project: {task.project_name or task.project_id or 'N/A'}\n"
        f"{constraints_text}\n\n"
        "Respond with JSON:\n"
        "```json\n"
        "{\n"
        '  "delegations": [\n'
        '    {"agent_name": "...", "task_summary": "...", "expected_output": "..."}\n'
        "  ],\n"
        '  "parallel_groups": [[0], [1, 2]]  // indices into delegations array\n'
        "}\n"
        "```"
    )

    # Call LLM for planning
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]

    response = await llm_with_cloud_fallback(
        messages=messages,
        rules=rules,
        task_type="decomposition",
        context_tokens=len(system_prompt + user_prompt) // 4,
    )

    content = ""
    if response and response.choices:
        content = response.choices[0].message.content or ""

    # Parse LLM response
    plan = _parse_plan_response(content, task, response_language, domain)

    logger.info(
        "Plan created: %d delegations, %d parallel groups, domain=%s",
        len(plan.delegations),
        len(plan.parallel_groups),
        plan.domain.value,
    )

    # Build initial delegation states
    delegation_states = {}
    for d in plan.delegations:
        delegation_states[d.delegation_id] = {
            "delegation_id": d.delegation_id,
            "agent_name": d.agent_name,
            "status": "pending",
            "result_summary": None,
            "sub_delegation_ids": [],
            "checkpoint_data": None,
        }

    return {
        "execution_plan": plan.model_dump(),
        "delegation_states": delegation_states,
        "completed_delegations": [],
        "delegation_results": {},
    }


def _parse_plan_response(
    content: str,
    task: CodingTask,
    response_language: str,
    domain: str,
) -> ExecutionPlan:
    """Parse LLM JSON response into ExecutionPlan."""
    # Try to extract JSON from response
    json_str = content
    if "```json" in content:
        json_str = content.split("```json")[1].split("```")[0]
    elif "```" in content:
        json_str = content.split("```")[1].split("```")[0]

    try:
        data = json.loads(json_str.strip())
    except json.JSONDecodeError:
        logger.warning("Failed to parse plan JSON, creating single research delegation")
        # Fallback: single research delegation
        return _fallback_plan(task, response_language, domain)

    delegations: list[DelegationMessage] = []
    raw_delegations = data.get("delegations", [])

    for i, raw in enumerate(raw_delegations):
        agent_name = raw.get("agent_name", "research")
        task_summary = raw.get("task_summary", task.query)
        expected_output = raw.get("expected_output", "")

        delegations.append(DelegationMessage(
            delegation_id=f"d-{uuid.uuid4().hex[:8]}",
            depth=1,
            agent_name=agent_name,
            task_summary=task_summary,
            expected_output=expected_output,
            response_language=response_language,
            client_id=task.client_id,
            project_id=task.project_id,
        ))

    # Parse parallel groups (convert indices to delegation IDs)
    raw_groups = data.get("parallel_groups", [])
    parallel_groups: list[list[str]] = []

    if raw_groups:
        for group in raw_groups:
            group_ids = []
            for idx in group:
                if isinstance(idx, int) and 0 <= idx < len(delegations):
                    group_ids.append(delegations[idx].delegation_id)
            if group_ids:
                parallel_groups.append(group_ids)
    else:
        # No groups specified → all sequential
        for d in delegations:
            parallel_groups.append([d.delegation_id])

    # Determine domain
    try:
        domain_enum = DomainType(domain)
    except ValueError:
        domain_enum = DomainType.CODE

    return ExecutionPlan(
        delegations=delegations,
        parallel_groups=parallel_groups,
        domain=domain_enum,
    )


def _fallback_plan(
    task: CodingTask,
    response_language: str,
    domain: str,
) -> ExecutionPlan:
    """Create a simple fallback plan with a single research delegation."""
    delegation = DelegationMessage(
        delegation_id=f"d-{uuid.uuid4().hex[:8]}",
        depth=1,
        agent_name="research",
        task_summary=task.query,
        expected_output="Comprehensive answer to the user's question",
        response_language=response_language,
        client_id=task.client_id,
        project_id=task.project_id,
    )

    try:
        domain_enum = DomainType(domain)
    except ValueError:
        domain_enum = DomainType.RESEARCH

    return ExecutionPlan(
        delegations=[delegation],
        parallel_groups=[[delegation.delegation_id]],
        domain=domain_enum,
    )


def _classify_trigger(query: str, domain: str) -> str:
    """Classify task into a trigger pattern for procedural memory lookup."""
    query_lower = query.lower()

    if any(w in query_lower for w in ["review", "code review", "check code"]):
        return "code_review"
    if any(w in query_lower for w in ["deploy", "deployment", "release"]):
        return "deployment"
    if any(w in query_lower for w in ["test", "testing", "coverage"]):
        return "testing"
    if any(w in query_lower for w in ["email", "respond", "reply"]):
        return "email_response"
    if any(w in query_lower for w in ["bug", "fix", "error", "issue"]):
        return "bug_fix"
    if any(w in query_lower for w in ["plan", "sprint", "roadmap"]):
        return "planning"
    if any(w in query_lower for w in ["document", "docs", "readme"]):
        return "documentation"

    return f"{domain}_general"
