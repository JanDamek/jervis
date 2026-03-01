"""Response quality detection for background tasks.

Detection criteria:
- Empty or very short response (< 20 chars)
- Response is error message or refusal
- Tool call fails with parse error after 2 retries
- Model outputs gibberish (no Czech/English words detected)
"""

from __future__ import annotations

import logging
import re

logger = logging.getLogger(__name__)


def needs_escalation(
    answer: str | None,
    *,
    tool_parse_failures: int = 0,
    iteration: int = 0,
) -> bool:
    """Detect if LLM response quality is insufficient.

    Args:
        answer: LLM response text (None if no response).
        tool_parse_failures: Count of tool call parse failures in this exchange.
        iteration: Current iteration number.

    Returns:
        True if the response is low quality (empty, refusal, gibberish).
    """
    # Empty or None response
    if not answer or len(answer.strip()) < 20:
        logger.info("QUALITY_CHECK | reason=empty_response | len=%d", len(answer or ""))
        return True

    # Error/refusal patterns
    stripped = answer.strip().lower()
    refusal_patterns = [
        "i cannot", "i can't", "nemohu", "nelze", "nedokážu",
        "i don't understand", "nerozumím", "sorry, i",
    ]
    if any(stripped.startswith(p) for p in refusal_patterns):
        logger.info("QUALITY_CHECK | reason=refusal")
        return True

    # Repeated tool parse failures
    if tool_parse_failures >= 2:
        logger.info("QUALITY_CHECK | reason=tool_parse_failures=%d", tool_parse_failures)
        return True

    # Gibberish detection: response should contain at least some real words
    word_count = len(re.findall(r"\b[a-záčďéěíňóřšťúůýž]{3,}\b", answer, re.IGNORECASE))
    if len(answer) > 100 and word_count < 5:
        logger.info("QUALITY_CHECK | reason=gibberish | word_count=%d", word_count)
        return True

    return False
