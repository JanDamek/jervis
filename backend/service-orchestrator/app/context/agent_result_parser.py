"""Agent result parser — normalizes variable agent response formats.

Coding agents don't have a fixed response structure. This module
normalizes whatever they return into a consistent internal format.
"""

from __future__ import annotations

import logging
from typing import Any

from app.llm.provider import llm_provider
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

    summary = text[:500]  # Simple truncation as default
    try:
        summary = await _summarize_with_llm(text, max_tokens=200)
    except Exception as e:
        logger.warning("LLM summarization failed, using truncation: %s", e)

    return {
        "success": True,  # If agent didn't crash, consider it success
        "summary": summary,
        "changed_files": [],
        "raw": text,
    }


async def _summarize_with_llm(text: str, max_tokens: int = 200) -> str:
    """Summarize text using local LLM."""
    messages = [
        {
            "role": "system",
            "content": "Summarize the following agent output in 2-3 sentences. Focus on what was done and the outcome.",
        },
        {
            "role": "user",
            "content": text[:4000],  # Cap input
        },
    ]

    response = await llm_provider.completion(
        messages=messages,
        tier=ModelTier.LOCAL_FAST,
        max_tokens=max_tokens,
        temperature=0.1,
    )

    return response.choices[0].message.content.strip()
