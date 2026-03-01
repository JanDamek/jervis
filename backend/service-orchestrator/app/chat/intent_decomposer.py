"""EPIC 9-S3: Multi-Intent Decomposition.

Detects when a user message contains multiple independent intents
(e.g., "udělej X, Y a Z") and decomposes them into SingleIntent objects
with dependency ordering.

Different from handler_decompose.py (topic-based message splitting for
long messages). This module handles SHORT/MEDIUM messages that contain
multiple action requests, e.g.:

  "Vytvoř bug report na přihlašování, pak se podívej na stav PROJ-123
   a najdi v KB návod na deployment"

→ 3 intents: [create_bug, check_issue, kb_search], independent

Integration points:
- Called as pre-processing step in handler.py before agentic loop
- Uses regex heuristics for fast detection, LLM for extraction
- Result informs the agentic loop's tool selection and focus
"""

from __future__ import annotations

import json
import logging
import re

from app.models import ModelTier

logger = logging.getLogger(__name__)

# Regex patterns for multi-intent detection (Czech + English)
_MULTI_INTENT_PATTERNS = re.compile(
    r"(?:"
    r"\b(?:a\s+(?:taky|také|ještě|pak|potom)|"  # "a taky", "a ještě"
    r"(?:za\s+)?(?:prvn[éí]|druh[ée]|třetí|1\.|2\.|3\.)|"  # "za prvé", "1."
    r"(?:(?:na)?víc|kromě\s+toho|mimo\s+to|plus)|"  # "navíc", "kromě toho"
    r"(?:pak|potom|následně|nakonec|dál)\s)|"  # "pak", "potom", "nakonec"
    r"(?:and\s+(?:also|then)|(?:first|second|third)(?:ly)?|"  # English
    r"additionally|furthermore|also\b|plus\b)"
    r")",
    re.IGNORECASE,
)

# Minimum message length to attempt decomposition
_MIN_LENGTH = 30

# Maximum intents to extract
_MAX_INTENTS = 5


def has_multiple_intents(message: str) -> bool:
    """Quick regex check for potential multi-intent messages.

    This is a cheap pre-filter. Returns True if message likely contains
    multiple independent requests.
    """
    if len(message) < _MIN_LENGTH:
        return False

    # Count pattern matches
    matches = _MULTI_INTENT_PATTERNS.findall(message)
    if len(matches) >= 1:
        return True

    # Check for numbered lists
    if re.search(r"(?:^|\n)\s*\d+[\.\)]\s", message):
        return True

    # Check for bullet lists
    if re.search(r"(?:^|\n)\s*[-•]\s", message):
        return True

    return False


async def decompose_intents(
    message: str,
    context_summary: str = "",
) -> list[dict] | None:
    """Decompose a user message into multiple SingleIntent objects.

    Uses LLM to extract structured intents with action types and parameters.

    Args:
        message: User message text.
        context_summary: Brief context summary for reference resolution.

    Returns:
        List of intent dicts [{intent, action, parameters, dependsOn}]
        or None if message is single-intent.
    """
    if not has_multiple_intents(message):
        return None

    try:
        intents = await _llm_decompose(message, context_summary)
        if intents and len(intents) > 1:
            logger.info("Decomposed %d intents from message (%d chars)", len(intents), len(message))
            return intents
    except Exception as e:
        logger.warning("Intent decomposition failed: %s (treating as single intent)", e)

    return None


async def _llm_decompose(
    message: str,
    context_summary: str,
) -> list[dict]:
    """LLM-based intent extraction."""
    from app.chat.handler_streaming import call_llm

    context_part = f"\nKONTEXT: {context_summary[:300]}\n" if context_summary else ""

    prompt = (
        "Analyzuj uživatelskou zprávu a rozlož ji na nezávislé záměry/akce.\n\n"
        f"ZPRÁVA: {message[:2000]}\n"
        f"{context_part}\n"
        "Odpověz POUZE validním JSON polem:\n"
        "[\n"
        '  {"intent": "popis záměru", "action": "typ_akce", '
        '"parameters": {"klíč": "hodnota"}, "dependsOn": []},\n'
        "  ...\n"
        "]\n\n"
        "Typy akcí: search_kb, search_code, create_task, check_issue, "
        "create_issue, send_email, search_web, store_knowledge, general_question\n\n"
        "dependsOn: pole indexů (0-based) záměrů, na kterých tento závisí.\n"
        "Např. [0] = závisí na prvním záměru.\n\n"
        "Pokud zpráva obsahuje POUZE JEDEN záměr, vrať pole s jedním prvkem.\n"
        "Max 5 záměrů.\n\n"
        "JSON:"
    )

    response = await call_llm(
        messages=[
            {"role": "system", "content": "Extract user intents. Respond with JSON array only."},
            {"role": "user", "content": prompt},
        ],
        tier=ModelTier.LOCAL_COMPACT,
        max_tokens=512,
        temperature=0.1,
        timeout=8.0,
    )

    content = response.choices[0].message.content or ""
    content = content.strip()
    if content.startswith("```"):
        content = content.split("\n", 1)[-1].rsplit("```", 1)[0].strip()

    intents = json.loads(content)
    if not isinstance(intents, list):
        return []

    # Validate and normalize
    result = []
    for i, intent in enumerate(intents[:_MAX_INTENTS]):
        if not isinstance(intent, dict) or not intent.get("intent"):
            continue
        result.append({
            "intent": str(intent["intent"])[:200],
            "action": str(intent.get("action", "general_question"))[:50],
            "parameters": intent.get("parameters", {}),
            "dependsOn": [
                d for d in intent.get("dependsOn", [])
                if isinstance(d, int) and 0 <= d < i
            ],
        })

    return result


def build_intent_focus_message(intents: list[dict]) -> str:
    """Build a system message that focuses the LLM on all intents.

    Injected into messages before the agentic loop so the LLM
    knows to address each intent.
    """
    if not intents or len(intents) <= 1:
        return ""

    lines = [
        f"[MULTI-INTENT] Uživatel má {len(intents)} nezávislých požadavků. "
        "Zodpověz VŠECHNY v jedné odpovědi:\n"
    ]
    for i, intent in enumerate(intents, 1):
        deps = ""
        if intent.get("dependsOn"):
            deps = f" (závisí na: {', '.join(str(d + 1) for d in intent['dependsOn'])})"
        lines.append(f"  {i}. {intent['intent']}{deps}")

    lines.append("\nZpracuj je v pořadí s ohledem na závislosti.")
    return "\n".join(lines)


def intent_metadata(intents: list[dict] | None) -> dict:
    """Build metadata dict for save_assistant_message from decomposed intents."""
    if not intents or len(intents) <= 1:
        return {}
    return {
        "multi_intent": "true",
        "intent_count": str(len(intents)),
        "intents": ",".join(i.get("action", "unknown") for i in intents),
    }
