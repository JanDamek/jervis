"""Retention policy — decides what agent results to persist to KB vs discard.

Rules:
- User decisions/preferences → always persist to KB (semantic memory)
- Learned procedures → persist to KB (procedural memory)
- Routine operational results → context_store only (30-day TTL)
- Errors/failures → context_store only (for debugging)
"""

from __future__ import annotations

import logging
import re

from app.models import AgentOutput

logger = logging.getLogger(__name__)

# Keywords indicating the output contains user decisions/preferences
_DECISION_INDICATORS = [
    "user decided", "user chose", "user prefers", "preference set",
    "decision:", "approved:", "rule:", "convention:",
    "always use", "never use", "standard process",
]

# Keywords indicating learned process/workflow
_PROCEDURE_INDICATORS = [
    "workflow:", "process:", "procedure:", "steps:",
    "after completion", "before deploy", "review process",
]

# Agent names whose output typically contains persistable knowledge
_KB_WORTHY_AGENTS = {
    "legal", "financial", "research", "learning",
    "code_review", "security",
}


def should_persist_to_kb(output: AgentOutput) -> bool:
    """Determine if an agent's output should be persisted to the KB.

    Returns True if the output contains information worth storing
    permanently in semantic memory.
    """
    if not output.success:
        return False

    if not output.result:
        return False

    result_lower = output.result.lower()

    # User decisions are always KB-worthy
    if any(ind in result_lower for ind in _DECISION_INDICATORS):
        return True

    # Learned procedures are KB-worthy
    if any(ind in result_lower for ind in _PROCEDURE_INDICATORS):
        return True

    # Certain agents typically produce KB-worthy output
    if output.agent_name in _KB_WORTHY_AGENTS:
        # Only if the result is substantial (not just "done" or "ok")
        return len(output.result) > 100

    return False


def extract_kb_facts(output: AgentOutput) -> list[dict]:
    """Extract structured facts from an agent's output for KB ingestion.

    Returns a list of fact dicts, each with:
    - kind: str (convention, decision, process, lesson_learned, etc.)
    - content: str (the fact text)
    - source_agent: str
    - confidence: float
    """
    if not output.success or not output.result:
        return []

    facts: list[dict] = []
    result_lower = output.result.lower()

    # Extract decisions
    if any(ind in result_lower for ind in _DECISION_INDICATORS):
        facts.append({
            "kind": "decision",
            "content": output.result[:500],
            "source_agent": output.agent_name,
            "confidence": output.confidence,
        })

    # Extract procedures
    if any(ind in result_lower for ind in _PROCEDURE_INDICATORS):
        facts.append({
            "kind": "process",
            "content": output.result[:500],
            "source_agent": output.agent_name,
            "confidence": output.confidence,
        })

    # Extract conventions from code review
    if output.agent_name == "code_review":
        convention_patterns = re.findall(
            r"(?:convention|rule|standard):\s*(.+?)(?:\n|$)",
            output.result,
            re.IGNORECASE,
        )
        for conv in convention_patterns[:5]:
            facts.append({
                "kind": "convention",
                "content": conv.strip()[:200],
                "source_agent": output.agent_name,
                "confidence": output.confidence,
            })

    # Extract lessons learned from failures
    if output.agent_name in ("test", "security") and "lesson" in result_lower:
        facts.append({
            "kind": "lesson_learned",
            "content": output.result[:500],
            "source_agent": output.agent_name,
            "confidence": output.confidence,
        })

    return facts
