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

_BRAIN_PATTERNS = re.compile(
    r"(?:"
    r"jir[au]|issue|ticket|bug|epic|story|sprint|backlog|"
    r"confluence|wiki|stránk[auy]|page|dokumentac|"
    r"[A-Z]{2,8}-\d+|"                          # Ticket keys like TPT-12345
    r"vytvoř\s+(?:issue|ticket|bug|stránk)|"
    r"přesuň|transition|komentář|comment"
    r")",
    re.IGNORECASE,
)

_TASK_MGMT_PATTERNS = re.compile(
    r"(?:"
    r"úkol[yů]?|task[sy]?|background|na\s+pozadí|orchestr|"
    r"coding\s+agent|dispatch|pošli\s+(?:na\s+)?agent|"
    r"nahrávk[auy]|meeting|schůzk|klasifik|classify|neklasifik|"
    r"co\s+(?:jsi|jste)\s+(?:udělal|dělal)|nedávn[éý]\s+úkol|"
    r"stav\s+úkol|vytvoř.*úkol|"
    r"respond|odpověz\s+na\s+(?:task|úkol)|user_task|"
    r"znalost|knowledge|ulož\s+(?:do\s+)?kb"
    r")",
    re.IGNORECASE,
)

_RESEARCH_PATTERNS = re.compile(
    r"(?:"
    r"kód[ue]?|code|zdrojov|source|funkc[ei]|class|tříd[auy]|metod[auy]|"
    r"architektur|pattern|vzor|implementac|"
    r"indexovan|indexed|statistik|stats|"
    r"co\s+víš\s+o|co\s+máš\s+o|přehled|overview|"
    r"affair|záležitost|téma[ta]?"
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

    if _BRAIN_PATTERNS.search(user_message):
        categories.add(ToolCategory.BRAIN)

    if _TASK_MGMT_PATTERNS.search(user_message):
        categories.add(ToolCategory.TASK_MGMT)

    if _RESEARCH_PATTERNS.search(user_message):
        categories.add(ToolCategory.RESEARCH)

    # Context-driven: responding to a user_task needs TASK_MGMT tools
    if has_context_task_id:
        categories.add(ToolCategory.TASK_MGMT)

    # Proactive: greeting with pending tasks/meetings → TASK_MGMT
    if _GREETING_PATTERNS.search(user_message):
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
