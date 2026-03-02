"""Lightweight intent classifier for tool category selection.

Regex + keyword matching — no LLM call. Runs in <1ms.
Returns set of ToolCategory values to expose to the LLM.

Intent classification reduces the tool count from 26 to 5-13 per call,
cutting ~1,700 tokens of tool schema overhead and preventing the model
from getting distracted by irrelevant tools.
"""

from __future__ import annotations

import logging
import re

from app.chat.tools import ToolCategory, TOOL_CATEGORIES

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Keyword patterns per category (Czech + English)
# ---------------------------------------------------------------------------

_TASK_MGMT_PATTERNS = re.compile(
    r"(?:"
    r"úkol[yů]?|task[sy]?|background|na\s+pozadí|orchestr|"
    r"coding\s+agent|dispatch|pošli\s+(?:na\s+)?agent|"
    r"nahrávk[auy]|meeting|schůzk|klasifik|classify|neklasifik|"
    r"co\s+(?:jsi|jste)\s+(?:udělal|dělal)|nedávn[éý]\s+úkol|"
    r"stav\s+úkol|vytvoř.*úkol|"
    r"respond|odpověz\s+na\s+(?:task|úkol)|user_task|"
    r"znalost|knowledge|ulož\s+(?:do\s+)?kb|"
    # switch_context triggers (explicit + natural phrases for switching context)
    r"přepni\s+(?:se\s+)?na|otevři\s+(?:projekt|klient|\w+)|"
    r"jdeme\s+na|pojďme\s+na|pojdme\s+na|přejdi\s+na|prejdi\s+na|"
    r"switch|přepnout|prepnout|zapamatuj\s+si|"
    # memory_store triggers (learning new procedures)
    r"nauč\s+se|pamatuj\s+si|konvence|postup|"
    # git/coding operations → dispatch_coding_agent
    r"git\b|branch|větv[ei]|vetv[ei]|commit|push\b|pull\b|merge|rebase|"
    r"checkout|stash|cherry.?pick|deploy|build|kompil|sestav"
    r")",
    re.IGNORECASE,
)

_RESEARCH_PATTERNS = re.compile(
    r"(?:"
    r"kód[ue]?|kod[ue]?|code|zdrojov|source|funkc[ei]|class|"
    r"tříd[auy]|trid[auy]|metod[auy]|"
    r"architektur|pattern|vzor|implementac|"
    r"indexovan|indexed|statistik|stats|"
    r"co\s+víš\s+o|co\s+vis\s+o|co\s+máš\s+o|co\s+mas\s+o|"
    r"přehled|prehled|overview|"
    r"analýz|analyz|bug|chyb[auy]?|error|tracing|debug|"
    r"affair|záležitost|zalezitost|téma[ta]?|tema[ta]?|"
    # git/code operations trigger RESEARCH for KB lookup
    r"git\b|branch|větv[ei]|vetv[ei]|commit|repo|repozitář|repozitar"
    r")",
    re.IGNORECASE,
)

_FILTERING_PATTERNS = re.compile(
    r"(?:"
    r"filtr|filter|ignoruj|ignore|priorit[auy]?\s+(?:vysok|nízk|vysokou|nízkou)|"
    r"pravidl[ao]|rule[s]?|"
    r"ignorovat\s+(?:email|mail|jir[au]|notifikac)|"
    r"nastav\s+(?:filtr|pravidlo)|odstraň\s+(?:filtr|pravidlo)|"
    r"zruš\s+(?:filtr|pravidlo)|smaž\s+(?:filtr|pravidlo)|"
    r"nechci\s+(?:vidět|dostávat)|blokuj|block"
    r")",
    re.IGNORECASE,
)

_GREETING_PATTERNS = re.compile(
    r"(?:"
    r"ahoj|čau|zdravím|hej|dobr[éý]?\s+(?:ráno|den|odpoledne|večer)|"
    r"co\s+je\s+nového|co\s+se\s+děje|"
    r"hi|hello|hey"
    r")",
    re.IGNORECASE,
)


def classify_intent(
    user_message: str,
    has_pending_user_tasks: bool = False,
    has_unclassified_meetings: bool = False,
    has_context_task_id: bool = False,
) -> set[ToolCategory]:
    """Classify user message intent into tool categories.

    Always returns CORE. Adds other categories based on keyword matching
    and runtime context (pending tasks, unclassified meetings).

    This is a zero-cost pre-pass (regex only, no LLM call).

    Args:
        user_message: The raw user message text.
        has_pending_user_tasks: True if there are pending user_tasks.
        has_unclassified_meetings: True if there are unclassified meetings.
        has_context_task_id: True if this is a response to a user_task.

    Returns:
        Set of ToolCategory values — CORE is always included.
    """
    categories: set[ToolCategory] = {ToolCategory.CORE}

    # For long messages (>2000 chars), classify from head+tail only.
    # Long messages are typically "information dumps" (bug reports, analyses)
    # where keywords in the middle would match ALL categories.
    # The actual intent/question is in the first or last sentences.
    if len(user_message) > 2000:
        intent_text = user_message[:500] + "\n" + user_message[-500:]
        logger.info("Chat intent: long message (%d chars), classifying from head+tail (1000 chars)", len(user_message))
    else:
        intent_text = user_message

    if _TASK_MGMT_PATTERNS.search(intent_text):
        categories.add(ToolCategory.TASK_MGMT)

    if _RESEARCH_PATTERNS.search(intent_text):
        categories.add(ToolCategory.RESEARCH)

    if _FILTERING_PATTERNS.search(intent_text):
        categories.add(ToolCategory.FILTERING)

    # Context-driven: responding to a user_task needs TASK_MGMT tools
    if has_context_task_id:
        categories.add(ToolCategory.TASK_MGMT)

    # Proactive: greeting with pending tasks/meetings → TASK_MGMT
    if _GREETING_PATTERNS.search(intent_text):
        if has_pending_user_tasks or has_unclassified_meetings:
            categories.add(ToolCategory.TASK_MGMT)

    return categories


def select_tools(categories: set[ToolCategory]) -> list[dict]:
    """Build deduplicated tool list from selected categories.

    Args:
        categories: Set of ToolCategory values from classify_intent().

    Returns:
        List of tool definition dicts (no duplicates).
    """
    tools: list[dict] = []
    seen_names: set[str] = set()
    for cat in categories:
        for tool in TOOL_CATEGORIES.get(cat, []):
            name = tool["function"]["name"]
            if name not in seen_names:
                tools.append(tool)
                seen_names.add(name)
    return tools
