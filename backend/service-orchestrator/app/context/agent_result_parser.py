"""Agent result parser — normalizes variable agent response formats.

Coding agents don't have a fixed response structure. This module
normalizes whatever they return into a consistent internal format.
"""

from __future__ import annotations

import logging
from typing import Any

from app.llm.provider import llm_provider
from app.memory.content_reducer import reduce_for_prompt
from app.models import ModelTier

logger = logging.getLogger(__name__)


async def parse_agent_result(raw_response: Any) -> dict:
    """Normalize agent response into internal format.

    Agent may return:
    - Structured JSON (result.json)
    - Plain text output
    - Partial JSON
    - Error trace

    Returns:
        {success: bool, summary: str, changed_files: list[str], raw: Any}
    """
    if isinstance(raw_response, dict):
        # Structured — extract what we can
        return {
            "success": raw_response.get("success", True),
            "summary": raw_response.get("summary", ""),
            "changed_files": raw_response.get("changedFiles", []),
            "raw": raw_response,
        }

    # Plain text or other — summarize with LLM
    text = str(raw_response)
    if not text.strip():
        return {
            "success": False,
            "summary": "Empty response from agent",
            "changed_files": [],
            "raw": text,
        }

    # LLM summarization — model decides appropriate length (no hard truncation)
    try:
        summary = await _summarize_with_llm(text)
    except Exception as e:
        logger.warning("LLM summarization failed, using content reduction: %s", e)
        summary = await reduce_for_prompt(text, 2000, "summary")

    return {
        "success": True,  # If agent didn't crash, consider it success
        "summary": summary,
        "changed_files": [],
        "raw": text,
    }


async def _summarize_with_llm(text: str) -> str:
    """Summarize text using local LLM. Model decides appropriate length."""
    messages = [
        {
            "role": "system",
            "content": (
                "Summarize the following agent output. Focus on what was done and the outcome. "
                "Be as concise or detailed as the content requires — no hard length limit."
            ),
        },
        {
            "role": "user",
            "content": text,
        },
    ]

    response = await llm_provider.completion(
        messages=messages,
        tier=ModelTier.LOCAL_COMPACT,
        temperature=0.1,
    )

    return response.choices[0].message.content.strip()
