"""Evaluate, next_step, advance_step, advance_goal nodes.

Handles result evaluation, routing, and goal/step advancement.
"""

from __future__ import annotations

import fnmatch
import logging

from app.models import (
    CodingTask,
    Evaluation,
    Goal,
    GoalSummary,
    ProjectRules,
    StepResult,
)

logger = logging.getLogger(__name__)


async def evaluate(state: dict) -> dict:
    """Evaluate the result of the last step."""
    task = CodingTask(**state["task"])
    rules = ProjectRules(**state["rules"])
    step_results = state.get("step_results", [])

    if not step_results:
        return {"error": "No step results to evaluate"}

    last_result = StepResult(**step_results[-1])
    checks: list[str] = []

    if not last_result.success:
        checks.append(f"FAILED: Step {last_result.step_index} failed: {last_result.summary}")

    # Check forbidden files using fnmatch
    for f in last_result.changed_files:
        for pattern in rules.forbidden_files:
            if fnmatch.fnmatch(f, pattern):
                checks.append(f"BLOCKED: Changed forbidden file: {f}")

    # Check max file count
    if len(last_result.changed_files) > rules.max_changed_files:
        checks.append(
            f"WARNING: Changed {len(last_result.changed_files)} files "
            f"(max {rules.max_changed_files})"
        )

    acceptable = not any(c.startswith("BLOCKED") or c.startswith("FAILED") for c in checks)

    evaluation = Evaluation(acceptable=acceptable, checks=checks)

    logger.info(
        "Evaluation: acceptable=%s checks=%s",
        evaluation.acceptable,
        evaluation.checks,
    )

    return {"evaluation": evaluation.model_dump()}


def next_step(state: dict) -> str:
    """Route to next step, next goal, git operations, or finalize.

    Returns:
        "execute_step" — more steps in current goal
        "advance_goal" — current goal done, more goals remain
        "git_operations" — all goals done, proceed to git
        "finalize" — evaluation failed, skip to finalize
    """
    steps = state.get("steps", [])
    current_step = state.get("current_step_index", 0)
    goals = state.get("goals", [])
    current_goal = state.get("current_goal_index", 0)
    evaluation = state.get("evaluation", {})

    # If evaluation failed, go to finalize (skip remaining steps)
    if evaluation and not evaluation.get("acceptable", True):
        return "finalize"

    # More steps in current goal?
    if current_step + 1 < len(steps):
        return "execute_step"

    # More goals?
    if current_goal + 1 < len(goals):
        return "advance_goal"

    # All goals done – proceed to git
    return "git_operations"


def advance_step(state: dict) -> dict:
    """Advance to next step index."""
    return {"current_step_index": state.get("current_step_index", 0) + 1}


def advance_goal(state: dict) -> dict:
    """Advance to next goal index and build GoalSummary for cross-goal context."""
    current_idx = state.get("current_goal_index", 0)
    goals = [Goal(**g) for g in state.get("goals", [])]
    step_results = [StepResult(**r) for r in state.get("step_results", [])]
    steps_for_goal = state.get("steps", [])

    # Collect results from steps of the current goal
    num_steps = len(steps_for_goal)
    recent_results = step_results[-num_steps:] if num_steps > 0 else []

    # Build summary
    changed_files: list[str] = []
    summaries: list[str] = []
    for r in recent_results:
        if r.success:
            summaries.append(r.summary)
        changed_files.extend(r.changed_files)

    goal = goals[current_idx] if current_idx < len(goals) else None
    goal_summary = GoalSummary(
        goal_id=goal.id if goal else f"g{current_idx}",
        title=goal.title if goal else "Unknown",
        summary="; ".join(summaries) if summaries else "No successful steps",
        changed_files=list(set(changed_files)),
    )

    existing_summaries = list(state.get("goal_summaries", []))
    existing_summaries.append(goal_summary.model_dump())

    logger.info(
        "Advancing from goal %d to %d, summary: %s",
        current_idx + 1, current_idx + 2, goal_summary.summary[:100],
    )

    return {
        "current_goal_index": current_idx + 1,
        "goal_summaries": existing_summaries,
    }
