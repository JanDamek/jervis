"""Coding pipeline nodes — decompose, select_goal, plan_steps.

These handle the SINGLE_TASK/code path: break down coding tasks
into goals and plan execution steps for each goal.
"""

from __future__ import annotations

import json
import logging

from app.models import (
    CodingStep,
    CodingTask,
    Complexity,
    Goal,
    GoalSummary,
    StepType,
)
from app.graph.nodes._helpers import (
    llm_with_cloud_fallback,
    parse_json_response,
    select_agent,
)

logger = logging.getLogger(__name__)


async def decompose(state: dict) -> dict:
    """Decompose user query into goals using LLM.

    Context-aware: uses project_context, task_complexity, clarification_response,
    and environment to produce well-ordered goals with dependency declarations.
    """
    task = CodingTask(**state["task"])

    raw_complexity = state.get("task_complexity", "medium")
    try:
        complexity = Complexity(raw_complexity)
    except ValueError:
        complexity = Complexity.MEDIUM

    # Build context sections
    context_parts: list[str] = []

    project_context = state.get("project_context", "")
    if project_context:
        context_parts.append(f"## Existing Project Context\n{project_context[:3000]}")

    clarification = state.get("clarification_response")
    if clarification:
        context_parts.append(
            f"## User Clarification Answers\n{json.dumps(clarification, default=str, indent=2)}"
        )

    env_data = state.get("environment")
    if env_data:
        context_parts.append(f"## Environment\n{json.dumps(env_data, default=str)[:500]}")

    # Evidence pack context
    evidence = state.get("evidence_pack", {})
    if evidence:
        for kr in evidence.get("kb_results", []):
            content = kr.get("content", "")
            if content:
                context_parts.append(f"## Knowledge Base\n{content[:2000]}")

    context_block = "\n\n".join(context_parts)

    messages = [
        {
            "role": "system",
            "content": (
                "You are a task decomposition agent. Break down the user's request "
                "into concrete, implementable goals.\n\n"
                "Rules:\n"
                "- Each goal will be executed by a coding agent — make goals concrete\n"
                "- Order goals by dependency\n"
                "- Use the dependencies field to declare prerequisite goal IDs\n"
                "- Simple tasks may have just 1 goal; complex tasks can have 5-10+\n"
                "- Each goal should be independently testable\n\n"
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

    logger.info("Decomposed into %d goals (complexity=%s)", len(goals), complexity)

    return {
        "goals": [g.model_dump() for g in goals],
        "current_goal_index": 0,
    }


def select_goal(state: dict) -> dict:
    """Select the current goal for processing with dependency validation."""
    goals = [Goal(**g) for g in state["goals"]]
    idx = state["current_goal_index"]

    if idx >= len(goals):
        logger.error("Goal index %d out of range (%d goals)", idx, len(goals))
        return {"error": f"Goal index {idx} out of range ({len(goals)} goals)"}

    # Build set of completed goal IDs
    completed_ids = {
        gs.get("goal_id") for gs in state.get("goal_summaries", [])
    }

    goal = goals[idx]

    # Check dependencies
    unmet = [dep for dep in goal.dependencies if dep not in completed_ids]
    if unmet:
        logger.warning(
            "Goal %s has unmet dependencies: %s. Trying to swap.",
            goal.id, unmet,
        )

        # Try to find a later goal with all dependencies met
        swap_idx = None
        for candidate_idx in range(idx + 1, len(goals)):
            candidate = goals[candidate_idx]
            candidate_unmet = [
                d for d in candidate.dependencies if d not in completed_ids
            ]
            if not candidate_unmet:
                swap_idx = candidate_idx
                break

        if swap_idx is not None:
            goals[idx], goals[swap_idx] = goals[swap_idx], goals[idx]
            goal = goals[idx]
            logger.info(
                "Swapped goal %d with %d: now executing %s",
                idx, swap_idx, goal.title,
            )
            return {"goals": [g.model_dump() for g in goals]}

        logger.warning(
            "Cannot resolve dependencies for goal %s — proceeding best-effort",
            goal.id,
        )

    logger.info(
        "Selected goal %d/%d: %s (complexity=%s)",
        idx + 1, len(goals), goal.title, goal.complexity,
    )
    return {}


async def plan_steps(state: dict) -> dict:
    """Create execution steps for the current goal with cross-goal context."""
    task = CodingTask(**state["task"])
    goals = [Goal(**g) for g in state["goals"]]
    idx = state["current_goal_index"]
    goal = goals[idx]

    from app.models import ProjectRules
    rules = ProjectRules(**state["rules"])
    agent_type = select_agent(goal.complexity, task.agent_preference, rules)

    # Build context sections
    context_parts: list[str] = []

    # Cross-goal context
    goal_summaries = state.get("goal_summaries", [])
    if goal_summaries:
        context_parts.append("## Previously Completed Goals")
        for gs in goal_summaries:
            summary = GoalSummary(**gs)
            files_str = ", ".join(summary.changed_files[:10]) if summary.changed_files else "none"
            context_parts.append(
                f"- **{summary.title}**: {summary.summary} (files: {files_str})"
            )

    project_context = state.get("project_context", "")
    if project_context:
        context_parts.append(f"\n## Project Context\n{project_context[:2000]}")

    context_block = "\n".join(context_parts)

    messages = [
        {
            "role": "system",
            "content": (
                "You are a coding task planner. Create concrete, detailed steps "
                "for a coding agent to execute.\n\n"
                "Rules:\n"
                "- Each step is a single, focused change for the coding agent\n"
                "- Instructions must be specific enough for implementation\n"
                "- Include relevant file paths in the files array\n"
                "- Steps should be ordered logically\n\n"
                "Respond with JSON:\n"
                "{\n"
                '  "steps": [\n'
                '    {\n'
                '      "index": 0,\n'
                '      "instructions": "Detailed instructions...",\n'
                f'      "agent_type": "{agent_type.value}",\n'
                '      "files": ["path/to/file.kt"]\n'
                "    }\n"
                "  ]\n"
                "}"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Goal: {goal.title}\n"
                f"Description: {goal.description}"
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

    steps: list[CodingStep] = []
    for s in raw_steps:
        try:
            step = CodingStep(**s, step_type=StepType.CODE)
            if step.instructions.strip():
                steps.append(step)
        except Exception as e:
            logger.warning("Skipping invalid step: %s (%s)", s, e)

    if not steps:
        steps = [
            CodingStep(
                index=0,
                instructions=goal.description,
                agent_type=agent_type,
                step_type=StepType.CODE,
            )
        ]

    logger.info("Planned %d steps for goal %s", len(steps), goal.id)

    return {
        "steps": [s.model_dump() for s in steps],
        "current_step_index": 0,
    }
