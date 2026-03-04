"""Intent Router — two-pass classification for focused agent routing.

Pass 1: Regex fast-path (0ms) — handles 40-60% of messages.
Pass 2: LLM call on P40 (LOCAL_COMPACT, ~2-3s) — for ambiguous messages.

Fallback: RESEARCH on low confidence or LLM failure.
"""

from __future__ import annotations

import json
import logging

from app.chat.models import ChatCategory, RoutingDecision
from app.chat.tools import ToolCategory
from app.config import settings

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Category → tool names mapping
# ---------------------------------------------------------------------------

_CATEGORY_TOOL_NAMES: dict[ChatCategory, list[str]] = {
    ChatCategory.DIRECT: [],  # No tools
    ChatCategory.RESEARCH: [
        "kb_search", "web_search",
        "memory_recall", "switch_context",
    ],
    ChatCategory.TASK_MGMT: [
        "create_background_task", "list_recent_tasks", "respond_to_user_task",
        "dispatch_coding_agent", "classify_meeting",
        "switch_context", "kb_search",
    ],
    ChatCategory.COMPLEX: [
        "create_background_task", "create_work_plan",
        "update_work_plan_draft", "finalize_work_plan",
        "dispatch_coding_agent", "kb_search",
        "web_search",
        "switch_context",
    ],
    ChatCategory.MEMORY: [
        "kb_search", "kb_delete", "memory_store", "store_knowledge",
        "memory_recall",
    ],
}

# Regex category → ChatCategory mapping
_REGEX_TO_CHAT_CATEGORY: dict[ToolCategory, ChatCategory] = {
    ToolCategory.RESEARCH: ChatCategory.RESEARCH,
    ToolCategory.TASK_MGMT: ChatCategory.TASK_MGMT,
    ToolCategory.FILTERING: ChatCategory.TASK_MGMT,  # Filtering is part of task management
}


def _build_routing_decision(
    category: ChatCategory,
    confidence: float,
    reason: str,
) -> RoutingDecision:
    """Build a RoutingDecision with category-specific defaults."""
    use_cloud = category not in (ChatCategory.DIRECT,)
    max_iterations = {
        ChatCategory.DIRECT: settings.direct_max_iterations,
        ChatCategory.RESEARCH: settings.research_max_iterations,
        ChatCategory.TASK_MGMT: settings.task_mgmt_max_iterations,
        ChatCategory.COMPLEX: settings.complex_max_iterations,
        ChatCategory.MEMORY: settings.memory_max_iterations,
    }.get(category, settings.research_max_iterations)

    return RoutingDecision(
        category=category,
        confidence=confidence,
        reason=reason,
        use_cloud=use_cloud,
        max_iterations=max_iterations,
        tool_names=_CATEGORY_TOOL_NAMES.get(category, []),
    )


async def route_intent(
    user_message: str,
    regex_categories: set[ToolCategory],
) -> RoutingDecision:
    """Two-pass intent classification.

    Pass 1: Regex fast-path.
    - CORE only → DIRECT (greeting, simple question).
    - Single non-CORE category → map directly.

    Pass 2: LLM call for ambiguous cases (multiple regex categories).
    - LOCAL_COMPACT tier, 8k context, no tools, ~2-3s.
    - Fallback to RESEARCH on failure or low confidence.

    Args:
        user_message: Raw user message text.
        regex_categories: Categories from classify_intent() (always includes CORE).

    Returns:
        RoutingDecision with category, confidence, tool_names, etc.
    """
    non_core = regex_categories - {ToolCategory.CORE}

    # Pass 1: Regex fast-path
    if not non_core:
        # Only CORE matched → simple message, no tools needed
        return _build_routing_decision(
            ChatCategory.DIRECT, 0.9, "regex: CORE only → DIRECT"
        )

    if len(non_core) == 1:
        # Single category → map directly
        regex_cat = next(iter(non_core))
        chat_cat = _REGEX_TO_CHAT_CATEGORY.get(regex_cat, ChatCategory.RESEARCH)
        return _build_routing_decision(
            chat_cat, 0.85, f"regex: single match → {chat_cat.value}"
        )

    # Pass 2: LLM classification for ambiguous messages (multiple regex hits)
    try:
        return await _llm_classify(user_message, non_core)
    except Exception as e:
        logger.warning("Intent router LLM failed, falling back to RESEARCH: %s", e)
        return _build_routing_decision(
            ChatCategory.RESEARCH, 0.5, f"LLM fallback: {e}"
        )


async def _llm_classify(
    user_message: str,
    regex_hints: set[ToolCategory],
) -> RoutingDecision:
    """LLM-based classification for ambiguous messages.

    Uses LOCAL_COMPACT tier (P40), 8k context, no tools.
    """
    from app.llm.provider import llm_provider
    from app.models import ModelTier

    categories_list = ", ".join(c.value for c in ChatCategory)
    hints = ", ".join(c.name for c in regex_hints)

    messages = [
        {
            "role": "system",
            "content": (
                "You are an intent classifier. Classify the user message into exactly ONE category.\n"
                f"Categories: {categories_list}\n"
                "Respond with JSON: {\"category\": \"...\", \"confidence\": 0.0-1.0, \"reason\": \"...\"}\n"
                "No markdown, no explanation, just JSON."
            ),
        },
        {
            "role": "user",
            "content": (
                f"Message: {user_message[:500]}\n"
                f"Regex hints (matching patterns): {hints}"
            ),
        },
    ]

    response = await llm_provider.completion(
        messages=messages,
        tier=ModelTier.LOCAL_COMPACT,
        max_tokens=settings.router_max_tokens,
        temperature=0.0,
        timeout=settings.router_timeout,
    )

    content = response.choices[0].message.content.strip()

    # Parse JSON response
    try:
        data = json.loads(content)
    except json.JSONDecodeError:
        # Try to extract JSON from response
        import re
        match = re.search(r'\{[^}]+\}', content)
        if match:
            data = json.loads(match.group())
        else:
            raise ValueError(f"Cannot parse LLM response: {content[:200]}")

    cat_str = data.get("category", "research")
    confidence = float(data.get("confidence", 0.7))
    reason = data.get("reason", "LLM classification")

    try:
        category = ChatCategory(cat_str)
    except ValueError:
        category = ChatCategory.RESEARCH

    if confidence < settings.router_confidence_threshold:
        logger.info("Intent router: low confidence %.2f → fallback RESEARCH", confidence)
        return _build_routing_decision(
            ChatCategory.RESEARCH, confidence, f"low confidence ({confidence:.2f}) → RESEARCH"
        )

    return _build_routing_decision(category, confidence, f"LLM: {reason}")
