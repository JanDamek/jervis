"""Context assembler — builds per-node LLM context from hierarchical store.

Ensures LLM calls receive appropriately scoped context:
- Step level: full detail + KB context + prev step summary
- Goal level: step names + status + 1 sentence per step
- Epic level: goal names + status only
- Evidence: RAG hits + tracker artifacts (trimmed)
"""

from __future__ import annotations

import logging

from app.context.context_store import context_store

logger = logging.getLogger(__name__)


async def assemble_step_context(
    task_id: str,
    goal_index: int,
    step_index: int,
    step_instructions: str,
    kb_context: str = "",
) -> dict:
    """Assemble context for execute_step node.

    Returns dict with keys for template insertion.
    """
    # Previous step summary (if any)
    prev_summary = ""
    if step_index > 0:
        prev_key = f"goal/{goal_index}/step/{step_index - 1}"
        prev_summary = await context_store.get_summary(task_id, "step", prev_key) or ""

    # Goal brief
    goal_summaries = await context_store.list_summaries(task_id, "goal")
    other_goals = [
        f"{gs['scope_key']}: {gs['summary'][:50]}"
        for gs in goal_summaries
    ]

    return {
        "step_instructions": step_instructions,
        "kb_context": kb_context[:4000] if kb_context else "",
        "prev_step_summary": prev_summary,
        "other_goals": other_goals,
    }


async def assemble_evaluate_context(
    task_id: str,
    goal_index: int,
    step_index: int,
    result_summary: str,
    changed_files: list[str],
    rules: dict,
) -> dict:
    """Assemble context for evaluate node."""
    return {
        "step_result_summary": result_summary,
        "changed_files": changed_files,
        "rules": rules,
    }


async def assemble_epic_review_context(task_id: str) -> dict:
    """Assemble context for epic-level review.

    NEVER includes implementation details — only goal names + status.
    """
    goal_summaries = await context_store.list_summaries(task_id, "goal")

    goals = [
        {"key": gs["scope_key"], "summary": gs["summary"][:100]}
        for gs in goal_summaries
    ]

    return {"goals": goals}


async def save_step_result(
    task_id: str,
    goal_index: int,
    step_index: int,
    summary: str,
    detail: dict | None = None,
) -> None:
    """Save step result to context store after execution."""
    scope_key = f"goal/{goal_index}/step/{step_index}"
    await context_store.save(
        task_id=task_id,
        scope="step",
        scope_key=scope_key,
        summary=summary,
        detail=detail,
    )


async def save_goal_summary(
    task_id: str,
    goal_index: int,
    summary: str,
    detail: dict | None = None,
) -> None:
    """Save goal summary to context store after goal completion."""
    scope_key = f"goal/{goal_index}"
    await context_store.save(
        task_id=task_id,
        scope="goal",
        scope_key=scope_key,
        summary=summary,
        detail=detail,
    )
