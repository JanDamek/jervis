"""Token-budgeted context composition for LLM prompts.

Composes structured context from the SessionContext (active affair,
parked affairs, user preferences) with dynamic token budget allocation.

Uses LLM-based content reduction (via content_reducer) instead of hard
truncation when individual messages or facts exceed their budget slice.
"""

from __future__ import annotations

import logging

from app.config import estimate_tokens
from app.memory.content_reducer import reduce_for_prompt, reduce_messages_for_prompt
from app.memory.models import Affair, SessionContext

logger = logging.getLogger(__name__)


async def compose_affair_context(
    session: SessionContext,
    max_tokens: int = 8000,
) -> tuple[str, int]:
    """Compose context for LLM prompt from session context.

    Token budget allocation:
      - 40% active affair (summary + key_facts + recent messages)
      - 10% parked affairs (titles + pending_actions only)
      - 15% user context (preferences, domain knowledge)
      - 35% remaining for KB evidence (filled by caller)

    Returns:
        (formatted_context, remaining_tokens) — remaining tokens for KB evidence.
    """
    sections: list[str] = []
    used_tokens = 0

    # 1. Active affair (40%)
    if session.active_affair:
        budget = int(max_tokens * 0.4)
        section = await _format_active_affair(session.active_affair, budget)
        sections.append(section)
        used_tokens += estimate_tokens(section)

    # 2. Parked affairs (10%)
    if session.parked_affairs:
        budget = int(max_tokens * 0.1)
        section = _format_parked_affairs(session.parked_affairs, budget)
        sections.append(section)
        used_tokens += estimate_tokens(section)

    # 3. User context (15%)
    if session.user_preferences:
        budget = int(max_tokens * 0.15)
        section = await _format_user_context(session.user_preferences, budget)
        sections.append(section)
        used_tokens += estimate_tokens(section)

    remaining = max_tokens - used_tokens
    context = "\n\n".join(sections) if sections else ""
    return context, max(0, remaining)


async def _format_active_affair(affair: Affair, max_tokens: int) -> str:
    """Format the active affair for the LLM context."""
    lines = [
        f"## Aktuální záležitost: {affair.title}",
        f"**Stav**: {affair.status.value}",
    ]

    if affair.summary:
        summary = affair.summary
        summary_tokens = estimate_tokens(summary)
        # If summary alone exceeds 1/3 of budget, reduce via LLM
        budget_third = max_tokens // 3
        if summary_tokens > budget_third:
            summary = await reduce_for_prompt(summary, budget_third, "summary")
        lines.append(f"**Shrnutí**: {summary}")

    if affair.key_facts:
        facts_text = "\n".join(f"- {k}: {v}" for k, v in affair.key_facts.items())
        facts_tokens = estimate_tokens(facts_text)
        budget_quarter = max_tokens // 4
        if facts_tokens > budget_quarter:
            facts_text = await reduce_for_prompt(facts_text, budget_quarter, "key_facts")
        lines.append("**Klíčové fakta**:")
        lines.append(facts_text)

    if affair.pending_actions:
        actions = ", ".join(affair.pending_actions)
        lines.append(f"**Čeká na**: {actions}")

    # Add recent messages — budget-aware via reduce_messages_for_prompt
    if affair.messages:
        lines.append("")
        lines.append("### Poslední zprávy k této záležitosti:")
        current_text = "\n".join(lines)
        remaining = max_tokens - estimate_tokens(current_text)

        if remaining > 100:
            messages_text = await reduce_messages_for_prompt(
                affair.messages, token_budget=remaining,
            )
            if messages_text:
                lines.append(messages_text)

    return "\n".join(lines)


def _format_parked_affairs(affairs: list[Affair], max_tokens: int) -> str:
    """Format parked affairs as a brief overview."""
    lines = ["## Odložené záležitosti:"]

    for affair in affairs[:10]:  # Cap at 10
        pending = affair.pending_actions[0] if affair.pending_actions else "žádná akce"
        lines.append(f"- **{affair.title}** ({affair.status.value}): {pending}")

        # Check budget
        if estimate_tokens("\n".join(lines)) > max_tokens:
            lines.append(f"... a dalších {len(affairs) - len(lines) + 1}")
            break

    return "\n".join(lines)


async def _format_user_context(preferences: dict, max_tokens: int) -> str:
    """Format user preferences and domain knowledge."""
    lines = ["## Uživatelský kontext:"]

    for key, value in preferences.items():
        value_str = str(value)
        value_tokens = estimate_tokens(value_str)
        # If a single preference value is very large, reduce it
        per_item_budget = max(max_tokens // max(len(preferences), 1), 100)
        if value_tokens > per_item_budget:
            value_str = await reduce_for_prompt(value_str, per_item_budget, "context")
        line = f"- **{key}**: {value_str}"
        lines.append(line)
        if estimate_tokens("\n".join(lines)) > max_tokens:
            break

    return "\n".join(lines)
