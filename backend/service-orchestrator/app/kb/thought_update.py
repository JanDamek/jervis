"""
Post-response Thought Map update — Hebbian reinforcement and thought extraction.

Called fire-and-forget after saving assistant message.
Failure does not block response to user.
"""

import logging
from typing import Optional

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

_TIMEOUT = httpx.Timeout(15.0, connect=5.0)


async def reinforce_activated_thoughts(
    thought_ids: list[str],
    edge_ids: list[str],
) -> None:
    """Hebbian reinforcement of ThoughtNodes/Edges that were in the active context."""
    if not thought_ids and not edge_ids:
        return

    from jervis_contracts import kb_client

    try:
        await kb_client.thought_reinforce(
            caller="orchestrator.thought_update",
            thought_keys=thought_ids,
            edge_keys=edge_ids,
            timeout=15.0,
        )
        logger.info("THOUGHT_UPDATE: reinforced thoughts=%d edges=%d", len(thought_ids), len(edge_ids))
    except Exception as e:
        logger.warning("THOUGHT_UPDATE: reinforce failed: %s", e)


async def extract_and_store_response_thoughts(
    response_text: str,
    client_id: str,
    project_id: Optional[str] = None,
    group_id: Optional[str] = None,
) -> None:
    """
    Extract new thoughts from LLM response and store in Thought Map.

    Uses pattern matching for common decision/problem/insight signals.
    Not an LLM call — lightweight extraction from response text.
    """
    if not response_text or not client_id:
        return

    thoughts = _extract_thoughts_from_text(response_text)
    if not thoughts:
        return

    from jervis_contracts import kb_client

    try:
        await kb_client.thought_create(
            caller="orchestrator.thought_update",
            thoughts=thoughts,
            client_id=client_id,
            project_id=project_id or "",
            group_id=group_id or "",
            timeout=30.0,
        )
        logger.info("THOUGHT_UPDATE: stored %d thoughts from response", len(thoughts))
    except Exception as e:
        logger.warning("THOUGHT_UPDATE: store thoughts failed: %s", e)


def _extract_thoughts_from_text(text: str) -> list[dict]:
    """Pattern-based extraction of decisions/problems/insights from response text.

    Lightweight — no LLM call. Detects common signals in Czech/English text.
    """
    thoughts = []

    # Split into sentences
    lines = text.replace("\n", " ").split(". ")

    decision_signals = ["rozhodl", "rozhodn", "decided", "decision", "zvolil", "vyber", "použij"]
    problem_signals = ["problém", "chyba", "selhá", "nefunguje", "error", "bug", "issue", "fail"]
    insight_signals = ["zjistil", "klíčové", "důležité", "insight", "discovered", "found that", "poznatek"]

    for line in lines:
        line = line.strip()
        if len(line) < 20 or len(line) > 500:
            continue

        lower = line.lower()

        if any(sig in lower for sig in decision_signals):
            thoughts.append({
                "label": line[:80],
                "summary": line[:300],
                "type": "decision",
                "related_entities": [],
            })
        elif any(sig in lower for sig in problem_signals):
            thoughts.append({
                "label": line[:80],
                "summary": line[:300],
                "type": "problem",
                "related_entities": [],
            })
        elif any(sig in lower for sig in insight_signals):
            thoughts.append({
                "label": line[:80],
                "summary": line[:300],
                "type": "insight",
                "related_entities": [],
            })

    # Limit to max 3 thoughts per response
    return thoughts[:3]
