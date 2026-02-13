"""Summarization utilities for agent outputs and session memory.

Provides functions to create compact summaries of agent results for:
1. Parent agent consumption (full but compact)
2. Session memory storage (max 200 chars)
3. Progress reporting to UI
"""

from __future__ import annotations

import logging

from app.models import AgentOutput

logger = logging.getLogger(__name__)


def summarize_agent_output(output: AgentOutput) -> str:
    """Create a compact summary of an agent's output for the parent agent.

    Does not truncate — the agent is instructed to respond compactly.
    This function structures the output in the standard communication
    protocol format: STATUS/RESULT/ARTIFACTS/ISSUES/CONFIDENCE.
    """
    parts = [
        f"STATUS: {'SUCCESS' if output.success else 'FAILED'}",
    ]

    if output.result:
        parts.append(f"RESULT: {output.result}")

    if output.changed_files:
        files_str = ", ".join(output.changed_files[:10])
        if len(output.changed_files) > 10:
            files_str += f" (+{len(output.changed_files) - 10} more)"
        parts.append(f"CHANGED_FILES: {files_str}")

    if output.artifacts:
        arts_str = ", ".join(output.artifacts[:5])
        if len(output.artifacts) > 5:
            arts_str += f" (+{len(output.artifacts) - 5} more)"
        parts.append(f"ARTIFACTS: {arts_str}")

    if output.needs_verification:
        parts.append("NEEDS_VERIFICATION: true")

    parts.append(f"CONFIDENCE: {output.confidence:.2f}")

    return "\n".join(parts)


def summarize_for_session(output: AgentOutput) -> str:
    """Create a very short summary for session memory (max 200 chars).

    Session memory entries must be brief — they are loaded at the start
    of every orchestration and contribute to the context budget.
    """
    status = "OK" if output.success else "FAIL"
    result_preview = output.result[:150] if output.result else "no result"

    # Remove newlines for compactness
    result_preview = result_preview.replace("\n", " ").strip()

    summary = f"[{output.agent_name}:{status}] {result_preview}"
    return summary[:200]


def summarize_for_progress(output: AgentOutput) -> str:
    """Create a one-line summary for UI progress reporting."""
    status = "✓" if output.success else "✗"
    result_preview = (output.result or "")[:100].replace("\n", " ").strip()
    return f"{status} {output.agent_name}: {result_preview}"


def summarize_delegation_plan(
    agent_names: list[str],
    domain: str,
    task_summary: str,
) -> str:
    """Create a progress-friendly summary of the delegation plan."""
    agents_str = " → ".join(agent_names)
    task_preview = task_summary[:80].replace("\n", " ").strip()
    return f"[{domain}] {task_preview} | Agents: {agents_str}"
