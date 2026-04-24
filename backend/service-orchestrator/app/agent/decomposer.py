"""Graph decomposition engine — reactive/lazy progressive decomposition.

Instead of upfront LLM-driven decomposition, vertices decompose themselves
at runtime via the `decompose_task` tool. This module provides:
- create_child_vertices(): creates children from a parent when it decides to decompose
- _format_evidence(): evidence formatting (kept from original)

The old decompose_root() / _llm_decompose() / _build_subgraph() are removed.
Decomposition now happens inside the agentic tool loop when a vertex calls
the `decompose_task` tool.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.agent.models import (
    EdgeType,
    GraphVertex,
    AgentGraph,
    VertexStatus,
    VertexType,
)

if TYPE_CHECKING:
    from app.models import EvidencePack
from app.agent.graph import add_edge, add_vertex, create_task_graph

logger = logging.getLogger(__name__)

# Max vertices per single decompose_task call
MAX_VERTICES_PER_DECOMPOSE = 10
# Max total vertices in a graph — prevents runaway growth
MAX_TOTAL_VERTICES = 150
# Max decomposition depth (root → children → grandchildren → ...)
MAX_DECOMPOSE_DEPTH = 5


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def create_child_vertices(
    graph: AgentGraph,
    parent_vertex: GraphVertex,
    children_specs: list[dict],
) -> list[GraphVertex]:
    """Create child vertices from a parent vertex when it decides to decompose.

    Called by the decompose_task tool handler in the agentic loop.
    Each child is a TASK vertex that will try direct resolution first.

    Args:
        graph: The agent graph
        parent_vertex: The vertex that decided to decompose
        children_specs: List of dicts with 'title' and 'description'

    Returns:
        List of created child vertices

    Raises:
        ValueError: If depth or vertex limits exceeded
    """
    if parent_vertex.depth >= MAX_DECOMPOSE_DEPTH:
        raise ValueError(
            f"Max decomposition depth ({MAX_DECOMPOSE_DEPTH}) reached for "
            f"vertex '{parent_vertex.title}' at depth {parent_vertex.depth}"
        )

    if len(graph.vertices) >= MAX_TOTAL_VERTICES:
        raise ValueError(
            f"Max total vertices ({MAX_TOTAL_VERTICES}) reached — "
            f"graph has {len(graph.vertices)} vertices"
        )

    # Enforce per-call limit
    specs = children_specs[:MAX_VERTICES_PER_DECOMPOSE]

    # Enforce remaining capacity
    remaining = MAX_TOTAL_VERTICES - len(graph.vertices)
    if remaining < len(specs):
        specs = specs[:remaining]

    created: list[GraphVertex] = []

    for spec in specs:
        title = (spec.get("title") or "Untitled").strip()
        description = (spec.get("description") or "").strip()

        child = add_vertex(
            graph=graph,
            title=title,
            description=description,
            vertex_type=VertexType.TASK,
            parent_id=parent_vertex.id,
            input_request=description,
            client_id=parent_vertex.client_id,
            project_id=parent_vertex.project_id,
        )

        # DECOMPOSITION edge for traceability (doesn't gate readiness)
        add_edge(graph, parent_vertex.id, child.id, EdgeType.DECOMPOSITION)

        # Child has no DEPENDENCY edges → immediately READY
        child.status = VertexStatus.READY
        created.append(child)

    # Verify no cycles introduced
    if has_cycle(graph):
        # Shouldn't happen with simple parent→child edges, but safety check
        logger.error(
            "Cycle detected after creating children for vertex %s — "
            "removing children",
            parent_vertex.id,
        )
        for child in created:
            if child.id in graph.vertices:
                del graph.vertices[child.id]
        graph.edges = [
            e for e in graph.edges
            if e.source_id != parent_vertex.id
            or e.target_id not in {c.id for c in created}
        ]
        raise ValueError("Decomposition would create a cycle")

    logger.info(
        "DECOMPOSE_TASK | graph=%s | parent=%s | title='%s' | "
        "children=%d | depth=%d→%d | total_vertices=%d",
        graph.id, parent_vertex.id, parent_vertex.title,
        len(created), parent_vertex.depth, parent_vertex.depth + 1,
        len(graph.vertices),
    )

    return created


# ---------------------------------------------------------------------------
# Evidence formatting (kept from original)
# ---------------------------------------------------------------------------


def _format_evidence(evidence: dict) -> str:
    """Format evidence pack into text for LLM prompt."""
    parts: list[str] = []

    kb_results = evidence.get("kb_results", [])
    if kb_results:
        parts.append(f"### KB Results ({len(kb_results)} hits)")
        for r in kb_results[:5]:
            title = r.get("title", r.get("summary", "untitled"))
            parts.append(f"- {title}")

    facts = evidence.get("facts", [])
    if facts:
        parts.append("### Known Facts")
        for f in facts[:10]:
            parts.append(f"- {f}")

    unknowns = evidence.get("unknowns", [])
    if unknowns:
        parts.append("### Unknowns")
        for u in unknowns[:5]:
            parts.append(f"- {u}")

    chat_summary = evidence.get("chat_history_summary", "")
    if chat_summary:
        parts.append(f"### Conversation Context\n{chat_summary}")

    existing_resources = evidence.get("existing_resources", "")
    if existing_resources:
        parts.append(f"### Existing Resources\n{existing_resources}")

    return "\n".join(parts) if parts else "(no evidence available)"
