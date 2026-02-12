"""Plan node — SINGLE_TASK multi-type planning.

Determines approach based on task_action: respond, code, tracker_ops, or mixed.
"""

from __future__ import annotations

import json
import logging

from app.models import (
    CodingStep,
    CodingTask,
    Complexity,
    Goal,
    StepType,
)
from app.graph.nodes._helpers import (
    llm_with_cloud_fallback,
    parse_json_response,
    select_agent,
)

logger = logging.getLogger(__name__)


async def plan(state: dict) -> dict:
    """Plan SINGLE_TASK execution. Determines approach based on task_action.

    Routes:
    - respond → simple "respond directly" plan
    - code → decompose into goals + steps (coding pipeline)
    - tracker_ops → create/update issues
    - mixed → steps of different types in sequence
    """
    task = CodingTask(**state["task"])
    action = state.get("task_action", "respond")
    evidence = state.get("evidence_pack", {})
    project_context = state.get("project_context", "")
    clarification = state.get("clarification_response")

    # Build context for LLM
    context_parts: list[str] = []
    # Task identity from top-level state
    client_name = state.get("client_name")
    project_name = state.get("project_name")
    target_branch = state.get("target_branch")
    identity_parts = []
    if client_name:
        identity_parts.append(f"Client: {client_name}")
    if project_name:
        identity_parts.append(f"Project: {project_name}")
    if identity_parts:
        context_parts.append("## Task Context\n" + "\n".join(identity_parts))

    # Branch context — if a target branch was detected from the user query
    if target_branch:
        context_parts.append(
            f"## Target Branch\nWork on branch: **{target_branch}**\n"
            "All code changes should target this branch."
        )
    if project_context:
        context_parts.append(f"## Project Context\n{project_context[:3000]}")
    if evidence:
        for kr in evidence.get("kb_results", []):
            content = kr.get("content", "")
            if content:
                context_parts.append(f"## Knowledge Base\n{content[:3000]}")
    if clarification:
        context_parts.append(
            f"## User Clarification\n{json.dumps(clarification, default=str, indent=2)}"
        )

    # Key decisions from chat history (for planning continuity)
    chat_history = state.get("chat_history")
    if chat_history:
        decisions = []
        for block in (chat_history.get("summary_blocks") or []):
            for d in (block.get("key_decisions") or []):
                decisions.append(d)
        if decisions:
            context_parts.append(
                "## Key Decisions from Previous Conversation\n" +
                "\n".join(f"- {d}" for d in decisions[-10:])
            )

    context_block = "\n\n".join(context_parts)

    if action == "respond":
        # Analytical/advisory task → single respond step
        return {
            "steps": [CodingStep(
                index=0,
                instructions=task.query,
                step_type=StepType.RESPOND,
            ).model_dump()],
            "goals": [Goal(
                id="g1",
                title="Odpověz na dotaz",
                description=task.query,
                complexity=Complexity.SIMPLE,
            ).model_dump()],
            "current_goal_index": 0,
        }

    if action == "code":
        # Coding task → decompose into goals + steps
        return await _plan_coding_task(state, task, context_block)

    if action == "tracker_ops":
        # Tracker operations → create/update issues
        return await _plan_tracker_ops(state, task, context_block)

    if action == "mixed":
        # Mixed — plan steps of different types
        return await _plan_mixed_task(state, task, context_block)

    # Fallback: treat as respond
    return {
        "steps": [CodingStep(
            index=0,
            instructions=task.query,
            step_type=StepType.RESPOND,
        ).model_dump()],
        "goals": [Goal(
            id="g1",
            title="Zpracování úkolu",
            description=task.query,
            complexity=Complexity.SIMPLE,
        ).model_dump()],
        "current_goal_index": 0,
    }


async def _plan_coding_task(state: dict, task: CodingTask, context_block: str) -> dict:
    """Plan coding task — decompose into goals + steps."""
    raw_complexity = state.get("task_complexity", "medium")
    try:
        complexity = Complexity(raw_complexity)
    except ValueError:
        complexity = Complexity.MEDIUM

    from app.models import ProjectRules
    rules = ProjectRules(**state["rules"])
    agent_type = select_agent(complexity, task.agent_preference, rules)

    messages = [
        {
            "role": "system",
            "content": (
                "You are a task decomposition agent. Break down the user's coding request "
                "into concrete, implementable goals.\n\n"
                "Rules:\n"
                "- Each goal will be executed by a coding agent — make goals concrete\n"
                "- Order goals by dependency\n"
                "- Use the dependencies field to declare prerequisite goal IDs\n"
                "- Simple tasks may have just 1 goal\n"
                "- If a target branch is specified, include it in instructions\n\n"
                "Respond with JSON:\n"
                "{\n"
                '  "goals": [\n'
                '    {\n'
                '      "id": "g1",\n'
                '      "title": "Short descriptive title",\n'
                '      "description": "Detailed instructions for the coding agent",\n'
                '      "complexity": "simple|medium|complex|critical",\n'
                '      "dependencies": []\n'
                "    }\n"
                "  ]\n"
                "}"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Task: {task.query}"
                + (f"\n\n{context_block}" if context_block else "")
            ),
        },
    ]

    response = await llm_with_cloud_fallback(
        state=state, messages=messages, task_type="decomposition", max_tokens=8192,
    )
    content = response.choices[0].message.content
    parsed = parse_json_response(content)
    raw_goals = parsed.get("goals", [])

    goals = []
    for g in raw_goals:
        try:
            goals.append(Goal(**g))
        except Exception as e:
            logger.warning("Skipping invalid goal: %s (%s)", g, e)

    if not goals:
        goals = [
            Goal(
                id="g1", title="Execute task",
                description=task.query, complexity=complexity,
            )
        ]

    logger.info("Planned %d coding goals", len(goals))

    return {
        "goals": [g.model_dump() for g in goals],
        "current_goal_index": 0,
        "steps": [],
    }


async def _plan_tracker_ops(state: dict, task: CodingTask, context_block: str) -> dict:
    """Plan tracker operations."""
    messages = [
        {
            "role": "system",
            "content": (
                "You are a project management assistant. Plan tracker operations "
                "based on the user's request.\n\n"
                "Respond with JSON:\n"
                "{\n"
                '  "operations": [\n'
                '    {"action": "create|update|comment", "title": "...", '
                '"description": "...", "type": "story|task|bug", "parent_key": null}\n'
                "  ]\n"
                "}"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Task: {task.query}"
                + (f"\n\n{context_block}" if context_block else "")
            ),
        },
    ]

    response = await llm_with_cloud_fallback(
        state=state, messages=messages, task_type="planning", max_tokens=4096,
    )
    content = response.choices[0].message.content
    parsed = parse_json_response(content)
    operations = parsed.get("operations", [])

    steps = [
        CodingStep(
            index=i,
            instructions=json.dumps(op, ensure_ascii=False),
            step_type=StepType.TRACKER,
            tracker_operations=[op],
        ).model_dump()
        for i, op in enumerate(operations)
    ]

    if not steps:
        steps = [CodingStep(
            index=0,
            instructions=task.query,
            step_type=StepType.TRACKER,
        ).model_dump()]

    return {
        "steps": steps,
        "goals": [Goal(
            id="g1", title="Tracker operations",
            description=task.query, complexity=Complexity.SIMPLE,
        ).model_dump()],
        "current_goal_index": 0,
    }


async def _plan_mixed_task(state: dict, task: CodingTask, context_block: str) -> dict:
    """Plan mixed task — combination of respond, code, tracker."""
    messages = [
        {
            "role": "system",
            "content": (
                "You are a task planner. Plan a mixed task that may involve analysis, "
                "coding, and tracker operations.\n\n"
                "Step types: respond (analysis/answer), code (coding agent), "
                "tracker (create/update issues)\n\n"
                "Respond with JSON:\n"
                "{\n"
                '  "steps": [\n'
                '    {"index": 0, "type": "respond", "instructions": "..."},\n'
                '    {"index": 1, "type": "code", "instructions": "...", '
                '"files": ["path/to/file"]},\n'
                '    {"index": 2, "type": "tracker", "instructions": "...", '
                '"operations": [{"action": "update", "title": "..."}]}\n'
                "  ]\n"
                "}"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Task: {task.query}"
                + (f"\n\n{context_block}" if context_block else "")
            ),
        },
    ]

    response = await llm_with_cloud_fallback(
        state=state, messages=messages, task_type="planning", max_tokens=8192,
    )
    content = response.choices[0].message.content
    parsed = parse_json_response(content)
    raw_steps = parsed.get("steps", [])

    steps = []
    for s in raw_steps:
        step_type_str = s.get("type", "respond")
        try:
            step_type = StepType(step_type_str)
        except ValueError:
            step_type = StepType.RESPOND

        steps.append(CodingStep(
            index=s.get("index", len(steps)),
            instructions=s.get("instructions", ""),
            step_type=step_type,
            files=s.get("files", []),
            tracker_operations=s.get("operations", []),
        ).model_dump())

    if not steps:
        steps = [CodingStep(
            index=0,
            instructions=task.query,
            step_type=StepType.RESPOND,
        ).model_dump()]

    return {
        "steps": steps,
        "goals": [Goal(
            id="g1", title="Zpracování úkolu",
            description=task.query, complexity=Complexity.MEDIUM,
        ).model_dump()],
        "current_goal_index": 0,
    }


def route_after_plan(state: dict) -> str:
    """Route after plan: respond steps → respond node, coding → execute_step."""
    steps = state.get("steps", [])
    if not steps:
        return "respond"

    # If ALL steps are respond type → go to respond node directly
    all_respond = all(
        s.get("step_type", "code") == "respond"
        for s in steps
    )
    if all_respond:
        return "respond"

    # Otherwise → execution loop
    return "execute_step"
