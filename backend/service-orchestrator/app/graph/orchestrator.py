"""LangGraph StateGraph – main orchestrator workflow.

Flow:
    decompose → plan_steps → execute_step → evaluate → next_step
                    ↑                                      │
                    └──────────── (more steps) ────────────┘
                                      │
                                      ↓ (all done)
                               git_operations → report
"""

from __future__ import annotations

import logging
from typing import Any

from langgraph.graph import END, StateGraph

from app.graph.nodes import (
    advance_step,
    decompose,
    evaluate,
    execute_step,
    git_operations,
    next_step,
    plan_steps,
    report,
)
from app.models import CodingTask, OrchestrateRequest, ProjectRules

logger = logging.getLogger(__name__)


def build_orchestrator_graph() -> StateGraph:
    """Build the LangGraph StateGraph for the orchestrator.

    The graph implements the centrally-controlled coding workflow:
    - Orchestrator is the brain (decides what, when, conditions)
    - Coding agents are hands (execute specific steps)
    - Git operations: orchestrator DECIDES, agent EXECUTES
    """
    graph = StateGraph(dict)

    # Add nodes
    graph.add_node("decompose", decompose)
    graph.add_node("plan_steps", plan_steps)
    graph.add_node("execute_step", execute_step)
    graph.add_node("evaluate", evaluate)
    graph.add_node("advance_step", advance_step)
    graph.add_node("git_operations", git_operations)
    graph.add_node("report", report)

    # Set entry point
    graph.set_entry_point("decompose")

    # Linear edges
    graph.add_edge("decompose", "plan_steps")
    graph.add_edge("plan_steps", "execute_step")
    graph.add_edge("execute_step", "evaluate")

    # Conditional routing after evaluation
    graph.add_conditional_edges(
        "evaluate",
        next_step,
        {
            "execute_step": "advance_step",
            "git_operations": "git_operations",
            "report": "report",
        },
    )

    # Advance step -> execute next step
    graph.add_edge("advance_step", "execute_step")

    # Git operations -> report
    graph.add_edge("git_operations", "report")

    # Report -> END
    graph.add_edge("report", END)

    return graph


# Compiled graph (singleton)
_compiled_graph = None


def get_orchestrator_graph():
    """Get compiled orchestrator graph (lazy singleton)."""
    global _compiled_graph
    if _compiled_graph is None:
        graph = build_orchestrator_graph()
        _compiled_graph = graph.compile()
    return _compiled_graph


async def run_orchestration(request: OrchestrateRequest) -> dict:
    """Execute the full orchestration workflow.

    Args:
        request: Orchestration request from Kotlin server.

    Returns:
        Final state including results, branch, artifacts.
    """
    graph = get_orchestrator_graph()

    initial_state = {
        "task": CodingTask(
            id=request.task_id,
            client_id=request.client_id,
            project_id=request.project_id,
            workspace_path=request.workspace_path,
            query=request.query,
            agent_preference=request.agent_preference,
        ).model_dump(),
        "rules": request.rules.model_dump(),
        "goals": [],
        "current_goal_index": 0,
        "steps": [],
        "current_step_index": 0,
        "step_results": [],
        "branch": None,
        "final_result": None,
        "artifacts": [],
        "error": None,
        "evaluation": None,
    }

    logger.info("Starting orchestration for task %s", request.task_id)

    # Run the graph
    final_state = await graph.ainvoke(initial_state)

    logger.info(
        "Orchestration complete: task=%s result=%s",
        request.task_id,
        final_state.get("final_result"),
    )

    return final_state
