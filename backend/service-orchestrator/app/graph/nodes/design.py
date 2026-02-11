"""GENERATIVE design node — generate epic + task structure from scratch.

Handles GENERATIVE task category: user describes a high-level goal,
LLM generates an epic with tasks + acceptance criteria, then flows
into the EPIC execution pipeline after user approval.

Phase 3 implementation.
"""

from __future__ import annotations

import logging

from langgraph.types import interrupt

from app.models import CodingTask, Goal, Complexity
from app.graph.nodes._helpers import (
    llm_with_cloud_fallback,
    parse_json_response,
)

logger = logging.getLogger(__name__)


async def design(state: dict) -> dict:
    """Generate epic + task structure from high-level description.

    Steps:
    1. LLM generates structured epic: goals, acceptance criteria, dependencies
    2. interrupt() for user approval of generated structure
    3. After approval → flows into plan_epic execution

    The user provides a vague/ambitious goal and the orchestrator designs
    the full implementation structure before executing anything.
    """
    task = CodingTask(**state["task"])
    evidence = state.get("evidence_pack", {})

    # Build context from evidence pack
    kb_context = ""
    if evidence:
        kb_results = evidence.get("kb_results", [])
        if kb_results:
            kb_context = "\n".join(
                r.get("content", "")[:500] for r in kb_results[:5]
            )

    messages = [
        {
            "role": "system",
            "content": (
                "You are a software architect and project planner. "
                "Given a high-level goal, design a complete implementation plan "
                "with concrete goals (epics/stories) that can be executed by coding agents.\n\n"
                "Each goal should be independently executable and testable.\n"
                "Order goals by dependencies (prerequisite goals first).\n\n"
                "Respond with JSON:\n"
                "{\n"
                '  "epic_title": "Short title for the epic",\n'
                '  "epic_description": "2-3 sentence overview",\n'
                '  "goals": [\n'
                "    {\n"
                '      "id": "g1",\n'
                '      "title": "Goal title",\n'
                '      "description": "What needs to be done",\n'
                '      "acceptance_criteria": ["criterion 1", "criterion 2"],\n'
                '      "complexity": "simple|medium|complex",\n'
                '      "dependencies": []\n'
                "    }\n"
                "  ]\n"
                "}"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Goal: {task.query}\n\n"
                f"{'Project context:\\n' + kb_context if kb_context else ''}"
            ),
        },
    ]

    response = await llm_with_cloud_fallback(
        state=state, messages=messages, task_type="planning", max_tokens=8192,
    )
    parsed = parse_json_response(response.choices[0].message.content)

    epic_title = parsed.get("epic_title", "Generated Epic")
    epic_description = parsed.get("epic_description", task.query)
    raw_goals = parsed.get("goals", [])

    goals = []
    for g in raw_goals:
        try:
            goals.append(Goal(
                id=g.get("id", f"g{len(goals)}"),
                title=g.get("title", ""),
                description=g.get("description", ""),
                complexity=Complexity(g.get("complexity", "medium")),
                dependencies=g.get("dependencies", []),
            ))
        except Exception as e:
            logger.warning("Skipping invalid generated goal: %s (%s)", g, e)

    if not goals:
        goals = [Goal(
            id="g1", title="Execute goal",
            description=task.query, complexity=Complexity.MEDIUM,
        )]

    # Build human-readable summary for approval
    goal_summary = "\n".join(
        f"  {g.id}. {g.title} [{g.complexity.value}]"
        + (f" (depends on: {', '.join(g.dependencies)})" if g.dependencies else "")
        for g in goals
    )

    approval = interrupt({
        "type": "approval_request",
        "action": "generative_design",
        "description": (
            f"Generated plan: {epic_title}\n"
            f"{epic_description}\n\n"
            f"Goals ({len(goals)}):\n{goal_summary}"
        ),
        "task_id": task.id,
        "epic_title": epic_title,
        "goals_count": len(goals),
    })

    if not approval.get("approved", False):
        return {
            "error": "Generated plan rejected by user",
            "final_result": "Generovaný plán zamítnut uživatelem.",
        }

    logger.info("Generative design approved: %s (%d goals)", epic_title, len(goals))

    # Set goals for the epic execution pipeline
    return {
        "goals": [g.model_dump() for g in goals],
        "current_goal_index": 0,
    }
