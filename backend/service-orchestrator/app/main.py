"""FastAPI application – Python Orchestrator Service.

Accepts requests from Kotlin server, runs orchestration,
pushes progress via callbacks.

Communication architecture (push-based):
- Kotlin → Python: POST /orchestrate (fire-and-forget, returns thread_id)
- Python → Kotlin: POST /internal/orchestrator-progress (node progress, real-time)
- Python → Kotlin: POST /internal/orchestrator-status (completion/error)
- Kotlin → Python: GET /status/{thread_id} (safety-net fallback polling, 60s)

Foreground chat:
- Kotlin → Python: POST /chat (SSE streaming)

Python pushes progress and status changes to Kotlin via HTTP callbacks.
Kotlin broadcasts to UI via Flow-based event subscriptions.
Crash handler (atexit/SIGTERM) sends best-effort error callback for active tasks.
Polling is reduced to a 60s safety-net fallback only.

Concurrency:
- Router handles GPU/CPU routing, up to 10 concurrent orchestrations
- Dispatch returns 429 if busy; Kotlin retries on next polling cycle
"""

from __future__ import annotations

import asyncio
import atexit
import json
import logging
import signal
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from sse_starlette.sse import EventSourceResponse

from app.config import settings
from app.graph.orchestrator import (
    init_checkpointer,
    close_checkpointer,
)
from app.context.context_store import context_store
from app.context.session_memory import session_memory_store
from app.monitoring.delegation_metrics import metrics_collector
from app.tools.kotlin_client import kotlin_client
from app.chat.context import chat_context_assembler
from app.chat.router import router as chat_router
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

# In-memory store for active orchestration asyncio tasks (for cancellation and crash cleanup)
_active_tasks: dict[str, asyncio.Task] = {}


def _crash_cleanup():
    """Best-effort final callback on process exit.

    Reports error status for all active orchestration tasks so Kotlin server
    doesn't have to wait for the stuck-task timeout to detect the crash.
    Uses synchronous httpx (async loop may be dead at this point).
    """
    if not _active_tasks:
        return

    import httpx as _httpx

    base_url = settings.kotlin_server_url
    if not base_url:
        return

    for thread_id in list(_active_tasks.keys()):
        # Extract task_id from thread_id format: "graph-{task_id}-{uuid}"
        parts = thread_id.split("-")
        task_id = parts[1] if len(parts) >= 2 else thread_id
        try:
            _httpx.post(
                f"{base_url}/internal/orchestrator-status",
                json={
                    "taskId": task_id,
                    "threadId": thread_id,
                    "status": "error",
                    "error": "Orchestrátor se neočekávaně ukončil",
                },
                timeout=5.0,
            )
            logger.info("CRASH_CLEANUP: reported error for thread %s", thread_id)
        except Exception:
            pass  # Best-effort — nothing we can do if Kotlin is also down


atexit.register(_crash_cleanup)


def _sigterm_handler(signum, frame):
    """Handle SIGTERM (K8s pod termination) — report active tasks before exit."""
    logger.warning("SIGTERM received — cleaning up %d active tasks", len(_active_tasks))
    _crash_cleanup()
    raise SystemExit(0)


signal.signal(signal.SIGTERM, _sigterm_handler)


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

    # Initialize Graph Agent (sole orchestrator)
    from app.agent.persistence import agent_store
    from app.agent.langgraph_runner import init_graph_agent_checkpointer
    from app.agent.artifact_graph import artifact_graph_store
    await agent_store.init()
    await init_graph_agent_checkpointer()
    await artifact_graph_store.init()
    logger.info("Graph Agent ready (LangGraph + MongoDB + ArangoDB artifact graph)")

    # Memory Agent
    logger.info("Memory Agent ready (affairs + LQM)")

    # Initialize ChatContextAssembler (direct MongoDB access for chat history)
    await chat_context_assembler.init()
    logger.info("ChatContextAssembler ready (MongoDB: chat_messages, chat_summaries)")

    # Load OpenRouter API key from Kotlin server (DB settings → TIER_CONFIG)
    from app.llm.provider import refresh_openrouter_api_key
    await refresh_openrouter_api_key()

    # Start AgentTaskWatcher (monitors async coding agent K8s Jobs)
    from app.agent_task_watcher import agent_task_watcher
    await agent_task_watcher.start()

    yield

    # Stop AgentTaskWatcher
    await agent_task_watcher.stop()

    # Close ChatContextAssembler
    await chat_context_assembler.close()

    # Cleanup
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
    await context_store.close()
    await close_checkpointer()
    await kotlin_client.close()
    logger.info("Orchestrator stopped")


app = FastAPI(
    title="Jervis Orchestrator",
    description="Python orchestrator service for Jervis AI Assistant",
    version="0.1.0",
    lifespan=lifespan,
)

# Register chat context router (prepare-chat-context, compress-chat endpoints)
app.include_router(chat_router)


# --- Foreground Chat Endpoint ---

# Active chat stop events per session (for explicit stop button)
_active_chat_stops: dict[str, asyncio.Event] = {}


@app.post("/chat")
async def chat_endpoint(request_body: dict, request: Request):
    """Foreground chat — streaming SSE response.

    Receives a message from Kotlin, processes it through Jervis agentic loop
    (LLM + tools), and streams events back as SSE.

    Events: token, thinking, tool_call, tool_result, done, error

    Stop mechanisms:
    - SSE client disconnect → detected via request.is_disconnected()
    - Explicit POST /chat/stop → sets asyncio.Event for session
    """
    from app.chat.models import ChatRequest
    from app.agent.sse_handler import handle_chat_sse as handle_chat

    chat_request = ChatRequest(**request_body)
    logger.info("CHAT_REQUEST | session=%s | message=%s", chat_request.session_id, chat_request.message[:100])

    # Dedup: if an SSE stream is already running for this session, stop it first.
    # Prevents duplicate concurrent requests (e.g., kRPC retry, double-click).
    existing_event = _active_chat_stops.get(chat_request.session_id)
    if existing_event and not existing_event.is_set():
        logger.warning("CHAT_DEDUP | session=%s | stopping previous active stream", chat_request.session_id)
        existing_event.set()

    # Create stop event for this session
    disconnect_event = asyncio.Event()
    _active_chat_stops[chat_request.session_id] = disconnect_event

    async def event_generator():
        try:
            async for event in handle_chat(chat_request, disconnect_event):
                # Check if client disconnected
                if await request.is_disconnected():
                    logger.info("CHAT_DISCONNECT | session=%s", chat_request.session_id)
                    disconnect_event.set()
                    break

                yield {
                    "event": event.type,
                    "data": json.dumps({
                        "type": event.type,
                        "content": event.content,
                        "metadata": event.metadata,
                    }, ensure_ascii=False),
                }
        finally:
            _active_chat_stops.pop(chat_request.session_id, None)

    return EventSourceResponse(event_generator())


@app.post("/chat/stop")
async def chat_stop(request_body: dict):
    """Explicit stop for a running chat session.

    Called by Kotlin when user presses the Stop button.
    Sets the disconnect event so handler saves partial results and stops.
    """
    session_id = request_body.get("session_id", "")
    event = _active_chat_stops.get(session_id)
    if event:
        event.set()
        logger.info("CHAT_STOP | session=%s", session_id)
        return {"stopped": True}
    return {"stopped": False, "reason": "No active chat for session"}


@app.get("/health")
async def health():
    """Health check endpoint."""
    return {
        "status": "ok",
        "service": "orchestrator",
        "active_tasks": len(_active_tasks),
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
    from app.agent.langgraph_runner import _get_compiled_graph
    compiled = _get_compiled_graph()
    config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 200}
    graph_state = await compiled.aget_state(config)

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
            "keep_environment_running": values.get("keep_environment_running", False),
        }

    return {"status": "unknown", "thread_id": thread_id}


@app.post("/approve/{thread_id}")
async def approve(thread_id: str, request: Request):
    """Resume an interrupted orchestration with user's approval/clarification response.

    Called by Kotlin PythonOrchestratorClient.approve() after user responds
    to an ask_user interrupt. Fire-and-forget: resumes the graph in background,
    result pushed via status callback.

    Request body: {approved: bool, reason: str?, modification: str?, chat_history: dict?}
    """
    body = await request.json()
    approved = body.get("approved", False)
    reason = body.get("reason")
    modification = body.get("modification")
    chat_history = body.get("chat_history")

    logger.info(
        "APPROVE_START | thread_id=%s | approved=%s | reason=%s",
        thread_id, approved, reason,
    )

    # Build resume value that the interrupt() call will receive
    resume_value = {
        "approved": approved,
        "reason": reason or "",
        "modification": modification,
    }

    async def _run_resume():
        try:
            from app.agent.langgraph_runner import _get_compiled_graph
            from langgraph.types import Command
            compiled = _get_compiled_graph()
            config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 200}

            # Verify checkpoint exists — stale threads have no checkpoint
            existing = await compiled.aget_state(config)
            if not existing or not existing.values or "task" not in existing.values:
                raise ValueError(
                    f"No valid checkpoint for thread {thread_id} — "
                    f"thread may be stale"
                )

            # Update chat_history if provided
            if chat_history:
                await compiled.aupdate_state(config, {"chat_history": chat_history})

            final_state = await compiled.ainvoke(
                Command(resume=resume_value), config=config,
            )

            # Report completion
            # Extract task_id from thread_id format: "graph-{task_id}-{uuid}"
            parts = thread_id.split("-")
            task_id = parts[1] if len(parts) >= 2 else thread_id
            await kotlin_client.report_status_change(
                task_id=task_id,
                thread_id=thread_id,
                status="done",
                summary=final_state.get("final_result", ""),
                branch=final_state.get("branch"),
                artifacts=final_state.get("artifacts", []),
                keep_environment_running=final_state.get("keep_environment_running", False),
            )
        except asyncio.CancelledError:
            logger.info("APPROVE_INTERRUPTED | thread_id=%s — preempted", thread_id)
        except Exception as e:
            logger.exception("APPROVE_FAILED | thread_id=%s: %s", thread_id, e)
            parts = thread_id.split("-")
            task_id = parts[1] if len(parts) >= 2 else thread_id
            await kotlin_client.report_status_change(
                task_id=task_id,
                thread_id=thread_id,
                status="error",
                error=str(e),
            )
        finally:
            _active_tasks.pop(thread_id, None)

    task = asyncio.create_task(_run_resume())
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

        return {"success": True}
    except Exception as e:
        logger.error("INTERRUPT_ERROR: Failed to interrupt thread_id=%s: %s", thread_id, e)
        return {"success": False, "error": str(e)}


@app.post("/cancel/{thread_id}")
async def cancel(thread_id: str):
    """Cancel a running orchestration.

    Reports 'cancelled' status to Kotlin so the task doesn't stay stuck in
    PROCESSING.  Then cancels the asyncio task (CancelledError).
    """
    task = _active_tasks.get(thread_id)
    if not task:
        raise HTTPException(status_code=404, detail=f"No active task for {thread_id}")

    # Extract task_id from thread_id format: "graph-{task_id}-{uuid}"
    parts = thread_id.split("-")
    cancel_task_id = parts[1] if len(parts) >= 2 else thread_id

    # Mark graph as CANCELLED in persistence so agentic loop stops gracefully
    try:
        from app.agent.persistence import agent_store
        from app.agent.models import GraphStatus
        graph = await agent_store.load(cancel_task_id)
        if graph and graph.status not in (GraphStatus.COMPLETED, GraphStatus.FAILED):
            graph.status = GraphStatus.CANCELLED
            await agent_store.save(graph)
            logger.info("Marked graph %s as CANCELLED", graph.id)
    except Exception as e:
        logger.warning("Failed to mark graph as cancelled: %s", e)

    # Report cancelled status to Kotlin so UI updates immediately
    try:
        from app.tools.kotlin_client import kotlin_client
        await kotlin_client.report_status_change(
            task_id=cancel_task_id,
            thread_id=thread_id,
            status="cancelled",
            summary="Úkol zrušen uživatelem.",
        )
    except Exception as e:
        logger.warning("Failed to report cancel to Kotlin: %s", e)

    # Cancel the asyncio task — CancelledError will fire in _run_background
    task.cancel()
    _active_tasks.pop(thread_id, None)
    logger.info("Cancelled orchestration: thread=%s task=%s", thread_id, cancel_task_id)
    return {"status": "cancelled", "thread_id": thread_id}


@app.get("/graph/{task_id}")
async def get_task_graph(task_id: str):
    """Return the full AgentGraph for a given task_id.

    Called by Kotlin to serve graph data to the UI.
    For master map, returns the live RAM version (always fresh).
    """
    from app.agent.persistence import agent_store

    if task_id == "master":
        # Return live RAM version — always up-to-date
        graph = agent_store.get_memory_map_cached()
        if not graph:
            graph = await agent_store.get_or_create_memory_map()
    else:
        # Try RAM cache first (active sub-graphs), then DB
        graph = agent_store.get_cached_subgraph(task_id)
        if not graph:
            graph = await agent_store.load(task_id)

    if not graph:
        raise HTTPException(status_code=404, detail=f"No graph for task {task_id}")
    return graph.model_dump()


# --- Entry point ---

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
