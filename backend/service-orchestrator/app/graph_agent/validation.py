"""Graph validation — structural and semantic checks on TaskGraph.

Validates that the graph is well-formed before execution:
- No cycles
- Depth limits respected
- All edges reference existing vertices
- No orphan vertices (except root)
- Fan-in is within limits
- All TASK vertices have descriptions
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

from app.graph_agent.graph import (
    get_fan_in_count,
    get_incoming_edges,
    get_outgoing_edges,
    has_cycle,
    topological_order,
)
from app.graph_agent.models import (
    GraphVertex,
    TaskGraph,
    VertexStatus,
    VertexType,
)

logger = logging.getLogger(__name__)

# Limits
MAX_TOTAL_VERTICES = 50
MAX_DEPTH = 4
MAX_FAN_IN = 15
MAX_FAN_OUT = 10


@dataclass
class ValidationResult:
    """Result of graph validation."""

    valid: bool = True
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def add_error(self, msg: str) -> None:
        self.errors.append(msg)
        self.valid = False

    def add_warning(self, msg: str) -> None:
        self.warnings.append(msg)


def validate_graph(graph: TaskGraph) -> ValidationResult:
    """Run all validation checks on a graph.

    Returns ValidationResult with errors (fatal) and warnings (non-fatal).
    """
    result = ValidationResult()

    _check_root(graph, result)
    _check_cycles(graph, result)
    _check_vertex_limits(graph, result)
    _check_edge_references(graph, result)
    _check_orphans(graph, result)
    _check_fan_limits(graph, result)
    _check_vertex_content(graph, result)
    _check_depth_limits(graph, result)

    if result.errors:
        logger.error(
            "Graph validation FAILED: %d errors, %d warnings",
            len(result.errors), len(result.warnings),
        )
        for err in result.errors:
            logger.error("  ERROR: %s", err)
    elif result.warnings:
        logger.info(
            "Graph validation passed with %d warnings", len(result.warnings),
        )
    else:
        logger.debug("Graph validation passed (no issues)")

    return result


def _check_root(graph: TaskGraph, result: ValidationResult) -> None:
    """Verify root vertex exists."""
    if graph.root_vertex_id not in graph.vertices:
        result.add_error(
            f"Root vertex {graph.root_vertex_id} not found in graph"
        )


def _check_cycles(graph: TaskGraph, result: ValidationResult) -> None:
    """Verify graph is acyclic (DAG)."""
    if has_cycle(graph):
        result.add_error("Graph contains a cycle — cannot execute")


def _check_vertex_limits(graph: TaskGraph, result: ValidationResult) -> None:
    """Check total vertex count is within limits."""
    count = len(graph.vertices)
    if count > MAX_TOTAL_VERTICES:
        result.add_error(
            f"Too many vertices: {count} (max {MAX_TOTAL_VERTICES})"
        )
    elif count > MAX_TOTAL_VERTICES * 0.8:
        result.add_warning(
            f"Approaching vertex limit: {count}/{MAX_TOTAL_VERTICES}"
        )


def _check_edge_references(graph: TaskGraph, result: ValidationResult) -> None:
    """Verify all edges reference existing vertices."""
    for edge in graph.edges:
        if edge.source_id not in graph.vertices:
            result.add_error(
                f"Edge {edge.id}: source vertex {edge.source_id} not found"
            )
        if edge.target_id not in graph.vertices:
            result.add_error(
                f"Edge {edge.id}: target vertex {edge.target_id} not found"
            )


def _check_orphans(graph: TaskGraph, result: ValidationResult) -> None:
    """Check for vertices with no edges (except root)."""
    for vid, vertex in graph.vertices.items():
        if vid == graph.root_vertex_id:
            continue
        incoming = get_incoming_edges(graph, vid)
        outgoing = get_outgoing_edges(graph, vid)
        if not incoming and not outgoing:
            result.add_warning(
                f"Orphan vertex {vid} ({vertex.title}) — no edges"
            )


def _check_fan_limits(graph: TaskGraph, result: ValidationResult) -> None:
    """Check fan-in and fan-out are within limits."""
    for vid, vertex in graph.vertices.items():
        fan_in = get_fan_in_count(graph, vid)
        if fan_in > MAX_FAN_IN:
            result.add_error(
                f"Vertex {vid} ({vertex.title}): fan-in {fan_in} exceeds "
                f"limit {MAX_FAN_IN}"
            )

        fan_out = len(get_outgoing_edges(graph, vid))
        if fan_out > MAX_FAN_OUT:
            result.add_warning(
                f"Vertex {vid} ({vertex.title}): fan-out {fan_out} exceeds "
                f"recommended limit {MAX_FAN_OUT}"
            )


def _check_vertex_content(graph: TaskGraph, result: ValidationResult) -> None:
    """Check that TASK vertices have descriptions."""
    for vid, vertex in graph.vertices.items():
        if vertex.vertex_type == VertexType.TASK:
            if not vertex.description.strip():
                result.add_error(
                    f"TASK vertex {vid} ({vertex.title}) has no description"
                )
            if not vertex.title.strip():
                result.add_warning(
                    f"Vertex {vid} has no title"
                )


def _check_depth_limits(graph: TaskGraph, result: ValidationResult) -> None:
    """Check that no vertex exceeds max decomposition depth."""
    for vid, vertex in graph.vertices.items():
        if vertex.depth > MAX_DEPTH:
            result.add_error(
                f"Vertex {vid} ({vertex.title}): depth {vertex.depth} "
                f"exceeds limit {MAX_DEPTH}"
            )
