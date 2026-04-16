"""Generates draft responses for URGENT items (emails, Teams messages, etc.)."""

from __future__ import annotations

import json
import logging

from app.chat.handler_streaming import call_llm

logger = logging.getLogger(__name__)


async def generate_draft_response(
    incoming_content: str,
    channel_type: str,  # email, teams, slack
    sender: str,
    subject: str,
    kb_context: str,  # relevant KB results
    client_id: str,
    project_id: str | None = None,
) -> dict | None:
    """
    Generate a draft response for an urgent incoming message.

    Returns: {"draft": str, "confidence": float, "reasoning": str} or None
    """
    prompt = (
        "You are a professional assistant drafting a response.\n\n"
        f"Channel: {channel_type}\n"
        f"From: {sender}\n"
        f"Subject: {subject}\n"
        f"Content:\n{incoming_content[:2000]}\n\n"
        f"Relevant context from knowledge base:\n{kb_context[:2000]}\n\n"
        "Draft a professional response in the same language as the incoming message.\n"
        "Be concise, helpful, and match the tone of the conversation.\n"
        "If you don't have enough information to respond properly, say so.\n\n"
        'Respond in JSON: {"draft": "...", "confidence": 0.0-1.0, "reasoning": "..."}'
    )

    try:
        response = await call_llm(
            messages=[{"role": "user", "content": prompt}],
            timeout=60,
        )

        # Extract text content from LLM response
        text = response if isinstance(response, str) else response.get("content", "")
        result = json.loads(text)
        logger.info(
            "DRAFT_GENERATED: channel=%s sender=%s confidence=%.2f",
            channel_type,
            sender,
            result.get("confidence", 0),
        )
        return result
    except Exception as exc:
        logger.warning("DRAFT_GENERATION_FAILED: %s", exc)
        return None
