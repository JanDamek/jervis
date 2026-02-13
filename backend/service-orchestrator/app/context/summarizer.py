"""Summarizer — progressive summarization for delegation results.

Agents are instructed to respond as compactly as possible (via communication
protocol in BaseAgent._build_system_prompt), so their outputs are already
concise. We do NOT hard-truncate agent outputs — the orchestrator gets the
full (compact) result from every agent.

Only session memory entries have a hard 200-char limit (metadata, not content).
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


def summarize_for_parent(result: str) -> str:
    """Pass agent result to parent as-is.

    Agents are instructed via communication protocol to respond compactly.
    No truncation — orchestrator needs the full content to make decisions.
    """
    if not result:
        return "(no result)"
    return result


def summarize_for_session_memory(
    result: str,
    agent_name: str,
    success: bool,
    max_chars: int = 200,
) -> str:
    """Create a compact summary for session memory storage.

    Max 200 chars per the SessionEntry spec.
    """
    status = "OK" if success else "FAILED"
    prefix = f"[{agent_name}:{status}] "
    remaining = max_chars - len(prefix)

    if not result:
        return f"{prefix}(no result)"

    # Take first line or first N chars
    first_line = result.split("\n")[0].strip()
    if len(first_line) <= remaining:
        return prefix + first_line

    return prefix + first_line[:remaining - 3] + "..."


def build_delegation_context(
    evidence_pack: dict | None,
    session_memory_text: str,
    procedure_text: str,
    token_budget: int,
) -> str:
    """Assemble context for a delegation within a token budget.

    Priority order:
    1. Session memory (recent, high signal)
    2. Procedure context (if exists)
    3. Evidence pack KB results
    4. Evidence pack tracker artifacts

    Args:
        evidence_pack: Evidence gathered by evidence_pack node
        session_memory_text: Formatted session memory text
        procedure_text: Formatted procedure text
        token_budget: Max tokens for this context block

    Returns:
        Assembled context string within budget
    """
    # Rough: 1 token ≈ 4 chars
    char_budget = token_budget * 4
    parts: list[str] = []
    used = 0

    # 1. Session memory (max 20% of budget)
    if session_memory_text:
        sm_budget = min(len(session_memory_text), char_budget // 5)
        parts.append(session_memory_text[:sm_budget])
        used += sm_budget

    # 2. Procedure context (max 15% of budget)
    if procedure_text:
        proc_budget = min(len(procedure_text), char_budget * 15 // 100)
        parts.append(procedure_text[:proc_budget])
        used += proc_budget

    # 3. Evidence pack (remaining budget)
    if evidence_pack:
        remaining = char_budget - used

        # KB results
        kb_results = evidence_pack.get("kb_results", [])
        for kr in kb_results:
            content = kr.get("content", "")
            if content and remaining > 100:
                chunk = content[:min(len(content), remaining // 2)]
                parts.append(f"KB: {chunk}")
                remaining -= len(chunk) + 4

        # Tracker artifacts
        tracker_artifacts = evidence_pack.get("tracker_artifacts", [])
        for ta in tracker_artifacts:
            ref = ta.get("ref", "?")
            content = ta.get("content", "")
            if content and remaining > 100:
                chunk = content[:min(len(content), remaining // 3)]
                parts.append(f"[{ref}]: {chunk}")
                remaining -= len(chunk) + len(ref) + 4

    return "\n\n".join(parts)


def get_token_budget(depth: int) -> int:
    """Get token budget for a given delegation depth.

    Depth 0 (orchestrator): 48k (GPU sweet spot)
    Depth 1: 16k
    Depth 2: 8k
    Depth 3-4: 4k
    """
    from app.config import settings

    budgets = {
        0: settings.token_budget_depth_0,
        1: settings.token_budget_depth_1,
        2: settings.token_budget_depth_2,
    }
    return budgets.get(depth, settings.token_budget_depth_3)
