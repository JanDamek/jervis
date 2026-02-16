"""Token-budgeted context composition for LLM prompts.

Composes structured context from the SessionContext (active affair,
parked affairs, user preferences) with dynamic token budget allocation.
"""

from __future__ import annotations

import logging

from app.memory.models import Affair, SessionContext

logger = logging.getLogger(__name__)


def estimate_tokens(text: str) -> int:
    """Rough token estimate: 1 token ~ 4 chars.

    Same heuristic used in respond.py for context estimation.
    """
    return len(text) // 4


def compose_affair_context(
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
        section = _format_active_affair(session.active_affair, budget)
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
        section = _format_user_context(session.user_preferences, budget)
        sections.append(section)
        used_tokens += estimate_tokens(section)

    remaining = max_tokens - used_tokens
    context = "\n\n".join(sections) if sections else ""
    return context, max(0, remaining)


def _format_active_affair(affair: Affair, max_tokens: int) -> str:
    """Format the active affair for the LLM context."""
    lines = [
        f"## Aktuální záležitost: {affair.title}",
        f"**Stav**: {affair.status.value}",
    ]

    if affair.summary:
        lines.append(f"**Shrnutí**: {affair.summary}")

    if affair.key_facts:
        facts_lines = [f"- {k}: {v}" for k, v in affair.key_facts.items()]
        lines.append("**Klíčové fakta**:")
        lines.extend(facts_lines)

    if affair.pending_actions:
        actions = ", ".join(affair.pending_actions)
        lines.append(f"**Čeká na**: {actions}")

    # Add recent messages (trim from oldest if over budget)
    if affair.messages:
        lines.append("")
        lines.append("### Poslední zprávy k této záležitosti:")
        current_text = "\n".join(lines)
        remaining = max_tokens - estimate_tokens(current_text)

        msg_lines: list[str] = []
        # Process messages newest-first, collect until budget exhausted
        for msg in reversed(affair.messages[-20:]):
            content = msg.content[:500]
            line = f"[{msg.role}]: {content}"
            if estimate_tokens(line) > remaining:
                break
            msg_lines.insert(0, line)
            remaining -= estimate_tokens(line)

        lines.extend(msg_lines)

    result = "\n".join(lines)

    # Hard truncation if still over budget
    max_chars = max_tokens * 4
    if len(result) > max_chars:
        result = result[:max_chars] + "\n(...zkráceno)"

    return result


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


def _format_user_context(preferences: dict, max_tokens: int) -> str:
    """Format user preferences and domain knowledge."""
    lines = ["## Uživatelský kontext:"]

    for key, value in preferences.items():
        line = f"- **{key}**: {value}"
        lines.append(line)
        if estimate_tokens("\n".join(lines)) > max_tokens:
            break

    return "\n".join(lines)
