"""Data models for the unified agent graph engine.

Core concepts:
- GraphVertex: a processing unit (decompose, execute, synthesize)
- GraphEdge: connection carrying summary + full context between vertices
- EdgePayload: the data that flows through an edge after source completes
- AgentGraph: the complete DAG (Paměťová mapa or Myšlenková mapa)

Graph types:
- MEMORY_MAP: one global Paměťová mapa per user — all interactions are vertices
- THINKING_MAP: Myšlenková mapa for a specific background task, linked to memory map
"""

from __future__ import annotations

from enum import Enum
from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Enums
# ---------------------------------------------------------------------------


class GraphType(str, Enum):
    """Whether this graph is the Paměťová mapa or Myšlenková mapa."""

    MEMORY_MAP = "memory_map"           # Global Paměťová mapa (one per user)
    THINKING_MAP = "thinking_map"       # Myšlenková mapa for a background task


class VertexType(str, Enum):
    """What kind of processing this vertex performs.

    Each type maps to a distinct responsibility — the LangGraph runner
    dispatches to the correct handler based on this type.
    """

    ROOT = "root"               # Initial request — decomposes into sub-vertices
    PLANNER = "planner"         # Plans approach / breaks down further
    INVESTIGATOR = "investigator"  # Researches context (KB, web, code search)
    EXECUTOR = "executor"       # Performs concrete work (coding, tracker ops)
    VALIDATOR = "validator"     # Verifies results (tests, checks, lint)
    REVIEWER = "reviewer"       # Reviews quality (code review, output review)
    SYNTHESIS = "synthesis"     # Combines results from multiple upstream vertices
    GATE = "gate"               # Decision / approval point
    SETUP = "setup"             # Project scaffolding + environment provisioning

    # Paměťová mapa vertex types
    CLIENT = "client"           # Client organization in hierarchy
    PROJECT = "project"         # Project within client
    ASK_USER = "ask_user"       # Blocked — needs user input via chat
    REQUEST = "request"         # Chat message → agent execution → response
    TASK_REF = "task_ref"       # Reference to a Myšlenková mapa
    INCOMING = "incoming"       # Qualified item from indexation

    # Decomposer vertex types
    TASK = "task"               # Generic task — auto-routed by agent_name
    DECOMPOSE = "decompose"     # Decomposition step (like PLANNER but from decomposer)


class VertexStatus(str, Enum):
    """Lifecycle status of a vertex."""

    PENDING = "pending"         # Waiting for incoming edges (not all satisfied)
    READY = "ready"             # All incoming edges satisfied, can execute
    RUNNING = "running"         # Currently being processed
    COMPLETED = "completed"     # Done, result available
    FAILED = "failed"           # Execution failed
    SKIPPED = "skipped"         # Skipped (e.g. conditional branch not taken)
    CANCELLED = "cancelled"     # Cancelled by user or parent graph cancellation
    BLOCKED = "blocked"         # Waiting for external input (ASK_USER)


class EdgeType(str, Enum):
    """Relationship type between vertices."""

    DEPENDENCY = "dependency"           # Target depends on source result
    DECOMPOSITION = "decomposition"     # Parent → child breakdown
    SEQUENCE = "sequence"               # Strict ordering (no data dependency)


class GraphStatus(str, Enum):
    """Overall status of the task graph."""

    BUILDING = "building"       # Decomposition in progress
    READY = "ready"             # Fully decomposed, ready for execution
    EXECUTING = "executing"     # Vertices being processed
    COMPLETED = "completed"     # All vertices done
    FAILED = "failed"           # Unrecoverable failure
    CANCELLED = "cancelled"     # Cancelled by user


# ---------------------------------------------------------------------------
# Edge Payload — what flows through an edge
# ---------------------------------------------------------------------------


class EdgePayload(BaseModel):
    """Data that flows through an edge after the source vertex completes.

    Each edge carries:
    - summary: concise summary of source vertex result
    - context: full context of source vertex (searchable at target)
    """

    source_vertex_id: str
    source_vertex_title: str
    summary: str                        # Concise result summary
    context: str                        # Full context (searchable)


# ---------------------------------------------------------------------------
# GraphEdge
# ---------------------------------------------------------------------------


class GraphEdge(BaseModel):
    """Directed edge between two vertices.

    Initially created without payload. Once the source vertex completes,
    the payload is filled with summary + context. The target vertex
    becomes READY only when ALL incoming edges have payloads.
    """

    id: str
    source_id: str                      # Source vertex ID
    target_id: str                      # Target vertex ID
    edge_type: EdgeType = EdgeType.DEPENDENCY
    payload: EdgePayload | None = None  # Filled when source completes


# ---------------------------------------------------------------------------
# GraphVertex
# ---------------------------------------------------------------------------


class GraphVertex(BaseModel):
    """A single processing node in the task graph.

    Receives input from incoming edges (fan-in), processes it,
    and produces result that flows to outgoing edges (fan-out).

    If 10 edges converge into this vertex, it receives 10 summaries
    + 10 full contexts from upstream vertices.
    """

    id: str
    title: str                              # Short description (for display)
    description: str                        # Full task / what to solve
    vertex_type: VertexType = VertexType.TASK
    status: VertexStatus = VertexStatus.PENDING

    # Agent assignment
    agent_name: str | None = None           # Which agent handles this

    # Input
    input_request: str = ""                 # What this vertex needs to solve
    incoming_context: list[EdgePayload] = Field(default_factory=list)

    # Output (filled after execution)
    result: str = ""                        # Full result text
    result_summary: str = ""                # Summary for outgoing edges
    local_context: str = ""                 # Context generated during execution

    # Hierarchy (decomposition tree)
    parent_id: str | None = None            # Parent vertex (if decomposed from)
    depth: int = 0                          # Decomposition depth

    # Per-vertex agent state (for resume)
    agent_messages: list[dict] = Field(default_factory=list)   # LLM message history
    agent_iteration: int = 0                                    # Completed iterations

    # Execution metadata
    tools_used: list[str] = Field(default_factory=list)
    token_count: int = 0
    llm_calls: int = 0
    started_at: str | None = None
    completed_at: str | None = None
    error: str | None = None                # Error message if FAILED


# ---------------------------------------------------------------------------
# AgentGraph
# ---------------------------------------------------------------------------


class AgentGraph(BaseModel):
    """Complete execution DAG — Paměťová mapa or Myšlenková mapa.

    Contains all vertices and edges. Provides methods for graph traversal,
    topological ordering, context accumulation, and readiness detection.

    graph_type:
    - MEMORY_MAP: global singleton, all chat interactions + task refs
    - THINKING_MAP: per-task sub-graph, linked to Paměťová mapa
    """

    id: str
    task_id: str
    client_id: str
    project_id: str | None = None

    graph_type: GraphType = GraphType.THINKING_MAP
    parent_graph_id: str | None = None  # Memory map ID (for Myšlenkové mapy)

    root_vertex_id: str
    vertices: dict[str, GraphVertex] = Field(default_factory=dict)
    edges: list[GraphEdge] = Field(default_factory=list)

    status: GraphStatus = GraphStatus.BUILDING

    # Metadata
    created_at: str = ""
    completed_at: str | None = None
    total_token_count: int = 0
    total_llm_calls: int = 0


# Backward compatibility alias
TaskGraph = AgentGraph
