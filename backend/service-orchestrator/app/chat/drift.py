"""Multi-signal drift detection for agentic loops.

Shared between main chat handler and sub-topic processing.
Detects when the LLM is stuck in loops, ping-ponging between tools,
or drifting across unrelated domains.
"""
from __future__ import annotations

import logging

logger = logging.getLogger(__name__)

# Workflow chains: sets of domains that naturally occur together.
# If all observed domains fit within a single chain, it's NOT drift —
# it's a coherent multi-step workflow (e.g. search → store → dispatch).
_WORKFLOW_CHAINS: list[set[str]] = [
    {"search", "memory", "task"},       # research → store findings → create task
    {"search", "memory"},               # research → store
    {"search", "task"},                 # research → dispatch
    {"memory", "task"},                 # recall → act
    {"search", "memory", "scope"},      # research with context switch
    {"search", "guidelines"},           # research → update guidelines
    {"memory", "task", "scope"},        # context switch → recall → act
]


def _domains_form_workflow(all_domains: set[str]) -> bool:
    """Check if all observed domains fit within a known workflow chain."""
    for chain in _WORKFLOW_CHAINS:
        if all_domains <= chain:
            return True
    return False


def detect_drift(
    consecutive_same: int,
    domain_history: list[set[str]],
    distinct_tools_used: set[str],
    iteration: int,
    tool_call_history: list[tuple[str, str]] | None = None,
) -> str | None:
    """Multi-signal drift detection.

    Returns human-readable reason if drift detected, None otherwise.

    Signals:
    1. Consecutive same: 2x identical tool+args -> stuck in loop
    2. Same tool repeated: 3+ times across ANY iterations
    3. Alternating tool pair: A->B->A->B pattern (same-domain ping-pong)
    4. Domain drift: 4+ iterations with 3+ distinct domains outside workflow chains
    5. Excessive tools: 8+ distinct tools after 4+ iterations
    """
    # Signal 1: Identical tool+args in consecutive iterations
    if consecutive_same >= 2:
        return "you are repeatedly calling the same tool with the same arguments"

    if tool_call_history and len(tool_call_history) >= 2:
        from collections import Counter

        # Signal 2a: Identical tool+args called 2+ times (exact duplicate)
        full_sig_counts = Counter(f"{name}:{args}" for name, args in tool_call_history)
        for sig, count in full_sig_counts.items():
            if count >= 2:
                tool_name = sig.split(":")[0]
                return f"tool '{tool_name}' called {count}x with same arguments — respond with what you have"

        # Signal 2b: Same tool called 8+ times total (even with different args)
        tool_name_counts = Counter(name for name, _ in tool_call_history)
        for tool_name, count in tool_name_counts.items():
            if count >= 8:
                return f"tool '{tool_name}' called {count}x — you are repeating, respond with what you have"

        # Signal 3: Alternating tool pair pattern (A->B->A->B)
        if len(tool_call_history) >= 4:
            names = [name for name, _ in tool_call_history]
            last_four = names[-4:]
            if (last_four[0] == last_four[2] and last_four[1] == last_four[3]
                    and last_four[0] != last_four[1]):
                return (
                    f"tools '{last_four[0]}' and '{last_four[1]}' are repeating in a cycle "
                    f"— respond with what you have"
                )

    # Signal 4: Domain drift across iterations
    # Only trigger after 4+ iterations AND when domains don't form a known workflow.
    if len(domain_history) >= 4:
        last_four = domain_history[-4:]
        all_domains = set()
        for d in last_four:
            all_domains.update(d)
        # Ignore "unknown" domain — it's a catch-all, not a real signal
        all_domains.discard("unknown")
        common = last_four[0]
        for d in last_four[1:]:
            common = common & d
        if not common and len(all_domains) >= 3 and not _domains_form_workflow(all_domains):
            return f"tool calls jumping between unrelated domains ({', '.join(sorted(all_domains))})"

    # Signal 5: Too many distinct tools
    if iteration >= 4 and len(distinct_tools_used) >= 8:
        return f"{len(distinct_tools_used)} distinct tools used — too scattered"

    return None
