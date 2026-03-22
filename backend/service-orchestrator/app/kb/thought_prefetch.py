"""
Proactive Thought Map traversal for chat context.

Replaces reactive kb_search (LLM must call tool) with proactive
spreading activation (context pre-loaded before LLM call).
"""

import logging

import httpx

from app.config import settings, foreground_headers

logger = logging.getLogger(__name__)

# Token budget allocation for thought context
THOUGHT_SUMMARY_BUDGET = 2000  # tokens for ThoughtNode summaries
ANCHORED_KNOWLEDGE_BUDGET = 2000  # tokens for anchored KnowledgeNodes
RAG_BUDGET = 1000  # tokens for RAG chunks

_TIMEOUT = httpx.Timeout(10.0, connect=3.0)  # Short — don't block chat if GPU is busy


class ThoughtContext:
    """Result of Thought Map traversal for system prompt injection."""

    def __init__(
        self,
        formatted_context: str = "",
        activated_thought_ids: list[str] | None = None,
        activated_edge_ids: list[str] | None = None,
    ):
        self.formatted_context = formatted_context
        self.activated_thought_ids = activated_thought_ids or []
        self.activated_edge_ids = activated_edge_ids or []


async def prefetch_thought_context(
    query: str,
    client_id: str,
    project_id: str | None = None,
    group_id: str | None = None,
) -> ThoughtContext:
    """
    Proactive thought traversal — called before LLM to pre-load context.

    Calls KB service POST /thoughts/traverse and formats results
    as structured markdown for system prompt injection.
    """
    if not query or not client_id:
        return ThoughtContext()

    url = f"{settings.knowledgebase_url}/api/v1/thoughts/traverse"
    payload = {
        "query": query,
        "clientId": client_id,
        "projectId": project_id or "",
        "groupId": group_id or "",
        "maxResults": 20,
        "floor": 0.1,
        "maxDepth": 3,
        "entryTopK": 5,
    }
    headers = foreground_headers("FOREGROUND")

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.post(url, json=payload, headers=headers)
            resp.raise_for_status()
            data = resp.json()
    except Exception as e:
        logger.warning("THOUGHT_PREFETCH: failed query=%s error=%s (%s)", query[:50], e, type(e).__name__)
        return ThoughtContext()

    thoughts = data.get("thoughts", [])
    knowledge = data.get("knowledge", [])
    activated_thought_ids = data.get("activatedThoughtIds", [])
    activated_edge_ids = data.get("activatedEdgeIds", [])

    if not thoughts and not knowledge:
        return ThoughtContext()

    # Format as structured markdown
    parts = []

    # Active thoughts (sorted by pathWeight)
    if thoughts:
        for t in sorted(thoughts, key=lambda x: x.get("pathWeight", 0), reverse=True)[:10]:
            node = t.get("node", {})
            activation = node.get("activationScore", 0)
            label = node.get("label", "?")
            summary = node.get("summary", "")
            t_type = node.get("type", "")
            depth = t.get("depth", 0)
            is_entry = t.get("isEntryPoint", False)

            marker = "→" if is_entry else " "
            parts.append(f"{marker} [{label}] ({t_type}, aktivace: {activation:.2f}): {summary}")

    # Anchored knowledge nodes
    if knowledge:
        parts.append("")
        parts.append("Souvisejicí znalosti:")
        for k in sorted(knowledge, key=lambda x: x.get("pathWeight", 0), reverse=True)[:8]:
            node = k.get("node", {})
            label = node.get("label", "?")
            k_type = node.get("type", "")
            desc = node.get("description", "")[:200]
            parts.append(f"  - {label} ({k_type}): {desc}")

    formatted = "\n".join(parts)

    # Rough token budget check (chars / 3 ≈ tokens for Czech)
    max_chars = (THOUGHT_SUMMARY_BUDGET + ANCHORED_KNOWLEDGE_BUDGET + RAG_BUDGET) * 3
    if len(formatted) > max_chars:
        formatted = formatted[:max_chars] + "\n  ... (zkráceno)"

    logger.info(
        "THOUGHT_PREFETCH: client=%s thoughts=%d knowledge=%d context_len=%d",
        client_id, len(thoughts), len(knowledge), len(formatted),
    )

    return ThoughtContext(
        formatted_context=formatted,
        activated_thought_ids=activated_thought_ids,
        activated_edge_ids=activated_edge_ids,
    )
