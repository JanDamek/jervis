"""Resolves what to do after a coding agent completes a task."""

from __future__ import annotations

import logging

from app.tools.executor import _execute_kb_search

logger = logging.getLogger(__name__)


async def resolve_next_step(
    task_result: str,
    task_type: str,
    task_name: str,
    client_id: str,
    project_id: str | None,
) -> dict:
    """
    Check KB for conventions about what to do after this type of task.

    Returns: {
        "action": "auto_dispatch"|"ask_user"|"done",
        "next_task": str | None,  # task description if auto_dispatch
        "message": str,           # message for user
    }
    """
    # Search KB for post-task conventions
    query = f"convention: what to do after {task_type} in this project"
    kb_result = await _execute_kb_search(
        query=query,
        max_results=3,
        client_id=client_id,
        project_id=project_id,
        processing_mode="FOREGROUND",
    )

    # Check for clear convention
    _POST_TASK_KEYWORDS = ("after pr", "after deploy", "after test", "run e2e", "create pr")
    if kb_result and any(kw in kb_result.lower() for kw in _POST_TASK_KEYWORDS):
        logger.info(
            "NEXT_STEP: Convention found for task=%s type=%s",
            task_name,
            task_type,
        )
        return {
            "action": "ask_user",  # Even with convention, confirm first time
            "next_task": None,
            "message": (
                f"Agent dokončil: {task_name}\n"
                f"Nalezená konvence: {kb_result[:200]}\n"
                "Mám pokračovat?"
            ),
        }

    # No convention — ask user
    logger.info(
        "NEXT_STEP: No convention for task=%s type=%s — asking user",
        task_name,
        task_type,
    )
    return {
        "action": "ask_user",
        "next_task": None,
        "message": (
            f"Agent dokončil: {task_name}\n"
            f"Výsledek: {task_result[:300]}\n"
            "Co dál?"
        ),
    }
