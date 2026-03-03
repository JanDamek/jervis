"""Graph Agent orchestration — full pipeline from request to result.

Entry point: ``run_graph_agent(state)``

Pipeline:
1. Create TaskGraph with root vertex from request
2. Decompose root into sub-vertices (LLM-driven)
3. Validate graph structure
4. Execute vertices in topological order (fan-in, parallel)
5. Compose final result from terminal vertices
6. Persist and report
"""

from __future__ import annotations

import logging
import time
from typing import TYPE_CHECKING

from app.config import settings
from app.context.guidelines_resolver import resolve_guidelines, format_guidelines_for_prompt
from app.graph_agent.decomposer import decompose_root
from app.graph_agent.executor import execute_graph
from app.graph_agent.graph import (
    create_task_graph,
    get_final_result,
    get_stats,
)
from app.graph_agent.models import GraphStatus
from app.graph_agent.persistence import task_graph_store
from app.graph_agent.progress import report_graph_status
from app.graph_agent.validation import validate_graph
from app.models import CodingTask, OrchestrateResponse

if TYPE_CHECKING:
    from app.agents.registry import AgentRegistry

logger = logging.getLogger(__name__)


async def run_graph_agent(
    state: dict,
    registry: "AgentRegistry | None" = None,
) -> dict:
    """Full graph agent pipeline: decompose → validate → execute → result.

    This is the main entry point called from the orchestrator.

    Args:
        state: Orchestrator state dict with task, rules, evidence_pack, etc.
        registry: Optional AgentRegistry for TASK vertex dispatch.

    Returns:
        Dict with task_id, success, summary, graph_id, stats.
    """
    task = CodingTask(**state["task"])
    evidence = state.get("evidence_pack", {})
    response_language = state.get("response_language", "en")

    logger.info(
        "Graph agent starting for task=%s client=%s project=%s",
        task.id, task.client_id, task.project_id,
    )

    # --- 1. Create graph ---
    graph = create_task_graph(
        task_id=task.id,
        client_id=task.client_id,
        project_id=task.project_id,
        root_title=task.query[:100],
        root_description=task.query,
    )
    await task_graph_store.save(graph)
    await report_graph_status(graph, "Graph created, starting decomposition…")

    # --- 2. Resolve guidelines ---
    guidelines_text = ""
    try:
        guidelines = await resolve_guidelines(
            client_id=task.client_id,
            project_id=task.project_id,
        )
        guidelines_text = format_guidelines_for_prompt(guidelines) if guidelines else ""
    except Exception as e:
        logger.warning("Failed to resolve guidelines: %s", e)

    # --- 3. Decompose ---
    try:
        graph = await decompose_root(
            graph=graph,
            state=state,
            evidence=evidence,
            guidelines=guidelines_text,
        )
    except Exception as e:
        logger.error("Root decomposition failed: %s", e, exc_info=True)
        graph.status = GraphStatus.FAILED
        await task_graph_store.save(graph)
        return _error_response(task.id, graph.id, f"Decomposition failed: {e}")

    # --- 4. Validate ---
    validation = validate_graph(graph)
    if not validation.valid:
        logger.error(
            "Graph validation failed: %s",
            "; ".join(validation.errors),
        )
        graph.status = GraphStatus.FAILED
        await task_graph_store.save(graph)
        return _error_response(
            task.id, graph.id,
            f"Graph validation failed: {'; '.join(validation.errors)}",
        )

    if validation.warnings:
        logger.info(
            "Graph validation warnings: %s",
            "; ".join(validation.warnings),
        )

    # --- 5. Execute ---
    try:
        graph = await execute_graph(
            graph=graph,
            state=state,
            registry=registry,
            guidelines=guidelines_text,
        )
    except Exception as e:
        logger.error("Graph execution failed: %s", e, exc_info=True)
        graph.status = GraphStatus.FAILED
        await task_graph_store.save(graph)
        return _error_response(task.id, graph.id, f"Execution failed: {e}")

    # --- 6. Compose result ---
    final_result = get_final_result(graph)
    stats = get_stats(graph)

    success = graph.status == GraphStatus.COMPLETED

    logger.info(
        "Graph agent finished: task=%s success=%s vertices=%d tokens=%d",
        task.id, success, stats["total_vertices"], stats["total_tokens"],
    )

    return {
        "task_id": task.id,
        "graph_id": graph.id,
        "success": success,
        "summary": final_result,
        "response_language": response_language,
        "stats": stats,
        "graph_status": graph.status.value,
    }


def _error_response(task_id: str, graph_id: str, error: str) -> dict:
    """Build an error response dict."""
    return {
        "task_id": task_id,
        "graph_id": graph_id,
        "success": False,
        "summary": error,
        "stats": {},
        "graph_status": GraphStatus.FAILED.value,
    }
