"""LangGraph StateGraph – main orchestrator workflow (KB-first architecture).

Legacy graph (14-node, 4 task categories):
    intake → evidence_pack → route ─┬─ ADVICE ──────→ respond ──────────────────────→ finalize → END
                                     ├─ SINGLE_TASK ──→ plan → dispatch_or_respond ─→ finalize → END
                                     ├─ EPIC ─────────→ plan_epic → ... (Phase 3)
                                     └─ GENERATIVE ───→ design → ... (Phase 3)

    For SINGLE_TASK/code:
        plan → execute_step → evaluate → advance_step/advance_goal → ...
                                                                    → git_operations → finalize → END

Delegation graph (7-node, multi-agent system — feature flag: use_delegation_graph):
    intake → evidence_pack → plan_delegations → execute_delegation(s) → synthesize → finalize → END

    Universal delegation engine: plan_delegations selects agents from registry,
    builds DAG of delegations. Agents can sub-delegate recursively (max depth 4).

Legacy nodes:
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

Delegation nodes:
    plan_delegations    — LLM-driven agent selection, builds ExecutionPlan
    execute_delegation  — dispatch DelegationMessage to agents, DAG execution
    synthesize          — merge AgentOutput results, RAG cross-check, translate

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
from app.llm.gpu_router import announce_gpu, release_gpu
from app.graph.nodes import (
    # Legacy graph nodes
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
    # Delegation graph nodes (multi-agent system)
    plan_delegations,
    execute_delegation,
    synthesize,
    # Memory Agent nodes
    memory_load,
    memory_flush,
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

    # --- Branch awareness ---
    target_branch: str | None           # Branch detected from user query or context

    # --- User context (auto-prefetched from KB) ---
    user_context: str | None            # User-learned knowledge (preferences, domain, etc.)

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
    kb_ingested: bool               # Whether task outcome was stored to KB

    # --- Delegation system (multi-agent, opt-in via use_delegation_graph) ---
    execution_plan: dict | None
    delegation_states: dict
    active_delegation_id: str | None
    completed_delegations: list
    delegation_results: dict
    response_language: str
    domain: str | None
    session_memory: list

    # --- Memory Agent ---
    memory_agent: dict | None
    memory_context: str | None
    context_switch_type: str | None


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

    # --- Memory Agent nodes ---
    graph.add_node("memory_load", memory_load)
    graph.add_node("memory_flush", memory_flush)

    # --- Entry point ---
    graph.set_entry_point("intake")

    # intake → memory_load → evidence_pack
    graph.add_edge("intake", "memory_load")
    graph.add_edge("memory_load", "evidence_pack")

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

    # --- ADVICE path: respond → memory_flush → finalize → END ---
    graph.add_edge("respond", "memory_flush")
    graph.add_edge("memory_flush", "finalize")

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


def build_delegation_graph() -> StateGraph:
    """Build 7-node delegation graph (new multi-agent system).

    Graph structure:
        intake → evidence_pack → plan_delegations → execute_delegation
            → route: more_delegations → execute_delegation (loop)
            → route: synthesize → synthesize → finalize → END

    Feature flag: settings.use_delegation_graph must be True.
    """
    graph = StateGraph(OrchestratorState)

    # --- Add nodes ---
    graph.add_node("intake", intake)                       # Reuse: classify + language detect
    graph.add_node("evidence_pack", evidence_pack)         # Reuse: KB + tracker fetch
    graph.add_node("plan_delegations", plan_delegations)   # NEW: LLM selects agents
    graph.add_node("execute_delegation", execute_delegation)  # NEW: dispatch to agents
    graph.add_node("synthesize", synthesize)                # NEW: merge results
    graph.add_node("finalize", finalize)                   # Reuse: final summary

    # --- Edges ---
    graph.set_entry_point("intake")
    graph.add_edge("intake", "evidence_pack")
    graph.add_edge("evidence_pack", "plan_delegations")
    graph.add_edge("plan_delegations", "execute_delegation")

    # After execution: loop back if more delegations, else synthesize
    graph.add_conditional_edges(
        "execute_delegation",
        _route_after_execution,
        {
            "execute_delegation": "execute_delegation",
            "synthesize": "synthesize",
        },
    )

    graph.add_edge("synthesize", "finalize")
    graph.add_edge("finalize", END)

    return graph


def _route_after_execution(state: dict) -> str:
    """Route after execute_delegation node.

    If there are pending delegations in the execution plan → loop back.
    If all delegations completed → synthesize results.
    """
    delegation_states = state.get("delegation_states", {})
    execution_plan = state.get("execution_plan")

    if not execution_plan:
        return "synthesize"

    # Check if any delegations are still pending
    plan_delegations_list = execution_plan.get("delegations", []) if isinstance(execution_plan, dict) else []

    pending = [
        d for d in plan_delegations_list
        if d.get("delegation_id") not in state.get("completed_delegations", [])
    ]

    if pending:
        logger.info(
            "Routing back to execute_delegation (%d pending)",
            len(pending),
        )
        return "execute_delegation"

    logger.info("All delegations completed, routing to synthesize")
    return "synthesize"


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


def build_delegation_graph() -> StateGraph:
    """Build the multi-agent delegation graph (opt-in via use_delegation_graph).

    Graph structure:
        intake → evidence_pack → plan_delegations → execute_delegation → synthesize → finalize → END

    The delegation graph replaces the legacy orchestrator graph when
    settings.use_delegation_graph is True. All specialist agents are
    dispatched via the AgentRegistry.
    """
    graph = StateGraph(OrchestratorState)

    # --- Reuse existing nodes ---
    graph.add_node("intake", intake)
    graph.add_node("evidence_pack", evidence_pack)
    graph.add_node("finalize", finalize)

    # --- New delegation nodes ---
    graph.add_node("plan_delegations", plan_delegations)
    graph.add_node("execute_delegation", execute_delegation)
    graph.add_node("synthesize", synthesize)

    # --- Memory Agent nodes ---
    graph.add_node("memory_load", memory_load)
    graph.add_node("memory_flush", memory_flush)

    # --- Entry point ---
    graph.set_entry_point("intake")

    # intake → memory_load → evidence_pack → ...
    graph.add_edge("intake", "memory_load")
    graph.add_edge("memory_load", "evidence_pack")

    graph.add_edge("evidence_pack", "plan_delegations")
    graph.add_edge("plan_delegations", "execute_delegation")
    graph.add_edge("execute_delegation", "synthesize")

    # synthesize → memory_flush → finalize → END
    graph.add_edge("synthesize", "memory_flush")
    graph.add_edge("memory_flush", "finalize")

    graph.add_edge("finalize", END)

    return graph


def get_orchestrator_graph():
    """Get compiled orchestrator graph with checkpointing (lazy singleton).

    Uses delegation graph when settings.use_delegation_graph is True,
    otherwise uses the legacy 14-node orchestrator graph.
    """
    global _compiled_graph
    if _compiled_graph is None:
        if _checkpointer is None:
            raise RuntimeError(
                "Checkpointer not initialized. Call init_checkpointer() first."
            )
        if settings.use_delegation_graph:
            logger.info("Building DELEGATION graph (multi-agent system)")
            graph = build_delegation_graph()
        else:
            logger.info("Building orchestrator graph (14-node)")
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
        # Branch awareness
        "target_branch": None,
        # User context (auto-prefetched from KB)
        "user_context": None,
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
        "kb_ingested": False,
        # Delegation system (populated when use_delegation_graph is True)
        "execution_plan": None,
        "delegation_states": {},
        "active_delegation_id": None,
        "completed_delegations": [],
        "delegation_results": {},
        "response_language": "en",
        "domain": None,
        "session_memory": [],
        # Memory Agent
        "memory_agent": None,
        "memory_context": None,
        "context_switch_type": None,
    }


async def run_orchestration(
    request: OrchestrateRequest,
    thread_id: str = "default",
) -> dict:
    """Execute the full orchestration workflow (blocking)."""
    # Use task_id for stable session_id (thread_id has random suffix that changes)
    session_id = f"orch-{request.task_id}"
    await announce_gpu(session_id)
    try:
        graph = get_orchestrator_graph()
        config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 150}

        # Check if checkpoint exists for this thread
        existing_state = await get_graph_state(thread_id)

        if existing_state and existing_state.values and existing_state.next:
            # Checkpoint exists AND graph is NOT finished → resume
            logger.info(
                "Resuming from checkpoint: task=%s thread=%s (checkpoint at node: %s)",
                request.task_id,
                thread_id,
                existing_state.next,
            )
            # Only update chat_history and task query, preserve everything else
            state_update = {
                "chat_history": request.chat_history.model_dump() if request.chat_history else None,
                "task": {
                    **existing_state.values.get("task", {}),
                    "query": request.query,  # Update query with new user message
                },
            }
            final_state = await graph.ainvoke(state_update, config=config)
        else:
            # No checkpoint OR graph finished → fresh start with full initial state
            if existing_state and existing_state.values:
                logger.info(
                    "Previous execution completed, starting new: task=%s thread=%s",
                    request.task_id,
                    thread_id,
                )
            else:
                logger.info("Starting fresh orchestration: task=%s thread=%s", request.task_id, thread_id)
            initial_state = _build_initial_state(request)
            final_state = await graph.ainvoke(initial_state, config=config)

        logger.info(
            "Orchestration complete: task=%s result=%s",
            request.task_id,
            final_state.get("final_result"),
        )

        return final_state
    finally:
        await release_gpu(session_id)


async def run_orchestration_streaming(
    request: OrchestrateRequest,
    thread_id: str = "default",
) -> AsyncIterator[dict]:
    """Execute orchestration with streaming node events."""
    # Use task_id for stable session_id (thread_id has random suffix that changes)
    session_id = f"orch-stream-{request.task_id}"
    await announce_gpu(session_id)
    try:
        graph = get_orchestrator_graph()
        config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 150}

        # Check if checkpoint exists for this thread
        existing_state = await get_graph_state(thread_id)

        if existing_state and existing_state.values and existing_state.next:
            # Checkpoint exists AND graph is NOT finished → resume
            logger.info(
                "Resuming from checkpoint (streaming): task=%s thread=%s (checkpoint at node: %s)",
                request.task_id,
                thread_id,
                existing_state.next,
            )
            # Only update chat_history and task query, preserve everything else
            state_to_use = {
                "chat_history": request.chat_history.model_dump() if request.chat_history else None,
                "task": {
                    **existing_state.values.get("task", {}),
                    "query": request.query,  # Update query with new user message
                },
            }
        else:
            # No checkpoint OR graph finished → fresh start with full initial state
            if existing_state and existing_state.values:
                logger.info(
                    "Previous execution completed, starting new (streaming): task=%s thread=%s",
                    request.task_id,
                    thread_id,
                )
            else:
                logger.info("Starting fresh streaming orchestration: task=%s thread=%s", request.task_id, thread_id)
            state_to_use = _build_initial_state(request)

        # Track state for progress info
        tracked = {
            "total_goals": 0,
            "current_goal_index": 0,
            "total_steps": 0,
            "current_step_index": 0,
        }

        _KNOWN_NODES = {
            # Legacy graph nodes
            "intake", "evidence_pack", "respond", "plan",
            "select_goal", "plan_steps", "execute_step", "evaluate",
            "advance_step", "advance_goal", "git_operations", "finalize",
            "plan_epic", "design",
            "plan_delegations", "execute_delegation", "synthesize",
            # Memory Agent nodes
            "memory_load", "memory_flush",
        }

        async for event in graph.astream_events(state_to_use, config=config, version="v2"):
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
    finally:
        await release_gpu(session_id)


async def resume_orchestration(thread_id: str, resume_value: Any = None) -> dict:
    """Resume a paused orchestration from its checkpoint (blocking)."""
    # Get task_id from existing state for stable session_id
    existing_state = await get_graph_state(thread_id)
    task_id = existing_state.values.get("task", {}).get("id", thread_id) if existing_state and existing_state.values else thread_id
    session_id = f"orch-resume-{task_id}"
    await announce_gpu(session_id)
    try:
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
    finally:
        await release_gpu(session_id)


async def resume_orchestration_streaming(
    thread_id: str,
    resume_value: Any = None,
) -> AsyncIterator[dict]:
    """Resume orchestration with streaming node events (for progress reporting)."""
    session_id = f"orch-resume-stream-{thread_id}"
    await announce_gpu(session_id)
    try:
        graph = get_orchestrator_graph()
        config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 150}

        logger.info("Resuming streaming orchestration: thread=%s", thread_id)

        _KNOWN_NODES = {
            # Legacy graph nodes
            "intake", "evidence_pack", "respond", "plan",
            "select_goal", "plan_steps", "execute_step", "evaluate",
            "advance_step", "advance_goal", "git_operations", "finalize",
            "plan_epic", "design",
            "plan_delegations", "execute_delegation", "synthesize",
            # Memory Agent nodes
            "memory_load", "memory_flush",
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
    finally:
        await release_gpu(session_id)


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
