"""Routes coding agent questions: KB auto-answer or user escalation."""

from __future__ import annotations

import logging

import httpx

from app.config import settings
from app.tools.executor import _execute_kb_search

logger = logging.getLogger(__name__)

_KOTLIN_INTERNAL_URL = settings.kotlin_server_url


async def route_agent_question(
    question: str,
    context: str,
    priority: str,  # "blocking", "question", "info"
    client_id: str,
    project_id: str | None,
    task_id: str,
) -> dict:
    """
    Try to answer agent question from KB.
    If confidence > 0.8: return auto-answer.
    If not: escalate to user via pending_agent_questions.

    Returns: {"answer": str, "source": "kb"|"user_pending"|"timeout"}
    """
    # 1. Search KB for answer
    kb_result = await _execute_kb_search(
        query=question,
        max_results=3,
        client_id=client_id,
        project_id=project_id,
        processing_mode="FOREGROUND",
    )

    # 2. Check if KB has high-confidence answer
    # Parse kb_result for confidence indicators
    if kb_result and "No results" not in kb_result and len(kb_result) > 50:
        logger.info(
            "QUESTION_ROUTER: KB auto-answer for task=%s question='%s'",
            task_id,
            question[:80],
        )
        return {"answer": kb_result, "source": "kb"}

    # 3. No KB answer — escalate to user
    logger.info(
        "QUESTION_ROUTER: Escalating to user task=%s priority=%s question='%s'",
        task_id,
        priority,
        question[:80],
    )

    # Submit to pending_agent_questions via Kotlin server API
    question_id = ""
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.post(
                f"{_KOTLIN_INTERNAL_URL}/internal/agent-question",
                json={
                    "taskId": task_id,
                    "question": question,
                    "context": context,
                    "priority": priority,
                    "clientId": client_id,
                    "projectId": project_id,
                },
            )
            question_id = resp.json().get("questionId", "")
    except Exception as exc:
        logger.warning("QUESTION_ROUTER: Failed to submit question: %s", exc)

    return {"answer": "", "source": "user_pending", "questionId": question_id}
