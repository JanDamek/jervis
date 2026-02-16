"""LLM-based context switch detection.

Classifies user messages into CONTINUE/SWITCH/AD_HOC/NEW_AFFAIR
based on the current active affair and parked affairs.
"""

from __future__ import annotations

import json
import logging

from app.memory.models import (
    Affair,
    ContextSwitchResult,
    ContextSwitchType,
)

logger = logging.getLogger(__name__)


CONTEXT_SWITCH_PROMPT = """Analyzuj zprávu uživatele v kontextu aktuální konverzace.

AKTUÁLNÍ ZÁLEŽITOST: {active_affair_title}
AKTUÁLNÍ KONTEXT: {active_affair_summary}
ODLOŽENÉ ZÁLEŽITOSTI: {parked_affair_titles}

ZPRÁVA UŽIVATELE: {user_message}

Urči typ zprávy:
1. CONTINUE — pokračuje v aktuální záležitosti
2. SWITCH — přepíná na jinou (existující odloženou) záležitost
3. AD_HOC — jednorázový dotaz, po odpovědi se vrátí k aktuální záležitosti
4. NEW_AFFAIR — začíná zcela novou záležitost

Klíčové signály:
- "teď něco jiného", "jiná věc", "nové téma" → SWITCH nebo NEW_AFFAIR
- "mimochodem", "hele", "jen rychle" → AD_HOC
- Otázka na odloženou záležitost → AD_HOC (odpověz a vrať se)
- Pokračování v tématu → CONTINUE

Odpověz POUZE validním JSON (žádný markdown, žádný text okolo):
{{"type": "CONTINUE|SWITCH|AD_HOC|NEW_AFFAIR", "target_affair": "název existující záležitosti pokud SWITCH, jinak null", "reasoning": "krátké zdůvodnění", "confidence": 0.0, "new_affair_title": "název pokud NEW_AFFAIR, jinak null"}}"""


async def detect_context_switch(
    state: dict,
    user_message: str,
    active_affair: Affair | None,
    parked_affairs: list[Affair],
    confidence_threshold: float = 0.7,
) -> ContextSwitchResult:
    """Classify user message using LOCAL_FAST LLM tier.

    Fast paths (no LLM call):
      - No active affair and no parked affairs → NEW_AFFAIR
      - Empty message → CONTINUE

    LLM path:
      - Build prompt with active affair + parked affair titles
      - Call LOCAL_FAST (max_tokens=256, temperature=0.1)
      - Parse JSON response
      - Apply confidence threshold

    Returns ContextSwitchResult with type, target, confidence, reasoning.
    """
    # Fast path: no active affair → deterministic NEW_AFFAIR
    if active_affair is None and not parked_affairs:
        return ContextSwitchResult(
            type=ContextSwitchType.NEW_AFFAIR,
            new_affair_title=user_message[:80],
            confidence=1.0,
            reasoning="No active or parked affairs — creating new affair",
        )

    if not user_message.strip():
        return ContextSwitchResult(
            type=ContextSwitchType.CONTINUE,
            confidence=1.0,
            reasoning="Empty message",
        )

    # Fast path: no active affair but parked affairs exist → NEW or SWITCH
    if active_affair is None:
        # Check if message matches any parked affair
        match = _keyword_match_affair(user_message, parked_affairs)
        if match:
            return ContextSwitchResult(
                type=ContextSwitchType.SWITCH,
                target_affair_id=match.id,
                target_affair_title=match.title,
                confidence=0.8,
                reasoning=f"No active affair, message matches parked affair: {match.title}",
            )
        return ContextSwitchResult(
            type=ContextSwitchType.NEW_AFFAIR,
            new_affair_title=user_message[:80],
            confidence=0.9,
            reasoning="No active affair, no matching parked affairs",
        )

    # LLM path
    parked_titles = ", ".join(a.title for a in parked_affairs[:10]) or "(žádné)"
    prompt = CONTEXT_SWITCH_PROMPT.format(
        active_affair_title=active_affair.title,
        active_affair_summary=active_affair.summary[:500] or "(žádné shrnutí)",
        parked_affair_titles=parked_titles,
        user_message=user_message[:1000],
    )

    try:
        # Lazy import to avoid circular dependency
        from app.graph.nodes._helpers import llm_with_cloud_fallback

        response = await llm_with_cloud_fallback(
            state=state,
            messages=[
                {"role": "system", "content": "You are a context classifier. Respond with valid JSON only."},
                {"role": "user", "content": prompt},
            ],
            context_tokens=len(prompt) // 4,
            task_type="classification",
            max_tokens=256,
            temperature=0.1,
        )

        content = response.choices[0].message.content or ""
        return _parse_switch_result(content, parked_affairs, confidence_threshold)

    except Exception as e:
        logger.warning("Context switch detection failed: %s — defaulting to CONTINUE", e)
        return ContextSwitchResult(
            type=ContextSwitchType.CONTINUE,
            confidence=0.5,
            reasoning=f"LLM classification failed: {e}",
        )


def _parse_switch_result(
    llm_output: str,
    parked_affairs: list[Affair],
    confidence_threshold: float,
) -> ContextSwitchResult:
    """Parse LLM JSON response into ContextSwitchResult."""
    try:
        # Strip markdown code fences if present
        text = llm_output.strip()
        if text.startswith("```"):
            text = text.split("\n", 1)[-1]
            if text.endswith("```"):
                text = text[:-3]
            text = text.strip()

        data = json.loads(text)
        switch_type = data.get("type", "CONTINUE").upper()
        confidence = float(data.get("confidence", 0.5))
        reasoning = data.get("reasoning", "")

        # Map to enum
        type_map = {
            "CONTINUE": ContextSwitchType.CONTINUE,
            "SWITCH": ContextSwitchType.SWITCH,
            "AD_HOC": ContextSwitchType.AD_HOC,
            "NEW_AFFAIR": ContextSwitchType.NEW_AFFAIR,
        }
        result_type = type_map.get(switch_type, ContextSwitchType.CONTINUE)

        # Apply confidence threshold for disruptive actions
        if result_type in (ContextSwitchType.SWITCH, ContextSwitchType.NEW_AFFAIR):
            if confidence < confidence_threshold:
                logger.info(
                    "Context switch confidence %.2f < threshold %.2f — falling back to CONTINUE",
                    confidence, confidence_threshold,
                )
                return ContextSwitchResult(
                    type=ContextSwitchType.CONTINUE,
                    confidence=confidence,
                    reasoning=f"Low confidence ({confidence:.2f}): {reasoning}",
                )

        result = ContextSwitchResult(
            type=result_type,
            confidence=confidence,
            reasoning=reasoning,
            new_affair_title=data.get("new_affair_title"),
        )

        # Resolve target_affair for SWITCH
        if result_type == ContextSwitchType.SWITCH:
            target_name = data.get("target_affair")
            if target_name:
                match = _find_affair_by_title(target_name, parked_affairs)
                if match:
                    result.target_affair_id = match.id
                    result.target_affair_title = match.title
                else:
                    logger.warning(
                        "SWITCH target '%s' not found in parked affairs — falling back to CONTINUE",
                        target_name,
                    )
                    return ContextSwitchResult(
                        type=ContextSwitchType.CONTINUE,
                        confidence=0.3,
                        reasoning=f"Target affair not found: {target_name}",
                    )

        return result

    except (json.JSONDecodeError, KeyError, TypeError) as e:
        logger.warning("Failed to parse context switch LLM output: %s", e)
        return ContextSwitchResult(
            type=ContextSwitchType.CONTINUE,
            confidence=0.3,
            reasoning=f"Parse error: {e}",
        )


def _keyword_match_affair(message: str, affairs: list[Affair]) -> Affair | None:
    """Simple keyword match — check if message references a parked affair."""
    msg_lower = message.lower()
    for affair in affairs:
        # Match on title words or topics
        title_words = affair.title.lower().split()
        matching_words = sum(1 for w in title_words if w in msg_lower and len(w) > 3)
        if matching_words >= 2:
            return affair
        for topic in affair.topics:
            if topic.lower() in msg_lower:
                return affair
    return None


def _find_affair_by_title(name: str, affairs: list[Affair]) -> Affair | None:
    """Find a parked affair by approximate title match."""
    name_lower = name.lower()
    for affair in affairs:
        if name_lower in affair.title.lower() or affair.title.lower() in name_lower:
            return affair
    # Fuzzy: check if significant words match
    name_words = {w for w in name_lower.split() if len(w) > 3}
    for affair in affairs:
        title_words = {w for w in affair.title.lower().split() if len(w) > 3}
        overlap = name_words & title_words
        if len(overlap) >= 2 or (len(overlap) >= 1 and len(name_words) <= 2):
            return affair
    return None
