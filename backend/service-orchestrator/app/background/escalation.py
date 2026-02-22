"""Model tier escalation logic for background tasks.

Thinking = Escalation pattern:
1. Start with fastest local model (LOCAL_FAST)
2. If LLM fails (empty/nonsensical response, tool errors) → escalate
3. Escalation path: LOCAL_FAST → LOCAL_STANDARD → LOCAL_LARGE → CLOUD_REASONING
4. Cloud tiers ONLY used when project rules explicitly allow them

Detection criteria for escalation:
- Empty or very short response (< 20 chars)
- Response is error message or refusal
- Tool call fails with parse error after 2 retries
- Model outputs gibberish (no Czech/English words detected)
"""

from __future__ import annotations

import logging
import re

from app.models import ModelTier

logger = logging.getLogger(__name__)


# Escalation path: each tier maps to its successor
_ESCALATION_PATH: dict[ModelTier, ModelTier | None] = {
    ModelTier.LOCAL_FAST: ModelTier.LOCAL_STANDARD,
    ModelTier.LOCAL_STANDARD: ModelTier.LOCAL_LARGE,
    ModelTier.LOCAL_LARGE: ModelTier.LOCAL_XLARGE,
    ModelTier.LOCAL_XLARGE: None,  # End of local path
    # Cloud escalation (only if allowed)
    ModelTier.CLOUD_REASONING: ModelTier.CLOUD_CODING,
    ModelTier.CLOUD_CODING: ModelTier.CLOUD_PREMIUM,
    ModelTier.CLOUD_PREMIUM: None,  # End of cloud path
}

# Local → cloud bridge (only if cloud is allowed)
_LOCAL_TO_CLOUD_BRIDGE: ModelTier = ModelTier.CLOUD_REASONING


def needs_escalation(
    answer: str | None,
    *,
    tool_parse_failures: int = 0,
    iteration: int = 0,
) -> bool:
    """Detect if current model tier is insufficient and escalation is needed.

    Args:
        answer: LLM response text (None if no response).
        tool_parse_failures: Count of tool call parse failures in this exchange.
        iteration: Current iteration number.

    Returns:
        True if the model should be escalated to a higher tier.
    """
    # Empty or None response
    if not answer or len(answer.strip()) < 20:
        logger.info("ESCALATION_TRIGGER | reason=empty_response | len=%d", len(answer or ""))
        return True

    # Error/refusal patterns
    stripped = answer.strip().lower()
    refusal_patterns = [
        "i cannot", "i can't", "nemohu", "nelze", "nedokážu",
        "i don't understand", "nerozumím", "sorry, i",
    ]
    if any(stripped.startswith(p) for p in refusal_patterns):
        logger.info("ESCALATION_TRIGGER | reason=refusal")
        return True

    # Repeated tool parse failures
    if tool_parse_failures >= 2:
        logger.info("ESCALATION_TRIGGER | reason=tool_parse_failures=%d", tool_parse_failures)
        return True

    # Gibberish detection: response should contain at least some real words
    word_count = len(re.findall(r"\b[a-záčďéěíňóřšťúůýž]{3,}\b", answer, re.IGNORECASE))
    if len(answer) > 100 and word_count < 5:
        logger.info("ESCALATION_TRIGGER | reason=gibberish | word_count=%d", word_count)
        return True

    return False


def get_next_tier(
    current_tier: ModelTier,
    *,
    cloud_allowed: bool = False,
) -> ModelTier | None:
    """Get the next tier in the escalation path.

    Args:
        current_tier: Current model tier.
        cloud_allowed: Whether cloud models are allowed by project rules.

    Returns:
        Next tier to try, or None if no further escalation is possible.
    """
    next_tier = _ESCALATION_PATH.get(current_tier)

    # If at end of local path and cloud is allowed, bridge to cloud
    if next_tier is None and cloud_allowed and current_tier == ModelTier.LOCAL_XLARGE:
        next_tier = _LOCAL_TO_CLOUD_BRIDGE
        logger.info(
            "ESCALATION_BRIDGE | local_exhausted → cloud | tier=%s",
            next_tier.value,
        )

    # If next tier is a cloud tier but cloud is NOT allowed, stop
    if next_tier and next_tier.value.startswith("cloud_") and not cloud_allowed:
        logger.info(
            "ESCALATION_BLOCKED | next=%s | cloud_not_allowed",
            next_tier.value,
        )
        return None

    if next_tier:
        logger.info(
            "ESCALATION | %s → %s | cloud_allowed=%s",
            current_tier.value, next_tier.value, cloud_allowed,
        )

    return next_tier


class EscalationTracker:
    """Tracks escalation state across iterations within a single task.

    Usage:
        tracker = EscalationTracker(cloud_allowed=rules.auto_use_anthropic)
        while not tracker.exhausted:
            response = await llm_call(tier=tracker.current_tier, ...)
            if needs_escalation(response, ...):
                tracker.escalate()
            else:
                break  # success
    """

    def __init__(
        self,
        *,
        start_tier: ModelTier = ModelTier.LOCAL_FAST,
        cloud_allowed: bool = False,
    ):
        self.current_tier = start_tier
        self.cloud_allowed = cloud_allowed
        self.escalation_count = 0
        self.exhausted = False
        self._history: list[ModelTier] = [start_tier]

    def escalate(self) -> bool:
        """Escalate to next tier.

        Returns:
            True if escalation succeeded, False if exhausted.
        """
        next_tier = get_next_tier(
            self.current_tier,
            cloud_allowed=self.cloud_allowed,
        )
        if next_tier is None:
            self.exhausted = True
            logger.warning(
                "ESCALATION_EXHAUSTED | final_tier=%s | cloud_allowed=%s",
                self.current_tier.value, self.cloud_allowed,
            )
            return False

        self.current_tier = next_tier
        self.escalation_count += 1
        self._history.append(next_tier)
        return True

    @property
    def history_str(self) -> str:
        return " → ".join(t.value for t in self._history)
