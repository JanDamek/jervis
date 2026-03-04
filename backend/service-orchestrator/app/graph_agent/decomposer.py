"""Graph decomposition engine — LLM-driven vertex/edge creation.

Takes a request (via root vertex or any DECOMPOSE vertex) and breaks it down
into sub-vertices connected by edges. Supports recursive decomposition:
a vertex can itself be decomposed into a sub-graph.

Uses the same LLM call patterns as plan_delegations (llm_with_cloud_fallback
+ parse_json_response) to maintain consistency.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.config import settings
from app.graph.nodes._helpers import llm_with_cloud_fallback, parse_json_response
from app.graph_agent.graph import (
    add_edge,
    add_vertex,
    get_children,
    has_cycle,
    topological_order,
)
from app.graph_agent.models import (
    EdgeType,
    GraphStatus,
    GraphVertex,
    TaskGraph,
    VertexStatus,
    VertexType,
)
from app.graph_agent.progress import report_decomposition_progress

if TYPE_CHECKING:
    from app.models import EvidencePack

logger = logging.getLogger(__name__)

# Max vertices per single decomposition call
MAX_VERTICES_PER_DECOMPOSE = 10
# Max total vertices in a graph (higher to support deep recursive decomposition)
MAX_TOTAL_VERTICES = 200
# Max decomposition depth (deep enough for large projects: root → modules → sub-modules → components → tasks)
MAX_DECOMPOSE_DEPTH = 8


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


async def decompose_root(
    graph: TaskGraph,
    state: dict,
    evidence: dict | None = None,
    guidelines: str = "",
) -> TaskGraph:
    """Decompose the root vertex into sub-vertices.

    This is the entry point for graph construction. Takes the root vertex's
    description (the user's request) and creates the initial graph structure.

    Returns the modified graph with new vertices and edges.
    """
    root = graph.vertices.get(graph.root_vertex_id)
    if not root:
        raise ValueError(f"Root vertex {graph.root_vertex_id} not found")

    await report_decomposition_progress(
        graph, "Analysing request structure…", depth=0,
    )

    # Build context from evidence
    evidence_text = _format_evidence(evidence) if evidence else ""

    # Decompose root
    vertices_data = await _llm_decompose(
        vertex=root,
        state=state,
        evidence_text=evidence_text,
        guidelines=guidelines,
        is_root=True,
    )

    if not vertices_data:
        logger.warning("Root decomposition returned no vertices, using single-vertex plan")
        return _single_vertex_fallback(graph, root)

    # Build sub-graph from LLM output
    _build_subgraph(graph, root, vertices_data)

    # Mark root as completed (decomposition done)
    root.status = VertexStatus.COMPLETED
    root.result = f"Decomposed into {len(vertices_data)} vertices"
    root.result_summary = root.result

    # Update graph status
    graph.status = GraphStatus.READY

    # Validate
    if has_cycle(graph):
        logger.error("Decomposition produced a cycle — falling back to single vertex")
        return _single_vertex_fallback(graph, root)

    await report_decomposition_progress(
        graph,
        f"Decomposed into {len(graph.vertices) - 1} vertices",
        depth=0,
    )

    return graph


async def decompose_vertex(
    graph: TaskGraph,
    vertex_id: str,
    state: dict,
    guidelines: str = "",
) -> TaskGraph:
    """Recursively decompose a DECOMPOSE-type vertex into sub-vertices.

    Called during execution when a vertex is marked as DECOMPOSE type
    and needs further breakdown before its children can execute.

    Returns the modified graph with new vertices and edges.
    """
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        raise ValueError(f"Vertex {vertex_id} not found")
    if vertex.depth >= MAX_DECOMPOSE_DEPTH:
        logger.warning(
            "Max decomposition depth (%d) reached for vertex %s",
            MAX_DECOMPOSE_DEPTH, vertex_id,
        )
        # Convert to TASK so it executes directly
        vertex.vertex_type = VertexType.TASK
        return graph
    if len(graph.vertices) >= MAX_TOTAL_VERTICES:
        logger.warning("Max total vertices (%d) reached", MAX_TOTAL_VERTICES)
        vertex.vertex_type = VertexType.TASK
        return graph

    await report_decomposition_progress(
        graph,
        f"Decomposing: {vertex.title}",
        depth=vertex.depth,
    )

    # Build context from incoming edges
    context_parts = []
    for payload in vertex.incoming_context:
        context_parts.append(
            f"[{payload.source_vertex_title}] {payload.summary}"
        )
    upstream_context = "\n".join(context_parts)

    vertices_data = await _llm_decompose(
        vertex=vertex,
        state=state,
        evidence_text=upstream_context,
        guidelines=guidelines,
        is_root=False,
    )

    if not vertices_data:
        # No sub-decomposition — convert to TASK
        vertex.vertex_type = VertexType.TASK
        return graph

    _build_subgraph(graph, vertex, vertices_data)

    # Mark the DECOMPOSE vertex as completed
    vertex.status = VertexStatus.COMPLETED
    vertex.result = f"Decomposed into {len(vertices_data)} sub-vertices"
    vertex.result_summary = vertex.result

    return graph


# ---------------------------------------------------------------------------
# LLM decomposition call
# ---------------------------------------------------------------------------


_DECOMPOSE_SYSTEM_PROMPT = """You are the Task Decomposition Engine. Your job is to break down a request into discrete processing vertices (sub-tasks) with dependencies between them.

CRITICAL: DISTINGUISH DISCUSSION FROM IMPLEMENTATION

Before decomposing, determine if the user is:
A) **Discussing / specifying requirements** — vague or incomplete request, no explicit implementation command
   → Return a SINGLE "executor" vertex that responds conversationally: asks clarifying questions, suggests options, helps refine requirements. Do NOT create setup/executor/coding vertices. Examples:
   - "Klient by chtěl aplikaci na správu domácí knihovny" → discussion (ask: what platforms? what features? what storage?)
   - "Mělo by to mít konektivitu na databázi knih" → discussion (refine: which API? what data to fetch?)
   - "I want to build an e-commerce site" → discussion (ask: what products? what payment? mobile app?)

B) **Commanding implementation** — explicit instruction to build/implement/create with sufficient context
   → Decompose into proper vertices with setup, coding, validation etc. Examples:
   - "Tak to implementuj" / "Build it" / "Create the project" → implementation (requirements accumulated in memories)
   - "Napiš aplikaci pro domácí knihovnu v KMP s PostgreSQL backendem" → implementation (clear spec)
   - "Fix the login bug in auth.py line 42" → implementation (concrete task)

When requirements are vague: prefer a single conversational vertex over a complex graph.
When requirements are clear + user commands implementation: create the full workflow.

Each vertex has a RESPONSIBILITY TYPE that determines its system prompt, default tools, and behavior:
- "investigator" — researches context (KB search, web search, codebase exploration, repository info)
- "planner" — plans approach, breaks down further (codebase info, KB stats)
- "executor" — performs concrete work: coding, KB writes, dispatch coding agent, scheduling
- "task" — alias for executor (general-purpose work)
- "validator" — verifies results: checks code, branches, commits
- "reviewer" — reviews quality: code review, best practices, tech stack
- "gate" — decision/approval point (proceed or stop)
- "setup" — project scaffolding + environment provisioning (environment CRUD, deploy, coding agent). ONLY use when user explicitly commands implementation and requirements are sufficiently clear.
- "decompose" — needs further breakdown before execution

Each vertex gets a DEFAULT TOOL SET matching its responsibility. Vertices can also REQUEST ADDITIONAL TOOLS at runtime if needed.

Vertices are connected by edges — when vertex A completes, its result summary + full context flows to vertex B through the edge.

Respond with a JSON object:
{
  "vertices": [
    {
      "title": "<short title>",
      "description": "<what this vertex needs to accomplish>",
      "type": "<investigator|planner|executor|task|validator|reviewer|gate|setup|decompose>",
      "agent": "<agent name or null for auto>",
      "depends_on": [0, 1]
    }
  ],
  "synthesis": {
    "title": "<synthesis vertex title>",
    "description": "<how to combine results>"
  }
}

Rules:
- Choose the CORRECT vertex type for each step — this determines which tools it gets
- "depends_on": array of vertex indices that must complete BEFORE this vertex starts
- The synthesis vertex (if provided) automatically depends on ALL other vertices
- Maximum %d vertices per decomposition
- Use the MINIMUM number of vertices needed — don't over-decompose
- Each vertex should be independently executable with its input context
- If the request is simple enough for one vertex, return just one vertex with no synthesis
- Vertex descriptions should be self-contained (include relevant details)
- Typical patterns:
  - investigator → executor → validator (research → do → verify)
  - planner → multiple executors → reviewer (plan → parallel work → review)
  - investigator → gate → executor (research → decide → act)
- For vague/discussion requests: return ONE executor vertex that asks clarifying questions
- Discussion vertex description MUST include: "After each confirmed decision, call store_knowledge with category 'specification' to persist it in KB. This ensures nothing is lost across sessions."
- When user mentions another project (cross-reference), description MUST include: "Use target_project_name in store_knowledge to tag for the referenced project."

Available agents: research, coding, git, code_review, test, documentation, devops, project_management, communication, email, calendar, tracker, wiki, security, legal, financial, administrative, personal, learning

CODING AGENT SELECTION:
When executor/setup vertices dispatch coding tasks via `dispatch_coding_agent`, Kotlin selects the coding agent by complexity:
- SIMPLE tasks → Aider (fast, local GPU, free)
- MEDIUM/COMPLEX tasks → OpenHands (local GPU, free)
- CRITICAL tasks → Claude CLI (cloud API, paid)
- Kilo → alternative premium agent (if explicitly configured)
The agent_preference parameter can override this: "auto" (default tier-based), "aider", "openhands", "claude", "kilo".
Prefer "auto" unless the user explicitly requested a specific agent.

INTERACTIVE DIALOG:
- Executor and gate vertices have `ask_user` tool for interactive dialog with the user
- Use ask_user when clarification is needed, or when presenting options for user decision
- After each confirmed decision, store it via `store_knowledge` with category "specification"

GUIDELINES:
- Executor, setup, planner, and reviewer vertices can read/update project guidelines
- Use `get_guidelines` to check current rules before making decisions
- Use `update_guideline` (after user confirmation via ask_user) to persist new rules"""

_DECOMPOSE_USER_TEMPLATE = """## Request
{request}

## Evidence / Context
{evidence}

{guidelines_section}
{agent_section}

Decompose this request into vertices (sub-tasks) with dependencies.
If it's simple enough for a single vertex, just return one vertex."""


async def _llm_decompose(
    vertex: GraphVertex,
    state: dict,
    evidence_text: str,
    guidelines: str,
    is_root: bool,
) -> list[dict]:
    """Call LLM to decompose a vertex into sub-vertices.

    Returns list of vertex dicts from LLM response, or empty list on failure.
    """
    system_prompt = _DECOMPOSE_SYSTEM_PROMPT % MAX_VERTICES_PER_DECOMPOSE

    guidelines_section = ""
    if guidelines:
        guidelines_section = f"## Guidelines\n{guidelines}"

    # Include agent_preference from request context
    request_ctx = state.get("request_context", {})
    agent_pref = request_ctx.get("agent_preference", "auto") if isinstance(request_ctx, dict) else "auto"
    agent_section = ""
    if agent_pref and agent_pref != "auto":
        agent_section = f"\n## Agent Preference\nUser configured: {agent_pref} (use this for dispatch_coding_agent calls)"

    user_prompt = _DECOMPOSE_USER_TEMPLATE.format(
        request=vertex.description,
        evidence=evidence_text or "(no additional context)",
        guidelines_section=guidelines_section,
        agent_section=agent_section,
    )

    # Add incoming context for non-root vertices
    if not is_root and vertex.incoming_context:
        context_lines = ["\n## Upstream Context"]
        for payload in vertex.incoming_context:
            context_lines.append(
                f"### From: {payload.source_vertex_title}\n"
                f"Summary: {payload.summary}\n"
                f"Details: {payload.context[:2000]}"
            )
        user_prompt += "\n".join(context_lines)

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]

    try:
        response = await llm_with_cloud_fallback(
            state=state,
            messages=messages,
            task_type="decomposition",
            max_tokens=settings.default_output_tokens,
        )
        content = response.choices[0].message.content or ""
        data = parse_json_response(content)

        if not data or "vertices" not in data:
            logger.warning("Decomposition LLM returned no valid vertices")
            return []

        vertices = data["vertices"]
        if not isinstance(vertices, list):
            return []

        # Enforce limit
        vertices = vertices[:MAX_VERTICES_PER_DECOMPOSE]

        # Attach synthesis if provided
        synthesis = data.get("synthesis")
        if synthesis and len(vertices) > 1:
            vertices.append({
                "title": synthesis.get("title", "Synthesize results"),
                "description": synthesis.get("description", "Combine all vertex results"),
                "type": "synthesis",
                "agent": None,
                "depends_on": list(range(len(vertices))),
                "_is_synthesis": True,
            })

        return vertices

    except Exception as e:
        logger.error("Decomposition LLM call failed: %s", e, exc_info=True)
        return []


# ---------------------------------------------------------------------------
# Graph construction from LLM output
# ---------------------------------------------------------------------------


def _build_subgraph(
    graph: TaskGraph,
    parent: GraphVertex,
    vertices_data: list[dict],
) -> list[GraphVertex]:
    """Create vertices and edges from LLM decomposition output.

    Each vertex in vertices_data can specify depends_on indices.
    Edges are created for dependencies (DEPENDENCY) and parent→child (DECOMPOSITION).
    """
    created: list[GraphVertex] = []

    # 1. Create all vertices
    for vd in vertices_data:
        vtype_str = vd.get("type", "task")
        try:
            vtype = VertexType(vtype_str)
        except ValueError:
            vtype = VertexType.TASK

        v = add_vertex(
            graph=graph,
            title=vd.get("title", "Untitled"),
            description=vd.get("description", ""),
            vertex_type=vtype,
            agent_name=vd.get("agent"),
            parent_id=parent.id,
            input_request=vd.get("description", ""),
        )
        created.append(v)

    # 2. Create edges for dependencies (depends_on indices)
    for i, vd in enumerate(vertices_data):
        deps = vd.get("depends_on", [])
        if not deps:
            # No explicit dependencies → depends on parent
            # (parent already completed during decomposition, so add
            # decomposition edge for traceability)
            add_edge(graph, parent.id, created[i].id, EdgeType.DECOMPOSITION)
        else:
            for dep_idx in deps:
                if isinstance(dep_idx, int) and 0 <= dep_idx < len(created):
                    if dep_idx != i:  # No self-loops
                        add_edge(
                            graph,
                            created[dep_idx].id,
                            created[i].id,
                            EdgeType.DEPENDENCY,
                        )
                else:
                    logger.warning(
                        "Invalid depends_on index %s for vertex %s",
                        dep_idx, created[i].id,
                    )

    # 3. Vertices with no incoming edges (except decomposition from parent)
    #    are immediately READY
    for v in created:
        incoming_deps = [
            e for e in graph.edges
            if e.target_id == v.id and e.edge_type == EdgeType.DEPENDENCY
        ]
        if not incoming_deps:
            v.status = VertexStatus.READY

    return created


# ---------------------------------------------------------------------------
# Fallback
# ---------------------------------------------------------------------------


def _single_vertex_fallback(graph: TaskGraph, root: GraphVertex) -> TaskGraph:
    """Create a minimal single-vertex graph when decomposition fails."""
    v = add_vertex(
        graph=graph,
        title="Process request",
        description=root.description,
        vertex_type=VertexType.TASK,
        parent_id=root.id,
        input_request=root.description,
    )
    add_edge(graph, root.id, v.id, EdgeType.DECOMPOSITION)
    v.status = VertexStatus.READY
    root.status = VertexStatus.COMPLETED
    root.result = "Single-vertex fallback"
    root.result_summary = root.result
    graph.status = GraphStatus.READY
    return graph


# ---------------------------------------------------------------------------
# Evidence formatting
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
        parts.append(f"### Conversation Context\n{chat_summary[:1000]}")

    existing_resources = evidence.get("existing_resources", "")
    if existing_resources:
        parts.append(f"### Existing Resources\n{existing_resources}")

    return "\n".join(parts) if parts else "(no evidence available)"
