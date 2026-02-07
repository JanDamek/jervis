"""LangGraph StateGraph – main orchestrator workflow.

Flow:
    decompose → select_goal → plan_steps → execute_step → evaluate → next_step
                    ↑                                              │
                    └──── advance_goal ←── (more goals) ───────────┘
                                           │
                                           ↓ (all goals done)
                                    git_operations → report

State persistence:
    Uses MongoDBSaver for persistent checkpointing.
    All graph state is stored in MongoDB (same instance as Kotlin server).
    This ensures:
    - Restart resilience: state survives Python process restarts
    - Interrupt/resume: approval flow works across restarts
    - Thread ID links TaskDocument ↔ LangGraph checkpoint
"""

from __future__ import annotations

import logging
from typing import Any, AsyncIterator

from pymongo import MongoClient
from langgraph.checkpoint.mongodb import MongoDBSaver
from langgraph.graph import END, StateGraph
from langgraph.types import Command

from app.config import settings
from app.graph.nodes import (
    advance_goal,
    advance_step,
    decompose,
    evaluate,
    execute_step,
    git_operations,
    next_step,
    plan_steps,
    report,
    select_goal,
)
from app.models import CodingTask, OrchestrateRequest, ProjectRules

logger = logging.getLogger(__name__)

# MongoDB checkpointer – initialized in main.py lifespan
_checkpointer: MongoDBSaver | None = None

# Compiled graph (singleton, rebuilt when checkpointer changes)
_compiled_graph = None


async def init_checkpointer() -> MongoDBSaver:
    """Initialize the MongoDB checkpointer. Called from main.py lifespan."""
    global _checkpointer, _compiled_graph

    client = MongoClient(settings.mongodb_url)
    _checkpointer = MongoDBSaver(client, db_name="jervis_checkpoints")
    _compiled_graph = None  # Force rebuild with new checkpointer
    logger.info("MongoDB checkpointer initialized (persistent state)")
    return _checkpointer


async def close_checkpointer():
    """Close the MongoDB checkpointer. Called from main.py lifespan."""
    global _checkpointer, _compiled_graph
    if _checkpointer is not None:
        _checkpointer.client.close()
    _checkpointer = None
    _compiled_graph = None
    logger.info("MongoDB checkpointer closed")


def get_checkpointer() -> MongoDBSaver | None:
    """Get the global checkpointer instance."""
    return _checkpointer


def build_orchestrator_graph() -> StateGraph:
    """Build the LangGraph StateGraph for the orchestrator.

    The graph implements the centrally-controlled coding workflow:
    - Orchestrator is the brain (decides what, when, conditions)
    - Coding agents are hands (execute specific steps)
    - Git operations: orchestrator DECIDES, agent EXECUTES

    Nodes (9):
        decompose     — break user query into goals
        select_goal   — pick current goal by index
        plan_steps    — create execution steps for current goal
        execute_step  — run one coding step via K8s Job
        evaluate      — check step result against rules
        advance_step  — increment step index
        advance_goal  — increment goal index
        git_operations — commit/push with approval gates
        report        — generate final summary
    """
    graph = StateGraph(dict)

    # Add nodes
    graph.add_node("decompose", decompose)
    graph.add_node("select_goal", select_goal)
    graph.add_node("plan_steps", plan_steps)
    graph.add_node("execute_step", execute_step)
    graph.add_node("evaluate", evaluate)
    graph.add_node("advance_step", advance_step)
    graph.add_node("advance_goal", advance_goal)
    graph.add_node("git_operations", git_operations)
    graph.add_node("report", report)

    # Set entry point
    graph.set_entry_point("decompose")

    # Linear edges
    graph.add_edge("decompose", "select_goal")
    graph.add_edge("select_goal", "plan_steps")
    graph.add_edge("plan_steps", "execute_step")
    graph.add_edge("execute_step", "evaluate")

    # Conditional routing after evaluation
    graph.add_conditional_edges(
        "evaluate",
        next_step,
        {
            "execute_step": "advance_step",
            "advance_goal": "advance_goal",
            "git_operations": "git_operations",
            "report": "report",
        },
    )

    # Advance step -> execute next step
    graph.add_edge("advance_step", "execute_step")

    # Advance goal -> select next goal -> plan new steps
    graph.add_edge("advance_goal", "select_goal")

    # Git operations -> report
    graph.add_edge("git_operations", "report")

    # Report -> END
    graph.add_edge("report", END)

    return graph


def get_orchestrator_graph():
    """Get compiled orchestrator graph with checkpointing (lazy singleton)."""
    global _compiled_graph
    if _compiled_graph is None:
        if _checkpointer is None:
            raise RuntimeError(
                "Checkpointer not initialized. Call init_checkpointer() first."
            )
        graph = build_orchestrator_graph()
        _compiled_graph = graph.compile(checkpointer=_checkpointer)
    return _compiled_graph


def _build_initial_state(request: OrchestrateRequest) -> dict:
    """Build initial state dict from request."""
    return {
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
        "environment": request.environment,
    }


async def run_orchestration(
    request: OrchestrateRequest,
    thread_id: str = "default",
) -> dict:
    """Execute the full orchestration workflow (blocking).

    Args:
        request: Orchestration request from Kotlin server.
        thread_id: Unique thread ID for checkpointing.

    Returns:
        Final state including results, branch, artifacts.
    """
    graph = get_orchestrator_graph()
    initial_state = _build_initial_state(request)
    config = {"configurable": {"thread_id": thread_id}}

    logger.info("Starting orchestration: task=%s thread=%s", request.task_id, thread_id)

    final_state = await graph.ainvoke(initial_state, config=config)

    logger.info(
        "Orchestration complete: task=%s result=%s",
        request.task_id,
        final_state.get("final_result"),
    )

    return final_state


async def run_orchestration_streaming(
    request: OrchestrateRequest,
    thread_id: str = "default",
) -> AsyncIterator[dict]:
    """Execute orchestration with streaming node events.

    Yields SSE-compatible events for each node execution.
    """
    graph = get_orchestrator_graph()
    initial_state = _build_initial_state(request)
    config = {"configurable": {"thread_id": thread_id}}

    logger.info("Starting streaming orchestration: task=%s thread=%s", request.task_id, thread_id)

    async for event in graph.astream_events(initial_state, config=config, version="v2"):
        kind = event.get("event", "")
        name = event.get("name", "")

        if kind == "on_chain_start" and name in (
            "decompose", "select_goal", "plan_steps", "execute_step",
            "evaluate", "git_operations", "report",
        ):
            yield {
                "type": "node_start",
                "node": name,
                "thread_id": thread_id,
            }

        elif kind == "on_chain_end" and name in (
            "decompose", "select_goal", "plan_steps", "execute_step",
            "evaluate", "git_operations", "report",
        ):
            output = event.get("data", {}).get("output", {})
            yield {
                "type": "node_end",
                "node": name,
                "result": _safe_serialize(output),
                "thread_id": thread_id,
            }


async def resume_orchestration(thread_id: str, resume_value: Any = None) -> dict:
    """Resume a paused orchestration from its checkpoint.

    Uses LangGraph Command(resume=...) to pass the user's response
    back to the interrupted node (e.g., approval response).

    The checkpoint is stored in MongoDB, so this works even after
    Python restart – the graph state is fully reconstructed.

    Args:
        thread_id: Thread ID to resume.
        resume_value: Value to pass to the interrupt() call that paused the graph.
            For approval flow, this is {"approved": bool, "reason": str | None}.

    Returns:
        Final state after resumption completes.
    """
    graph = get_orchestrator_graph()
    config = {"configurable": {"thread_id": thread_id}}

    logger.info("Resuming orchestration: thread=%s resume_value=%s", thread_id, resume_value)

    final_state = await graph.ainvoke(
        Command(resume=resume_value),
        config=config,
    )

    logger.info(
        "Resumed orchestration complete: thread=%s result=%s",
        thread_id,
        final_state.get("final_result"),
    )

    return final_state


async def get_graph_state(thread_id: str):
    """Get the current state of a graph execution.

    Used to detect if the graph is interrupted (waiting for approval).
    Returns the StateSnapshot or None if no state exists.
    """
    graph = get_orchestrator_graph()
    config = {"configurable": {"thread_id": thread_id}}
    try:
        return await graph.aget_state(config)
    except Exception:
        return None


def _safe_serialize(obj: Any) -> Any:
    """Safely serialize state for SSE (strip large fields)."""
    if isinstance(obj, dict):
        result = {}
        for k, v in obj.items():
            if k in ("diff", "kb_context") and isinstance(v, str) and len(v) > 500:
                result[k] = v[:500] + "...(truncated)"
            else:
                result[k] = _safe_serialize(v)
        return result
    if isinstance(obj, list):
        return [_safe_serialize(item) for item in obj]
    return obj
