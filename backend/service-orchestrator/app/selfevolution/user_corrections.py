"""EPIC 13-S3: User Correction Chat Tools.

Provides chat tool handlers for storing and retrieving user corrections.
Users can say things like "Always write JIRA comments in Czech" or
"Never auto-approve email sending" and these get persisted as permanent
preferences that modify the system prompt.
"""

from __future__ import annotations

import logging
import re

from app.llm.provider import llm_completion
from app.models import ModelTier

logger = logging.getLogger(__name__)

# Patterns that indicate a user correction
CORRECTION_PATTERNS = [
    r"(?:vždy|always)\s+",
    r"(?:nikdy|never)\s+",
    r"(?:zapomeň|forget)\s+",
    r"(?:pamatuj|remember)\s+",
    r"(?:od teď|from now)\s+",
    r"(?:prosím|please)\s+(?:vždy|always|nikdy|never)",
]


def is_correction_message(message: str) -> bool:
    """Heuristic check if a user message contains a correction instruction."""
    lower = message.lower().strip()
    return any(re.search(p, lower) for p in CORRECTION_PATTERNS)


async def extract_correction(message: str) -> dict | None:
    """Extract a structured correction from a user message.

    Returns {instruction, context} or None if not a correction.
    """
    prompt = f"""Analyze this user message and extract a behavioral correction/preference if present.

Message: "{message}"

Rules:
- A correction is a permanent instruction about how to behave
- Examples: "Always write comments in Czech", "Never auto-approve emails"
- If the message is NOT a correction (just a question, task, etc.), return NONE

Respond with EXACTLY one of:
1. NONE (if not a correction)
2. A JSON object: {{"instruction": "...", "context": "..."}}
   - instruction: The rule in imperative form (e.g., "Write JIRA comments in Czech")
   - context: Optional context about when the rule applies
"""
    try:
        result = await llm_completion(
            messages=[{"role": "user", "content": prompt}],
            tier=ModelTier.LOCAL_STANDARD,
            temperature=0.2,
            max_tokens=150,
        )
        text = result.strip()
        if text.upper() == "NONE":
            return None

        import json
        data = json.loads(text)
        if "instruction" not in data:
            return None
        return data
    except Exception:
        logger.debug("Failed to extract correction", exc_info=True)
        return None


async def format_corrections_for_prompt(corrections: list[dict]) -> str:
    """Format stored corrections into a prompt section."""
    if not corrections:
        return ""
    lines = ["## User Corrections (permanent preferences)"]
    for c in corrections:
        instruction = c.get("instruction", "")
        context = c.get("context")
        if context:
            lines.append(f"- {instruction} (context: {context})")
        else:
            lines.append(f"- {instruction}")
    return "\n".join(lines)
