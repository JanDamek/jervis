"""EPIC 13-S2: Behavior Learning Loop.

Analyzes user denials and approval patterns to propose new behavioral
rules. Learns from repeated denials (propose stricter rules) and
repeated approvals (propose relaxed rules).
"""

from __future__ import annotations

import logging
from datetime import datetime

from app.llm.provider import llm_completion
from app.models import ModelTier

logger = logging.getLogger(__name__)


async def analyze_denial(
    denial_action: str,
    denial_reason: str | None,
    task_summary: str,
    session_id: str,
) -> dict | None:
    """Analyze a user denial to propose a behavioral rule.

    Called after a user denies an approval request. Uses LLM to
    understand *why* and propose a rule to prevent repeating the mistake.

    Returns dict with {rule, reason, learned_from} or None if no rule.
    """
    prompt = f"""Analyze this user denial and propose a behavioral rule.

Action denied: {denial_action}
Reason given: {denial_reason or 'No reason provided'}
Task context: {task_summary}

Rules:
- Only propose a rule if the denial suggests a repeating pattern
- The rule should be a short, clear instruction (1-2 sentences)
- The rule must be actionable for an AI assistant
- If the denial seems one-off (typo, misunderstanding), return NONE

Respond with EXACTLY one of:
1. NONE (if no rule should be proposed)
2. A JSON object: {{"rule": "...", "reason": "..."}}
"""
    try:
        result = await llm_completion(
            messages=[{"role": "user", "content": prompt}],
            tier=ModelTier.LOCAL_STANDARD,
            temperature=0.3,
            max_tokens=200,
        )
        text = result.strip()
        if text.upper() == "NONE":
            return None

        import json
        data = json.loads(text)
        if "rule" not in data:
            return None
        return {
            "rule": data["rule"],
            "reason": data.get("reason", "Learned from denial"),
            "learned_from": f"denial:{denial_action}:{session_id}",
        }
    except Exception:
        logger.debug("Failed to analyze denial for behavior learning", exc_info=True)
        return None


async def analyze_approval_patterns(
    client_id: str,
    approval_stats: dict,
) -> dict | None:
    """Analyze approval patterns to suggest auto-approval rules.

    If a client repeatedly approves the same action type (>10 times
    with >90% approval rate), suggest relaxing the approval requirement.

    Returns dict with {rule, reason, learned_from} or None.
    """
    for action, stats in approval_stats.items():
        total = stats.get("approved", 0) + stats.get("denied", 0)
        if total < 10:
            continue
        approval_rate = stats.get("approved", 0) / total
        if approval_rate >= 0.9:
            return {
                "rule": f"Auto-approve '{action}' actions (user approved {int(approval_rate*100)}% of {total} requests)",
                "reason": f"Consistent approval pattern detected for {action}",
                "learned_from": f"approval_pattern:{action}:{client_id}",
            }
    return None
