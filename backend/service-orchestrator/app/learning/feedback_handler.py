"""Handles user feedback on JERVIS decisions — stores learning in KB."""

from __future__ import annotations

import logging

from app.tools.executor import execute_tool

logger = logging.getLogger(__name__)


async def handle_feedback(
    feedback_type: str,  # "approve_draft", "reject_draft", "ignore", "explicit_instruction"
    context: dict,  # {questionId, draftId, messageId, content, etc.}
    client_id: str,
    project_id: str | None = None,
) -> None:
    """
    Process user feedback and store learning in KB.

    Rules:
    - "ignore" = one-time, NO kb_store (just mark as handled)
    - "approve_draft" = reinforce pattern in Thought Map
    - "reject_draft" = negative signal, lower confidence
    - "explicit_instruction" = kb_store as convention (permanent)
    """
    if feedback_type == "ignore":
        # One-time ignore — do NOT store as convention
        logger.info(
            "FEEDBACK: ignore (one-time) context=%s",
            context.get("messageId", ""),
        )
        return

    if feedback_type == "explicit_instruction":
        # User said something like "don't monitor this topic" — permanent convention
        instruction = context.get("content", "")
        if instruction:
            await _store_convention(
                content=instruction,
                client_id=client_id,
                project_id=project_id,
            )
            logger.info(
                "FEEDBACK: stored convention client=%s: %s",
                client_id,
                instruction[:100],
            )
        return

    if feedback_type == "approve_draft":
        # Reinforce the pattern — increase confidence for similar future drafts
        logger.info("FEEDBACK: draft approved, reinforcing pattern")
        # TODO: Call /thoughts/reinforce when thought IDs are available
        return

    if feedback_type == "reject_draft":
        # Negative signal — decrease confidence
        logger.info("FEEDBACK: draft rejected, negative signal")
        # TODO: Decrease auto-response confidence for this channel/type
        return


async def _store_convention(
    content: str,
    client_id: str,
    project_id: str | None,
) -> None:
    """Store knowledge as convention via KB service."""
    await execute_tool(
        tool_name="store_knowledge",
        arguments={
            "subject": "user convention",
            "content": content,
            "category": "convention",
        },
        client_id=client_id,
        project_id=project_id,
        skip_approval=True,
    )
