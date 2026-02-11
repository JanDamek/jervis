"""LangGraph StateGraph – main orchestrator workflow (KB-first architecture).

Flow (4 task categories):
    intake → evidence_pack → route ─┬─ ADVICE ──────→ respond ──────────────────────→ finalize → END
                                     ├─ SINGLE_TASK ──→ plan → dispatch_or_respond ─→ finalize → END
                                     ├─ EPIC ─────────→ plan_epic → ... (Phase 3)
                                     └─ GENERATIVE ───→ design → ... (Phase 3)

    For SINGLE_TASK/code:
        plan → execute_step → evaluate → advance_step/advance_goal → ...
                                                                    → git_operations → finalize → END

Nodes:
    intake         — detect intent, classify task (4 categories), mandatory clarification
    evidence_pack  — parallel KB + tracker artifact fetch
    respond        — answer ADVICE + analytical queries directly (LLM + KB)
    plan           — multi-type planning for SINGLE_TASK (respond/code/tracker/mixed)
    decompose      — break coding task into goals (SINGLE_TASK/code)
    select_goal    — pick current goal, validate dependencies
    plan_steps     — create execution steps with cross-goal context
    execute_step   — run one step (dispatch by step type: respond/code/tracker)
    evaluate       — check step result against rules
    advance_step   — increment step index
    advance_goal   — increment goal index, build GoalSummary
    git_operations — commit/push with approval gates
    finalize       — generate final summary

State persistence:
    Uses MongoDBSaver for persistent checkpointing (MongoDB).
    Survives restarts, supports interrupt/resume for approval flow.
"""

from __future__ import annotations

import logging
from typing import Any, AsyncIterator, TypedDict

from pymongo import MongoClient
from langgraph.checkpoint.mongodb import MongoDBSaver
from langgraph.graph import END, StateGraph
from langgraph.types import Command

from app.config import settings
from app.graph.nodes import (
    # New nodes
    intake,
    evidence_pack,
    respond,
    plan,
    route_after_plan,
    execute_step,
    evaluate,
    next_step,
    advance_step,
    advance_goal,
    git_operations,
    finalize,
    # Coding pipeline nodes
    decompose,
    select_goal,
    plan_steps,
    # Phase 3: EPIC + GENERATIVE
    plan_epic,
    execute_wave,
    verify_wave,
    design,
)
from app.models import ChatHistoryPayload, CodingTask, OrchestrateRequest, ProjectRules

logger = logging.getLogger(__name__)


class OrchestratorState(TypedDict, total=False):
    """Typed state for the orchestrator graph."""

    # --- Core task data ---
    task: dict
    rules: dict
    environment: dict | None
    jervis_project_id: str | None       # JERVIS internal project

    # --- Task identity (top-level for easy access from all nodes) ---
    client_name: str | None
    project_name: str | None

    # --- Chat history (conversation context across sessions) ---
    chat_history: dict | None

    # --- Intake (new) ---
    task_category: str | None           # TaskCategory: advice/single_task/epic/generative
    task_action: str | None             # TaskAction: respond/code/tracker_ops/mixed
    external_refs: list | None          # Extracted ticket IDs, URLs
    evidence_pack: dict | None          # EvidencePack from evidence node
    needs_clarification: bool

    # --- Existing (from clarify) ---
    project_context: str | None
    task_complexity: str | None
    clarification_questions: list | None
    clarification_response: dict | None
    allow_cloud_prompt: bool

    # --- Goals & steps ---
    goals: list
    current_goal_index: int
    steps: list
    current_step_index: int
    step_results: list
    goal_summaries: list                # Cross-goal context

    # --- Results ---
    branch: str | None
    final_result: str | None
    artifacts: list
    error: str | None
    evaluation: dict | None


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
    """Build the LangGraph StateGraph with 4-category routing.

    Graph structure:
        intake → evidence_pack → route_by_category
            → ADVICE:       respond → finalize → END
            → SINGLE_TASK:  plan → route_after_plan
                                → respond (analytical) → finalize → END
                                → execute_step → evaluate loop → git_operations → finalize → END
            → EPIC:         plan_epic → select_goal → plan_steps → execute_step loop → finalize
            → GENERATIVE:   design → select_goal → plan_steps → execute_step loop → finalize
    """
    graph = StateGraph(OrchestratorState)

    # --- Add nodes ---
    graph.add_node("intake", intake)
    graph.add_node("evidence_pack", evidence_pack)
    graph.add_node("respond", respond)
    graph.add_node("plan", plan)
    graph.add_node("select_goal", select_goal)
    graph.add_node("plan_steps", plan_steps)
    graph.add_node("execute_step", execute_step)
    graph.add_node("evaluate", evaluate)
    graph.add_node("advance_step", advance_step)
    graph.add_node("advance_goal", advance_goal)
    graph.add_node("git_operations", git_operations)
    graph.add_node("finalize", finalize)
    # Phase 3: EPIC + GENERATIVE
    graph.add_node("plan_epic", plan_epic)
    graph.add_node("design", design)

    # --- Entry point ---
    graph.set_entry_point("intake")

    # intake → evidence_pack
    graph.add_edge("intake", "evidence_pack")

    # --- Route by category ---
    graph.add_conditional_edges(
        "evidence_pack",
        _route_by_category,
        {
            "respond": "respond",
            "plan": "plan",
            "plan_epic": "plan_epic",
            "design": "design",
        },
    )

    # --- ADVICE path: respond → finalize → END ---
    graph.add_edge("respond", "finalize")

    # --- SINGLE_TASK path: plan → route ---
    graph.add_conditional_edges(
        "plan",
        route_after_plan,
        {
            "respond": "respond",           # Analytical task → respond directly
            "execute_step": "execute_step",  # Coding/mixed → execution loop
        },
    )

    # --- EPIC path: plan_epic → route (rejected → finalize, approved → select_goal) ---
    graph.add_conditional_edges(
        "plan_epic",
        _route_after_epic_or_design,
        {
            "select_goal": "select_goal",
            "finalize": "finalize",
        },
    )

    # --- GENERATIVE path: design → route (rejected → finalize, approved → select_goal) ---
    graph.add_conditional_edges(
        "design",
        _route_after_epic_or_design,
        {
            "select_goal": "select_goal",
            "finalize": "finalize",
        },
    )

    # --- Coding execution loop ---
    graph.add_edge("execute_step", "evaluate")

    graph.add_conditional_edges(
        "evaluate",
        next_step,
        {
            "execute_step": "advance_step",
            "advance_goal": "advance_goal",
            "git_operations": "git_operations",
            "finalize": "finalize",
        },
    )

    graph.add_edge("advance_step", "execute_step")
    graph.add_edge("advance_goal", "select_goal")
    graph.add_edge("select_goal", "plan_steps")
    graph.add_edge("plan_steps", "execute_step")

    # --- Git → finalize → END ---
    graph.add_edge("git_operations", "finalize")
    graph.add_edge("finalize", END)

    return graph


def _route_by_category(state: dict) -> str:
    """Route after evidence_pack based on task_category.

    ADVICE → respond directly
    SINGLE_TASK → plan (which handles respond/code/tracker/mixed)
    EPIC → plan_epic (wave-based execution)
    GENERATIVE → design (generate structure, then execute)
    """
    category = state.get("task_category", "advice")

    if category == "advice":
        logger.info("Routing to respond node (ADVICE)")
        return "respond"

    if category == "single_task":
        logger.info("Routing to plan node (SINGLE_TASK)")
        return "plan"

    if category == "epic":
        logger.info("Routing to plan_epic node (EPIC)")
        return "plan_epic"

    if category == "generative":
        logger.info("Routing to design node (GENERATIVE)")
        return "design"

    logger.warning("Unknown category %s, defaulting to respond", category)
    return "respond"


def _route_after_epic_or_design(state: dict) -> str:
    """Route after plan_epic or design nodes.

    If the user rejected the plan (error set, final_result set) → finalize.
    If approved (goals populated) → select_goal to start execution.
    """
    if state.get("error") or state.get("final_result"):
        return "finalize"
    return "select_goal"


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
        # Core task data
        "task": CodingTask(
            id=request.task_id,
            client_id=request.client_id,
            project_id=request.project_id,
            client_name=request.client_name,
            project_name=request.project_name,
            workspace_path=request.workspace_path,
            query=request.query,
            agent_preference=request.agent_preference,
        ).model_dump(),
        "rules": request.rules.model_dump(),
        "environment": request.environment,
        "jervis_project_id": request.jervis_project_id,
        # Task identity — top-level, accessible from all nodes
        "client_name": request.client_name,
        "project_name": request.project_name,
        # Chat history — conversation context
        "chat_history": request.chat_history.model_dump() if request.chat_history else None,
        # Intake (populated by intake node)
        "task_category": None,
        "task_action": None,
        "external_refs": None,
        "evidence_pack": None,
        "needs_clarification": False,
        # Clarification
        "clarification_questions": None,
        "clarification_response": None,
        "project_context": None,
        "task_complexity": None,
        "allow_cloud_prompt": False,
        # Goals & steps
        "goals": [],
        "current_goal_index": 0,
        "steps": [],
        "current_step_index": 0,
        "step_results": [],
        "goal_summaries": [],
        # Results
        "branch": None,
        "final_result": None,
        "artifacts": [],
        "error": None,
        "evaluation": None,
    }


async def run_orchestration(
    request: OrchestrateRequest,
    thread_id: str = "default",
) -> dict:
    """Execute the full orchestration workflow (blocking)."""
    graph = get_orchestrator_graph()
    initial_state = _build_initial_state(request)
    config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 150}

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
    """Execute orchestration with streaming node events."""
    graph = get_orchestrator_graph()
    initial_state = _build_initial_state(request)
    config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 150}

    logger.info("Starting streaming orchestration: task=%s thread=%s", request.task_id, thread_id)

    # Track state for progress info
    tracked = {
        "total_goals": 0,
        "current_goal_index": 0,
        "total_steps": 0,
        "current_step_index": 0,
    }

    _KNOWN_NODES = {
        "intake", "evidence_pack", "respond", "plan",
        "select_goal", "plan_steps", "execute_step", "evaluate",
        "advance_step", "advance_goal", "git_operations", "finalize",
        "plan_epic", "design",
    }

    async for event in graph.astream_events(initial_state, config=config, version="v2"):
        kind = event.get("event", "")
        name = event.get("name", "")

        if kind == "on_chain_start" and name in _KNOWN_NODES:
            yield {
                "type": "node_start",
                "node": name,
                "thread_id": thread_id,
                "task_id": request.task_id,
                "client_id": request.client_id,
                **tracked,
            }

        elif kind == "on_chain_end" and name in _KNOWN_NODES:
            output = event.get("data", {}).get("output", {})

            # Update tracked state from node output
            if isinstance(output, dict):
                if "goals" in output:
                    tracked["total_goals"] = len(output["goals"])
                if "current_goal_index" in output:
                    tracked["current_goal_index"] = output["current_goal_index"]
                if "steps" in output:
                    tracked["total_steps"] = len(output["steps"])
                if "current_step_index" in output:
                    tracked["current_step_index"] = output["current_step_index"]

            yield {
                "type": "node_end",
                "node": name,
                "result": _safe_serialize(output),
                "thread_id": thread_id,
                "task_id": request.task_id,
                "client_id": request.client_id,
                **tracked,
            }


async def resume_orchestration(thread_id: str, resume_value: Any = None) -> dict:
    """Resume a paused orchestration from its checkpoint (blocking)."""
    graph = get_orchestrator_graph()
    config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 150}

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


async def resume_orchestration_streaming(
    thread_id: str,
    resume_value: Any = None,
) -> AsyncIterator[dict]:
    """Resume orchestration with streaming node events (for progress reporting)."""
    graph = get_orchestrator_graph()
    config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 150}

    logger.info("Resuming streaming orchestration: thread=%s", thread_id)

    _KNOWN_NODES = {
        "intake", "evidence_pack", "respond", "plan",
        "select_goal", "plan_steps", "execute_step", "evaluate",
        "advance_step", "advance_goal", "git_operations", "finalize",
        "plan_epic", "design",
    }

    tracked = {
        "total_goals": 0, "current_goal_index": 0,
        "total_steps": 0, "current_step_index": 0,
    }

    async for event in graph.astream_events(
        Command(resume=resume_value), config=config, version="v2"
    ):
        kind = event.get("event", "")
        name = event.get("name", "")

        if kind == "on_chain_start" and name in _KNOWN_NODES:
            yield {
                "type": "node_start",
                "node": name,
                "thread_id": thread_id,
                **tracked,
            }

        elif kind == "on_chain_end" and name in _KNOWN_NODES:
            output = event.get("data", {}).get("output", {})

            if isinstance(output, dict):
                if "goals" in output:
                    tracked["total_goals"] = len(output["goals"])
                if "current_goal_index" in output:
                    tracked["current_goal_index"] = output["current_goal_index"]
                if "steps" in output:
                    tracked["total_steps"] = len(output["steps"])
                if "current_step_index" in output:
                    tracked["current_step_index"] = output["current_step_index"]

            yield {
                "type": "node_end",
                "node": name,
                "thread_id": thread_id,
                **tracked,
            }


async def get_graph_state(thread_id: str):
    """Get the current state of a graph execution."""
    graph = get_orchestrator_graph()
    config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 150}
    try:
        return await graph.aget_state(config)
    except Exception:
        return None


def _safe_serialize(obj: Any) -> Any:
    """Safely serialize state for SSE (strip large fields)."""
    if isinstance(obj, dict):
        result = {}
        for k, v in obj.items():
            if k in ("diff", "kb_context", "project_context") and isinstance(v, str) and len(v) > 500:
                result[k] = v[:500] + "...(truncated)"
            else:
                result[k] = _safe_serialize(v)
        return result
    if isinstance(obj, list):
        return [_safe_serialize(item) for item in obj]
    return obj
