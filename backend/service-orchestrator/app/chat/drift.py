"""Multi-signal drift detection for agentic loops.

Shared between main chat handler and sub-topic processing.
Detects when the LLM is stuck in loops, ping-ponging between tools,
or drifting across unrelated domains.
"""
from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


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
    4. Domain drift: 3 iterations with 3+ distinct domains, no common domain
    5. Excessive tools: 8+ distinct tools after 4+ iterations
    """
    # Signal 1: Identical tool+args in consecutive iterations
    if consecutive_same >= 2:
        return "opakovaně voláš stejný tool se stejnými argumenty"

    if tool_call_history and len(tool_call_history) >= 2:
        from collections import Counter

        # Signal 2a: Identical tool+args called 2+ times (exact duplicate)
        full_sig_counts = Counter(f"{name}:{args}" for name, args in tool_call_history)
        for sig, count in full_sig_counts.items():
            if count >= 2:
                tool_name = sig.split(":")[0]
                return f"tool '{tool_name}' volán {count}× se stejnými argumenty — odpověz s tím co máš"

        # Signal 2b: Same tool called 3+ times total (even with different args)
        tool_name_counts = Counter(name for name, _ in tool_call_history)
        for tool_name, count in tool_name_counts.items():
            if count >= 3:
                return f"tool '{tool_name}' volán {count}× — opakuješ se, odpověz s tím co máš"

        # Signal 3: Alternating tool pair pattern (A->B->A->B)
        if len(tool_call_history) >= 4:
            names = [name for name, _ in tool_call_history]
            last_four = names[-4:]
            if (last_four[0] == last_four[2] and last_four[1] == last_four[3]
                    and last_four[0] != last_four[1]):
                return (
                    f"tools '{last_four[0]}' a '{last_four[1]}' se opakují v cyklu "
                    f"— odpověz s tím co máš"
                )

    # Signal 4: Domain drift across iterations
    if len(domain_history) >= 3:
        last_three = domain_history[-3:]
        all_domains = set()
        for d in last_three:
            all_domains.update(d)
        common = last_three[0]
        for d in last_three[1:]:
            common = common & d
        if not common and len(all_domains) >= 3:
            return f"tool calls přeskakují mezi nesouvisejícími oblastmi ({', '.join(sorted(all_domains))})"

    # Signal 5: Too many distinct tools
    if iteration >= 4 and len(distinct_tools_used) >= 8:
        return f"použito {len(distinct_tools_used)} různých toolů — příliš rozptýlené"

    return None
