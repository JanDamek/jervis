"""FastAPI application – Python Orchestrator Service.

Accepts requests from Kotlin server, runs LangGraph orchestration,
streams progress via SSE, handles approval flow via LangGraph interrupt().

Communication architecture (push-based):
- Kotlin → Python: POST /orchestrate/stream (fire-and-forget, returns thread_id)
- Python → Kotlin: POST /internal/orchestrator-progress (node progress, real-time)
- Python → Kotlin: POST /internal/orchestrator-status (completion/error/interrupt)
- Python → UI: SSE /stream/{thread_id} (progress + approval requests)
- Kotlin → Python: POST /approve/{thread_id} (fire-and-forget, resumes graph)
- Kotlin → Python: GET /status/{thread_id} (safety-net fallback polling, 60s)

Python pushes progress and status changes to Kotlin in real-time.
Kotlin broadcasts to UI via Flow-based event subscriptions.
Polling is reduced to a 60s safety-net fallback only.

Concurrency:
- Only ONE orchestration runs at a time (asyncio.Semaphore)
- LLM (Ollama/Anthropic) can't handle multiple concurrent requests
- Dispatch returns 429 if busy; Kotlin retries on next polling cycle
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from sse_starlette.sse import EventSourceResponse

from app.config import settings
from app.graph.orchestrator import (
    run_orchestration,
    run_orchestration_streaming,
    resume_orchestration,
    resume_orchestration_streaming,
    get_graph_state,
    init_checkpointer,
    close_checkpointer,
)
from app.llm.gpu_router import release_gpu
from app.models import (
    ApprovalResponse,
    OrchestrateRequest,
    OrchestrateResponse,
    StepResult,
)
from app.context.context_store import context_store
from app.context.distributed_lock import distributed_lock
from app.context.session_memory import session_memory_store
from app.monitoring.delegation_metrics import metrics_collector
from app.llm.provider import llm_provider
from app.models import ModelTier
from app.tools.kotlin_client import kotlin_client
from app.logging_utils import LocalTimeFormatter

# Configure logging with local timezone
handler = logging.StreamHandler()
handler.setFormatter(LocalTimeFormatter("%(asctime)s [%(name)s] %(levelname)s: %(message)s"))
logging.root.addHandler(handler)
logging.root.setLevel(logging.INFO)
logger = logging.getLogger(__name__)

# Suppress MongoDB heartbeat spam — pymongo DEBUG logs are noise
logging.getLogger("pymongo").setLevel(logging.WARNING)
logging.getLogger("pymongo.topology").setLevel(logging.WARNING)
logging.getLogger("pymongo.connection").setLevel(logging.WARNING)
logging.getLogger("pymongo.command").setLevel(logging.WARNING)
logging.getLogger("pymongo.serverSelection").setLevel(logging.WARNING)


class _HealthCheckAccessFilter(logging.Filter):
    """Drop GET /health from uvicorn access log."""

    def filter(self, record: logging.LogRecord) -> bool:
        msg = record.getMessage()
        if "GET /health " in msg:
            return False
        return True

# In-memory store for active SSE streams
_active_streams: dict[str, asyncio.Queue] = {}

# In-memory store for active orchestration asyncio tasks (for cancellation)
_active_tasks: dict[str, asyncio.Task] = {}

# Concurrency: Router handles GPU/CPU routing, no artificial limit needed
# Multiple orchestrations can run concurrently - router will manage resources
_orchestration_semaphore = asyncio.Semaphore(10)  # Allow up to 10 concurrent orchestrations


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup and shutdown events."""
    logger.info("Orchestrator starting on port %d", settings.port)
    logging.getLogger("uvicorn.access").addFilter(_HealthCheckAccessFilter())
    # Initialize persistent MongoDB checkpointer for LangGraph state
    await init_checkpointer()
    logger.info("MongoDB checkpointer ready (state survives restarts)")
    # Initialize context store (hierarchical MongoDB context)
    await context_store.init()
    logger.info("Context store ready (orchestrator_context collection)")
    # Initialize distributed lock (multi-pod concurrency)
    await distributed_lock.init()
    logger.info("Distributed lock ready (multi-pod orchestration)")
    # Initialize multi-agent delegation system (opt-in)
    if settings.use_delegation_graph:
        logger.info("Initializing multi-agent delegation system...")
        from app.agents.registry import AgentRegistry
        from app.agents.legacy_agent import LegacyAgent
        from app.agents.specialists import (
            CodingAgent, GitAgent, CodeReviewAgent, TestAgent, ResearchAgent,
            IssueTrackerAgent, WikiAgent, DocumentationAgent, DevOpsAgent,
            ProjectManagementAgent, SecurityAgent,
            EmailAgent, CommunicationAgent, CalendarAgent,
            AdministrativeAgent, LegalAgent, FinancialAgent,
            PersonalAgent, LearningAgent,
        )

        registry = AgentRegistry.instance()
        # Register all 19 specialist agents + legacy fallback
        for agent_cls in [
            CodingAgent, GitAgent, CodeReviewAgent, TestAgent, ResearchAgent,
            IssueTrackerAgent, WikiAgent, DocumentationAgent, DevOpsAgent,
            ProjectManagementAgent, SecurityAgent,
            EmailAgent, CommunicationAgent, CalendarAgent,
            AdministrativeAgent, LegalAgent, FinancialAgent,
            PersonalAgent, LearningAgent,
            LegacyAgent,
        ]:
            registry.register(agent_cls())
        logger.info(
            "AgentRegistry ready: %d agents registered (%s)",
            len(registry.all_names()),
            ", ".join(registry.all_names()),
        )

        # Initialize session memory store
        from app.context.session_memory import session_memory_store
        await session_memory_store.init()
        logger.info("Session memory store ready")

        # Initialize delegation metrics collector
        from app.monitoring.delegation_metrics import metrics_collector
        await metrics_collector.init()
        logger.info("Delegation metrics collector ready")

        # Initialize procedural memory
        if settings.use_procedural_memory:
            from app.context.procedural_memory import procedural_memory
            await procedural_memory.init()
            logger.info("Procedural memory ready")

    # Memory Agent
    logger.info("Memory Agent ready (affairs + LQM)")

    # Start AgentTaskWatcher (monitors async coding agent K8s Jobs)
    from app.agent_task_watcher import agent_task_watcher
    await agent_task_watcher.start()

    yield

    # Stop AgentTaskWatcher
    await agent_task_watcher.stop()

    # Cleanup
    logger.info("Orchestrator shutting down, releasing GPU reservation...")
    try:
        await release_gpu("shutdown")
    except Exception as e:
        logger.warning("Failed to release GPU on shutdown: %s", e)

    if settings.use_delegation_graph:
        from app.context.session_memory import session_memory_store
        from app.monitoring.delegation_metrics import metrics_collector
        await session_memory_store.close()
        await metrics_collector.close()
        if settings.use_procedural_memory:
            from app.context.procedural_memory import procedural_memory
            await procedural_memory.close()
        logger.info("Multi-agent delegation system stopped")
    from app.memory.agent import reset_lqm
    reset_lqm()
    logger.info("Memory Agent LQM cleared")
    await distributed_lock.close()
    await context_store.close()
    await close_checkpointer()
    await kotlin_client.close()
    logger.info("Orchestrator stopped")


def _register_agents() -> None:
    """Register all specialist agents in the AgentRegistry.

    Called during lifespan startup when use_delegation_graph is enabled.
    Each agent is instantiated and registered with the singleton registry.
    """
    from app.agents.registry import AgentRegistry
    from app.agents.legacy_agent import LegacyAgent

    registry = AgentRegistry.instance()

    # Always register LegacyAgent as fallback
    registry.register(LegacyAgent())

    if settings.use_specialist_agents:
        from app.agents.specialists import (
            CodingAgent, GitAgent, CodeReviewAgent, TestAgent, ResearchAgent,
            IssueTrackerAgent, WikiAgent, DocumentationAgent, DevOpsAgent,
            ProjectManagementAgent, SecurityAgent,
            CommunicationAgent, EmailAgent, CalendarAgent, AdministrativeAgent,
            LegalAgent, FinancialAgent, PersonalAgent, LearningAgent,
        )

        agents = [
            # Tier 1 — Core
            CodingAgent(), GitAgent(), CodeReviewAgent(), TestAgent(), ResearchAgent(),
            # Tier 2 — DevOps & Project Management
            IssueTrackerAgent(), WikiAgent(), DocumentationAgent(), DevOpsAgent(),
            ProjectManagementAgent(), SecurityAgent(),
            # Tier 3 — Communication & Administrative
            CommunicationAgent(), EmailAgent(), CalendarAgent(), AdministrativeAgent(),
            # Tier 4 — Business Support
            LegalAgent(), FinancialAgent(), PersonalAgent(), LearningAgent(),
        ]

        for agent in agents:
            registry.register(agent)

        logger.info(
            "Registered %d specialist agents + LegacyAgent (total: %d)",
            len(agents), len(agents) + 1,
        )
    else:
        logger.info("Specialist agents disabled — using LegacyAgent only")


app = FastAPI(
    title="Jervis Orchestrator",
    description="Python orchestrator service (LangGraph) for Jervis AI Assistant",
    version="0.1.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health():
    """Health check endpoint."""
    return {
        "status": "ok",
        "service": "orchestrator",
        "busy": _orchestration_semaphore.locked(),
    }


@app.get("/status/{thread_id}")
async def status(thread_id: str):
    """Get the current status of an orchestration thread.

    Polled by Kotlin server's BackgroundEngine.runOrchestratorResultLoop()
    to check on dispatched tasks.

    Returns:
        status: "running" | "interrupted" | "done" | "error" | "unknown"
        Plus additional fields depending on status.
    """
    graph_state = await get_graph_state(thread_id)

    if graph_state is None:
        return {"status": "unknown", "thread_id": thread_id}

    # Check if graph has pending next nodes (still running or interrupted)
    if graph_state.next:
        # Graph is paused – check if it's an interrupt
        interrupt_data = None
        if graph_state.tasks:
            for task in graph_state.tasks:
                if hasattr(task, "interrupts") and task.interrupts:
                    interrupt_data = task.interrupts[0].value
                    break

        if interrupt_data:
            return {
                "status": "interrupted",
                "thread_id": thread_id,
                "interrupt_action": interrupt_data.get("action", "unknown"),
                "interrupt_description": interrupt_data.get("description", ""),
            }

        # Check if thread has an active asyncio task — if not, it's stale
        # (checkpoint says "running" but no actual task after pod restart)
        if thread_id not in _active_tasks:
            logger.warning(
                "Stale thread detected: %s has pending nodes but no active task (pod restarted?)",
                thread_id,
            )
            return {
                "status": "error",
                "thread_id": thread_id,
                "error": "Orchestrace přerušena restartem — úloha bude automaticky obnovena",
            }

        return {"status": "running", "thread_id": thread_id}

    # Graph has no next nodes – check if completed or errored
    values = graph_state.values or {}
    error = values.get("error")
    if error:
        return {"status": "error", "thread_id": thread_id, "error": error}

    final_result = values.get("final_result")
    if final_result:
        return {
            "status": "done",
            "thread_id": thread_id,
            "summary": final_result,
            "branch": values.get("branch"),
            "artifacts": values.get("artifacts", []),
        }

    # Check if there's an active stream (still running)
    if thread_id in _active_streams:
        return {"status": "running", "thread_id": thread_id}

    return {"status": "unknown", "thread_id": thread_id}


@app.post("/orchestrate")
async def orchestrate(request: OrchestrateRequest):
    """Start orchestration workflow (blocking).

    Called by Kotlin server when a user sends a coding task.
    Returns final result after all steps complete.

    If the graph hits an interrupt (approval required), the response
    will have status="interrupted" with the approval request details.
    The Kotlin server should then present the approval to the user
    and call POST /approve/{thread_id} with the response.

    Returns 429 if another orchestration is already running.
    """
    if _orchestration_semaphore.locked():
        raise HTTPException(
            status_code=429,
            detail="Orchestrator busy – another orchestration is running",
        )

    try:
        async with _orchestration_semaphore:
            # Use task_id as thread_id for persistent state across messages
            thread_id = f"thread-{request.task_id}"
            final_state = await run_orchestration(request, thread_id=thread_id)

            # Check if graph was interrupted (approval required)
            graph_state = await get_graph_state(thread_id)
            if graph_state and graph_state.next:
                # Graph is paused at an interrupt – extract interrupt value
                interrupt_data = None
                if graph_state.tasks:
                    for task in graph_state.tasks:
                        if hasattr(task, "interrupts") and task.interrupts:
                            interrupt_data = task.interrupts[0].value
                            break

                return {
                    "task_id": request.task_id,
                    "status": "interrupted",
                    "thread_id": thread_id,
                    "interrupt": interrupt_data,
                    "summary": "Waiting for user approval",
                }

            step_results = [
                StepResult(**r) for r in final_state.get("step_results", [])
            ]

            return OrchestrateResponse(
                task_id=request.task_id,
                success=all(r.success for r in step_results) if step_results else False,
                summary=final_state.get("final_result", "No result"),
                branch=final_state.get("branch"),
                artifacts=final_state.get("artifacts", []),
                step_results=step_results,
                thread_id=thread_id,
            ).model_dump()
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Orchestration failed for task %s", request.task_id)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/orchestrate/stream")
async def orchestrate_stream(request: OrchestrateRequest):
    """Start orchestration with SSE streaming (fire-and-forget).

    Returns thread_id immediately. Client should poll GET /status/{thread_id}.
    Returns 429 if another orchestration is already running.
    """
    if _orchestration_semaphore.locked():
        raise HTTPException(
            status_code=429,
            detail="Orchestrator busy – another orchestration is running",
        )

    thread_id = f"thread-{request.task_id}-{uuid.uuid4().hex[:8]}"
    logger.info("ORCHESTRATE_STREAM_RECEIVED | task_id=%s | thread_id=%s", request.task_id, thread_id)
    queue: asyncio.Queue = asyncio.Queue()
    _active_streams[thread_id] = queue

    # Run orchestration in background with semaphore, pushing events to queue
    logger.info("CREATING_BACKGROUND_TASK | thread_id=%s", thread_id)
    task = asyncio.create_task(_run_and_stream(request, thread_id, queue))
    _active_tasks[thread_id] = task
    logger.info("BACKGROUND_TASK_CREATED | thread_id=%s | task_done=%s", thread_id, task.done())

    return {"thread_id": thread_id, "stream_url": f"/stream/{thread_id}"}


# Human-readable node messages for progress reporting
_NODE_MESSAGES = {
    # Legacy graph nodes
    "intake": "Analyzing task intent...",
    "evidence_pack": "Gathering evidence from KB...",
    "respond": "Generating response...",
    "plan": "Planning task execution...",
    "select_goal": "Selecting next goal...",
    "plan_steps": "Planning execution steps...",
    "execute_step": "Executing step...",
    "evaluate": "Evaluating results...",
    "advance_step": "Moving to next step...",
    "advance_goal": "Moving to next goal...",
    "git_operations": "Handling git operations...",
    "finalize": "Generating final report...",
    "plan_epic": "Planning epic execution waves...",
    "design": "Designing implementation structure...",
    # Delegation graph nodes (multi-agent system)
    "plan_delegations": "Planning agent delegations...",
    "execute_delegation": "Executing agent delegations...",
    "synthesize": "Synthesizing agent results...",
    # Memory Agent nodes
    "memory_load": "Loading memory context...",
    "memory_flush": "Persisting memory...",
}


async def _run_and_stream(
    request: OrchestrateRequest,
    thread_id: str,
    queue: asyncio.Queue,
):
    """Background task: run orchestration, push events to SSE queue and Kotlin.

    Push-based communication:
    - Each node_start event is pushed to Kotlin via report_progress()
    - Completion/error/interrupt is pushed via report_status_change()
    - SSE queue is still maintained for direct stream subscribers

    CRITICAL: Semaphore is released IMMEDIATELY after graph execution completes
    to prevent blocking subsequent requests. Status pushing happens outside lock.
    """
    query_preview = str(request.query)[:100] if request.query else "NO_QUERY"
    logger.info("ORCHESTRATION_START | thread_id=%s | task_id=%s | query=%s", thread_id, request.task_id, query_preview)
    try:
        # Acquire semaphore ONLY for graph execution — release immediately after
        async with _orchestration_semaphore:
            async for event in run_orchestration_streaming(request, thread_id):
                await queue.put(event)

                # Push progress to Kotlin server on each node transition
                if event.get("type") == "node_start":
                    node = event.get("node", "")
                    logger.info("ORCHESTRATION_NODE_START | thread_id=%s | node=%s | message=%s", thread_id, node, _NODE_MESSAGES.get(node, f"Running {node}..."))
                    await kotlin_client.report_progress(
                        task_id=request.task_id,
                        client_id=request.client_id,
                        node=node,
                        message=_NODE_MESSAGES.get(node, f"Running {node}..."),
                        goal_index=event.get("current_goal_index", 0),
                        total_goals=event.get("total_goals", 0),
                        step_index=event.get("current_step_index", 0),
                        total_steps=event.get("total_steps", 0),
                    )
                elif event.get("type") == "node_end":
                    node = event.get("node", "")
                    logger.info("ORCHESTRATION_NODE_END | thread_id=%s | node=%s", thread_id, node)
        # Semaphore RELEASED here! Other requests can now proceed.

        # Handle completion/interrupt OUTSIDE semaphore lock
        graph_state = await get_graph_state(thread_id)
        if graph_state and graph_state.next:
            interrupt_data = None
            if graph_state.tasks:
                for task in graph_state.tasks:
                    if hasattr(task, "interrupts") and task.interrupts:
                        interrupt_data = task.interrupts[0].value
                        break
            logger.info("ORCHESTRATION_INTERRUPTED | thread_id=%s | action=%s", thread_id, interrupt_data.get("action") if interrupt_data else "unknown")
            await queue.put({
                "type": "approval_required",
                "thread_id": thread_id,
                "interrupt": interrupt_data,
            })

            # Push interrupted status to Kotlin
            await kotlin_client.report_status_change(
                task_id=request.task_id,
                thread_id=thread_id,
                status="interrupted",
                interrupt_action=interrupt_data.get("action") if interrupt_data else None,
                interrupt_description=interrupt_data.get("description") if interrupt_data else None,
            )
        else:
            values = graph_state.values if graph_state else {}
            final_result = values.get("final_result", "")
            logger.info("ORCHESTRATION_DONE | thread_id=%s | result_len=%d | branch=%s | artifacts=%d",
                       thread_id, len(final_result), values.get("branch", "none"), len(values.get("artifacts", [])))
            await queue.put({"type": "done", "thread_id": thread_id})

            # Push done status to Kotlin with final state
            await kotlin_client.report_status_change(
                task_id=request.task_id,
                thread_id=thread_id,
                status="done",
                summary=final_result,
                branch=values.get("branch"),
                artifacts=values.get("artifacts", []),
            )
    except Exception as e:
        logger.error("ORCHESTRATION_ERROR | thread_id=%s | error_type=%s | error=%s", thread_id, type(e).__name__, str(e))
        logger.exception("Full exception traceback:")
        await queue.put({"type": "error", "error": str(e)})

        # Push error status to Kotlin
        await kotlin_client.report_status_change(
            task_id=request.task_id,
            thread_id=thread_id,
            status="error",
            error=str(e),
        )
    finally:
        await queue.put(None)  # Sentinel to close SSE
        _active_tasks.pop(thread_id, None)
        logger.info("ORCHESTRATION_CLEANUP | thread_id=%s | task_removed", thread_id)


@app.get("/stream/{thread_id}")
async def stream(thread_id: str):
    """SSE endpoint for streaming orchestration progress.

    Events:
        node_start:        {node: "decompose", ...}
        node_end:          {node: "execute_step", result: ...}
        approval_required: {interrupt: {action: "commit", ...}}
        done:              {thread_id: "..."}
        error:             {error: "..."}
    """
    queue = _active_streams.get(thread_id)
    if not queue:
        raise HTTPException(status_code=404, detail=f"No active stream for {thread_id}")

    async def event_generator():
        while True:
            event = await queue.get()
            if event is None:
                break
            yield {
                "event": event.get("type", "message"),
                "data": json.dumps(event),
            }
        # Cleanup
        _active_streams.pop(thread_id, None)

    return EventSourceResponse(event_generator())


@app.post("/approve/{thread_id}")
async def approve(thread_id: str, response: ApprovalResponse):
    """Handle approval response from user and resume graph (fire-and-forget).

    Called by Kotlin server when user approves/rejects a risky action.
    Resumes the graph in background via asyncio.create_task.
    Returns immediately – Kotlin polls GET /status/{thread_id} for result.
    """
    logger.info(
        "Approval for thread %s: approved=%s reason=%s",
        thread_id,
        response.approved,
        response.reason,
    )

    resume_value = {
        "approved": response.approved,
        "reason": response.reason,
        "modification": response.modification,
    }

    # Convert chat history to dict for graph state update
    chat_history_dict = response.chat_history.model_dump() if response.chat_history else None

    # Fire-and-forget: resume in background, result polled via GET /status
    task = asyncio.create_task(_resume_in_background(thread_id, resume_value, chat_history=chat_history_dict))
    _active_tasks[thread_id] = task

    return {"status": "resuming", "thread_id": thread_id}


@app.post("/interrupt/{thread_id}")
async def interrupt(thread_id: str):
    """Interrupt a running orchestration to allow higher-priority task to run.

    Gracefully cancels the current execution. LangGraph automatically saves
    the checkpoint to MongoDB, allowing the task to be resumed later from
    the same point.

    This is different from /cancel which marks the task as errored.
    Interrupt allows the task to be resumed later when queue space is available.
    """
    task = _active_tasks.get(thread_id)
    if not task:
        logger.warning("INTERRUPT_NOT_FOUND: No active task for thread_id=%s", thread_id)
        return {"success": False, "error": "No active task found"}

    try:
        # Cancel the asyncio task - LangGraph will save checkpoint automatically
        task.cancel()
        logger.info("INTERRUPT_SUCCESS: Gracefully interrupted thread_id=%s, checkpoint saved to MongoDB", thread_id)

        # Remove from active tasks
        _active_tasks.pop(thread_id, None)

        # Clean up SSE stream if exists
        queue = _active_streams.pop(thread_id, None)
        if queue:
            try:
                await queue.put(None)  # Signal stream end
            except Exception:
                pass

        return {"success": True}
    except Exception as e:
        logger.error("INTERRUPT_ERROR: Failed to interrupt thread_id=%s: %s", thread_id, e)
        return {"success": False, "error": str(e)}


@app.post("/cancel/{thread_id}")
async def cancel(thread_id: str):
    """Cancel a running orchestration."""
    task = _active_tasks.get(thread_id)
    if not task:
        raise HTTPException(status_code=404, detail=f"No active task for {thread_id}")
    task.cancel()
    logger.info("Cancelled orchestration: thread=%s", thread_id)
    # Push cancelled status to Kotlin
    # Extract task_id from thread_id format: "thread-{task_id}-{uuid}"
    parts = thread_id.split("-")
    task_id = parts[1] if len(parts) >= 2 else thread_id
    await kotlin_client.report_status_change(
        task_id=task_id,
        thread_id=thread_id,
        status="error",
        error="Orchestrace zrušena uživatelem",
    )
    return {"status": "cancelled"}


async def _resume_in_background(thread_id: str, resume_value: dict, chat_history: dict | None = None):
    """Background task: resume orchestration from interrupt with semaphore.

    Uses streaming to push real-time progress to Kotlin (same as _run_and_stream).
    After resumption completes, pushes status change to Kotlin.

    CRITICAL: Semaphore is released IMMEDIATELY after graph execution completes
    to prevent blocking subsequent requests. Status pushing happens outside lock.

    Args:
        chat_history: Fresh chat history from Kotlin, merged into graph state before resume
                      to ensure the LLM sees the latest conversation context.
    """
    # Extract task_id from thread_id format: "thread-{task_id}-{uuid}"
    parts = thread_id.split("-")
    task_id = parts[1] if len(parts) >= 3 else ""
    # task_id from thread_id is the MongoDB ObjectId — use it for progress
    # Also try to get client_id from graph state
    client_id = ""
    try:
        graph_state = await get_graph_state(thread_id)
        if graph_state and graph_state.values:
            task_data = graph_state.values.get("task", {})
            task_id = task_data.get("id", task_id)
            client_id = task_data.get("client_id", "")
    except Exception:
        pass

    try:
        # Acquire semaphore ONLY for graph execution — release immediately after
        async with _orchestration_semaphore:
            # Stream events to push progress to Kotlin in real-time
            async for event in resume_orchestration_streaming(thread_id, resume_value, chat_history=chat_history):
                # Push progress to Kotlin on each node transition
                if event.get("type") == "node_start":
                    node = event.get("node", "")
                    await kotlin_client.report_progress(
                        task_id=task_id,
                        client_id=client_id,
                        node=node,
                        message=_NODE_MESSAGES.get(node, f"Running {node}..."),
                        goal_index=event.get("current_goal_index", 0),
                        total_goals=event.get("total_goals", 0),
                        step_index=event.get("current_step_index", 0),
                        total_steps=event.get("total_steps", 0),
                    )

            logger.info("Resume completed for thread %s", thread_id)
        # Semaphore RELEASED here! Other requests can now proceed.

        # Handle completion/interrupt OUTSIDE semaphore lock
        graph_state = await get_graph_state(thread_id)

        if graph_state and graph_state.next:
            # Graph paused at interrupt again
            interrupt_data = None
            if graph_state.tasks:
                for task in graph_state.tasks:
                    if hasattr(task, "interrupts") and task.interrupts:
                        interrupt_data = task.interrupts[0].value
                        break

            await kotlin_client.report_status_change(
                task_id=task_id,
                thread_id=thread_id,
                status="interrupted",
                interrupt_action=interrupt_data.get("action") if interrupt_data else None,
                interrupt_description=interrupt_data.get("description") if interrupt_data else None,
            )
        else:
            # Completed
            values = graph_state.values if graph_state else {}
            error = values.get("error")
            if error:
                await kotlin_client.report_status_change(
                    task_id=task_id,
                    thread_id=thread_id,
                    status="error",
                    error=error,
                )
            else:
                await kotlin_client.report_status_change(
                    task_id=task_id,
                    thread_id=thread_id,
                    status="done",
                    summary=values.get("final_result"),
                    branch=values.get("branch"),
                    artifacts=values.get("artifacts", []),
                )
    except Exception as e:
        logger.exception("Background resume failed for thread %s: %s", thread_id, e)

        await kotlin_client.report_status_change(
            task_id=task_id,
            thread_id=thread_id,
            status="error",
            error=str(e),
        )
    finally:
        _active_tasks.pop(thread_id, None)
        logger.info("RESUME_CLEANUP | thread_id=%s | task_removed", thread_id)


# --- Chat History Compression ---


@app.post("/internal/compress-chat")
async def compress_chat(request: dict):
    """Compress a block of chat messages into a summary using LLM.

    Called asynchronously by Kotlin server after orchestration completes.
    Uses local LLM (fast tier) for compression — short prompt, short output.

    Request: { messages: [...], previous_summary: "...", client_id, task_id }
    Response: { summary, key_decisions, topics, is_checkpoint, checkpoint_reason }
    """
    try:
        messages_data = request.get("messages", [])
        previous_summary = request.get("previous_summary")
        task_id = request.get("task_id", "unknown")

        if not messages_data:
            return {
                "summary": "",
                "key_decisions": [],
                "topics": [],
                "is_checkpoint": False,
                "checkpoint_reason": None,
            }

        # Format messages for LLM
        formatted = []
        for m in messages_data:
            label = {"user": "Uživatel", "assistant": "Jervis"}.get(m["role"], m["role"])
            formatted.append(f"[{label}]: {m['content'][:500]}")
        conversation_text = "\n".join(formatted)

        previous_context = ""
        if previous_summary:
            previous_context = f"\n\nPředchozí kontext konverzace:\n{previous_summary}"

        llm_messages = [
            {
                "role": "system",
                "content": (
                    "Jsi analytik konverzací. Tvůj úkol je shrnout blok konverzace do stručného souhrnu.\n\n"
                    "Pravidla:\n"
                    "- Piš česky\n"
                    "- Souhrn: 2-3 věty shrnující hlavní téma a průběh (max 500 znaků)\n"
                    "- Klíčová rozhodnutí: důležitá rozhodnutí učiněná v konverzaci\n"
                    "- Témata: hlavní témata diskuze (stručné štítky)\n"
                    "- Pokud se směr konverzace ZÁSADNĚ změnil oproti předchozímu kontextu, "
                    "nastav is_checkpoint=true a uveď důvod\n\n"
                    "Odpověz JSON:\n"
                    "{\n"
                    '  "summary": "...",\n'
                    '  "key_decisions": ["rozhodnutí 1", "rozhodnutí 2"],\n'
                    '  "topics": ["téma 1", "téma 2"],\n'
                    '  "is_checkpoint": false,\n'
                    '  "checkpoint_reason": null\n'
                    "}"
                ),
            },
            {
                "role": "user",
                "content": (
                    f"Shrň tento blok konverzace:{previous_context}\n\n"
                    f"Konverzace k shrnutí:\n{conversation_text}"
                ),
            },
        ]

        response = await llm_provider.completion(
            messages=llm_messages,
            tier=ModelTier.LOCAL_FAST,
            max_tokens=2048,
            temperature=0.1,
        )
        content = response.choices[0].message.content

        # Parse JSON response
        import re
        parsed = {}
        try:
            parsed = json.loads(content)
        except (json.JSONDecodeError, TypeError):
            match = re.search(r"```(?:json)?\s*\n?(.*?)\n?\s*```", content, re.DOTALL)
            if match:
                try:
                    parsed = json.loads(match.group(1))
                except (json.JSONDecodeError, TypeError):
                    pass
            if not parsed:
                brace_start = content.find("{")
                brace_end = content.rfind("}")
                if brace_start != -1 and brace_end > brace_start:
                    try:
                        parsed = json.loads(content[brace_start:brace_end + 1])
                    except (json.JSONDecodeError, TypeError):
                        pass

        if not parsed:
            logger.warning("COMPRESS_CHAT: failed to parse LLM JSON for task %s", task_id)
            parsed = {"summary": content[:500]}

        return {
            "summary": parsed.get("summary", content[:500]),
            "key_decisions": parsed.get("key_decisions", []),
            "topics": parsed.get("topics", []),
            "is_checkpoint": parsed.get("is_checkpoint", False),
            "checkpoint_reason": parsed.get("checkpoint_reason"),
        }
    except Exception as e:
        logger.exception("Failed to compress chat for task %s", request.get("task_id"))
        raise HTTPException(status_code=500, detail=str(e))


# --- Entry point ---

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
