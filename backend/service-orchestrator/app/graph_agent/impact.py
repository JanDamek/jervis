"""Impact analysis — propagates changes through the artifact graph.

When a vertex completes, this module:
1. Extracts which artifacts the vertex touched (from LLM result or explicit links)
2. Traverses the artifact dependency graph to find all affected entities
3. Checks which OTHER planned vertices touch those affected entities
4. Creates new VALIDATOR vertices to verify unbroken dependencies
5. Reports detected conflicts (two vertices modifying the same entity)

This is what prevents a class rename from silently breaking other parts of
the plan, or a rescheduled meeting from not propagating to dependent events.
"""

from __future__ import annotations

import json
import logging
import uuid

from app.config import settings
from app.graph.nodes._helpers import llm_with_cloud_fallback
from app.graph_agent.artifact_graph import (
    Artifact,
    ArtifactDep,
    ArtifactKind,
    DepKind,
    TaskArtifactLink,
    TouchKind,
    artifact_graph_store,
)
from app.graph_agent.graph import add_edge
from app.graph_agent.models import (
    EdgeType,
    GraphVertex,
    TaskGraph,
    VertexStatus,
    VertexType,
)

logger = logging.getLogger(__name__)

# Max new vertices created by impact analysis per vertex completion
_MAX_IMPACT_VERTICES = 5


# ---------------------------------------------------------------------------
# 1. Extract touched artifacts from vertex result
# ---------------------------------------------------------------------------

_EXTRACT_ARTIFACTS_PROMPT = """You are the Impact Analyzer. Given a vertex result, extract which entities (code artifacts, documents, meetings, etc.) were touched.

Respond with JSON:
{
  "touched": [
    {
      "key": "<unique identifier, e.g. 'com.jervis.service.FooService' or 'doc/test-plan-v2'>",
      "kind": "<module|file|class|function|api|db_table|config|test|schema|component|service|document|spec|test_plan|test_scenario|person|meeting|decision|training|environment|pipeline>",
      "label": "<human-readable name>",
      "touch": "<creates|modifies|renames|deletes|reads|tests|reviews|schedules|delegates|delivers>",
      "detail": "<what exactly was done>"
    }
  ],
  "dependencies_added": [
    {
      "from_key": "<artifact key>",
      "to_key": "<artifact key>",
      "dep_kind": "<imports|extends|implements|calls|contains|depends_on|responsible_for|assigned_to|produces|consumes|references|related_to|blocks|precedes|deploys_to>"
    }
  ]
}

If the result doesn't clearly touch any specific entities, return {"touched": [], "dependencies_added": []}.
Only include entities that were ACTUALLY changed, created, or meaningfully interacted with — not just mentioned."""


async def extract_touched_artifacts(
    vertex: GraphVertex,
    state: dict,
) -> tuple[list[TaskArtifactLink], list[Artifact], list[ArtifactDep]]:
    """Use LLM to extract which artifacts a vertex touched from its result.

    Returns:
        (links, new_artifacts, new_deps) — what to persist in the artifact graph.
    """
    if not vertex.result:
        return [], [], []

    messages = [
        {"role": "system", "content": _EXTRACT_ARTIFACTS_PROMPT},
        {
            "role": "user",
            "content": (
                f"## Vertex: {vertex.title}\n"
                f"Type: {vertex.vertex_type.value}\n"
                f"Description: {vertex.description}\n\n"
                f"## Result\n{vertex.result[:4000]}"
            ),
        },
    ]

    try:
        response = await llm_with_cloud_fallback(
            state=state,
            messages=messages,
            task_type="impact_analysis",
            max_tokens=2000,
            temperature=0.0,
        )
        content = response.choices[0].message.content or ""

        # Parse JSON response
        data = _parse_json(content)
        if not data:
            return [], [], []

        links: list[TaskArtifactLink] = []
        artifacts: list[Artifact] = []
        deps: list[ArtifactDep] = []

        task_data = state.get("task", {})
        client_id = task_data.get("client_id", "")
        project_id = task_data.get("project_id", "")

        for item in data.get("touched", []):
            key = item.get("key", "")
            if not key:
                continue

            # Create/update artifact
            try:
                kind = ArtifactKind(item.get("kind", "file"))
            except ValueError:
                kind = ArtifactKind.FILE

            artifacts.append(Artifact(
                key=key,
                kind=kind,
                label=item.get("label", key),
                client_id=client_id,
                project_id=project_id,
            ))

            # Create task-artifact link
            try:
                touch = TouchKind(item.get("touch", "modifies"))
            except ValueError:
                touch = TouchKind.MODIFIES

            links.append(TaskArtifactLink(
                vertex_id=vertex.id,
                artifact_key=key,
                touch_kind=touch,
                task_graph_id=vertex.parent_id or "",
                detail=item.get("detail", ""),
            ))

        for dep_item in data.get("dependencies_added", []):
            from_key = dep_item.get("from_key", "")
            to_key = dep_item.get("to_key", "")
            if not from_key or not to_key:
                continue

            try:
                dep_kind = DepKind(dep_item.get("dep_kind", "depends_on"))
            except ValueError:
                dep_kind = DepKind.DEPENDS_ON

            deps.append(ArtifactDep(
                from_key=from_key,
                to_key=to_key,
                dep_kind=dep_kind,
            ))

        return links, artifacts, deps

    except Exception as e:
        logger.warning("Failed to extract artifacts from vertex %s: %s", vertex.id, e)
        return [], [], []


# ---------------------------------------------------------------------------
# 2. Impact analysis — find what's affected and create response vertices
# ---------------------------------------------------------------------------


async def analyze_impact(
    graph: TaskGraph,
    vertex: GraphVertex,
    state: dict,
) -> list[str]:
    """Analyze impact of a completed vertex and create response vertices if needed.

    Steps:
    1. Extract touched artifacts from the vertex result
    2. Persist artifacts and links in ArangoDB
    3. For each MODIFYING touch, find affected entities via graph traversal
    4. Check if any affected entity is touched by another planned vertex
    5. If yes, create a VALIDATOR vertex to verify the dependency isn't broken
    6. Detect and report conflicts

    Returns:
        List of new vertex IDs created (validators, fixers).
    """
    # Skip entirely if ArangoDB is unavailable
    if not artifact_graph_store.available:
        return []

    # Step 1: Extract touched artifacts
    links, artifacts, deps = await extract_touched_artifacts(vertex, state)

    if not links:
        return []

    # Step 2: Persist in ArangoDB
    try:
        if artifacts:
            await artifact_graph_store.upsert_artifacts_batch(artifacts)
        if deps:
            await artifact_graph_store.add_dependencies_batch(deps)
        for link in links:
            link.task_graph_id = graph.id
            await artifact_graph_store.link_task_to_artifact(link)
    except Exception as e:
        logger.warning("Failed to persist artifact data: %s", e)
        # Non-fatal — impact analysis degrades gracefully

    # Step 3: For modifying touches, find affected entities
    new_vertex_ids: list[str] = []
    modifying_links = [
        l for l in links
        if l.touch_kind in (
            TouchKind.MODIFIES, TouchKind.RENAMES,
            TouchKind.DELETES, TouchKind.CREATES,
        )
    ]

    if not modifying_links:
        return []

    for link in modifying_links:
        try:
            affected_vertices = await artifact_graph_store.find_affected_task_vertices(
                artifact_key=link.artifact_key,
                task_graph_id=graph.id,
                max_depth=3,
            )
        except Exception as e:
            logger.warning("Impact traversal failed for %s: %s", link.artifact_key, e)
            continue

        # Filter: only vertices that are still PENDING or READY (not already completed)
        planned_affected = []
        for av in affected_vertices:
            vid = av.get("vertex_id")
            if vid and vid != vertex.id:
                v = graph.vertices.get(vid)
                if v and v.status in (VertexStatus.PENDING, VertexStatus.READY):
                    planned_affected.append(av)

        if not planned_affected and len(new_vertex_ids) < _MAX_IMPACT_VERTICES:
            continue

        # Step 5: Create VALIDATOR vertex to verify the impact
        if len(new_vertex_ids) >= _MAX_IMPACT_VERTICES:
            logger.warning("Impact analysis hit max new vertices (%d)", _MAX_IMPACT_VERTICES)
            break

        affected_desc = "\n".join(
            f"- {av.get('artifact_label', '?')} (touched by vertex {av.get('vertex_id')})"
            for av in planned_affected[:10]
        )

        validator_id = f"impact_v_{uuid.uuid4().hex[:8]}"
        validator = GraphVertex(
            id=validator_id,
            title=f"Verify impact: {link.artifact_key}",
            description=(
                f"Vertex '{vertex.title}' {link.touch_kind.value} artifact "
                f"'{link.artifact_key}'. Verify that the following planned "
                f"vertices are not broken by this change:\n{affected_desc}"
            ),
            vertex_type=VertexType.VALIDATOR,
            status=VertexStatus.PENDING,
            input_request=f"Check if change to {link.artifact_key} breaks dependent work",
            parent_id=vertex.parent_id,
            depth=vertex.depth,
        )

        graph.vertices[validator_id] = validator

        # Add dependency edge from the completed vertex to the validator
        add_edge(
            graph,
            source_id=vertex.id,
            target_id=validator_id,
            edge_type=EdgeType.DEPENDENCY,
        )

        # Add dependency edges from validator to affected planned vertices
        # (so they wait for validation before proceeding)
        for av in planned_affected[:5]:
            affected_vid = av.get("vertex_id")
            if affected_vid:
                add_edge(
                    graph,
                    source_id=validator_id,
                    target_id=affected_vid,
                    edge_type=EdgeType.DEPENDENCY,
                )

        new_vertex_ids.append(validator_id)
        logger.info(
            "Impact analysis: created validator %s for artifact %s "
            "(affects %d planned vertices)",
            validator_id, link.artifact_key, len(planned_affected),
        )

    # Step 6: Detect conflicts
    try:
        conflicts = await artifact_graph_store.find_conflicting_vertices(graph.id)
        if conflicts:
            logger.warning(
                "Impact analysis: %d conflicting artifacts detected in graph %s",
                len(conflicts), graph.id,
            )
            for c in conflicts:
                logger.warning(
                    "  Conflict: %s (%s) modified by vertices: %s",
                    c.get("artifact_label"), c.get("artifact_key"),
                    [v.get("vertex_id") for v in c.get("vertices", [])],
                )
    except Exception as e:
        logger.warning("Conflict detection failed: %s", e)

    return new_vertex_ids


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _parse_json(text: str) -> dict | None:
    """Try to parse JSON from LLM response (handles markdown code blocks)."""
    text = text.strip()

    # Strip markdown code blocks
    if text.startswith("```"):
        lines = text.split("\n")
        lines = lines[1:]  # Remove opening ```json
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines)

    try:
        return json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return None
