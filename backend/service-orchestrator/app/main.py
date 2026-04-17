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
from datetime import datetime, timedelta, timezone

from fastapi import FastAPI, HTTPException, Request
from sse_starlette.sse import EventSourceResponse

from app.config import settings
from app.agent.models import AgentGraph, GraphStatus, GraphType
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
    Uses a one-shot sync gRPC channel because the asyncio loop is usually
    dead by the time atexit runs.
    """
    if not _active_tasks:
        return

    if not settings.kotlin_server_url:
        return

    import grpc
    from jervis.common import types_pb2
    from jervis.server import orchestrator_progress_pb2, orchestrator_progress_pb2_grpc

    url = settings.kotlin_server_url.rstrip("/")
    if "://" in url:
        url = url.split("://", 1)[1]
    host = url.split("/")[0].split(":")[0]
    target = f"{host}:5501"

    try:
        channel = grpc.insecure_channel(target)
        stub = orchestrator_progress_pb2_grpc.ServerOrchestratorProgressServiceStub(channel)
    except Exception:
        return

    for thread_id in list(_active_tasks.keys()):
        # Extract task_id from thread_id format: "graph-{task_id}-{uuid}"
        parts = thread_id.split("-")
        task_id = parts[1] if len(parts) >= 2 else thread_id
        try:
            stub.OrchestratorStatus(
                orchestrator_progress_pb2.OrchestratorStatusRequest(
                    ctx=types_pb2.RequestContext(),
                    task_id=task_id,
                    thread_id=thread_id,
                    status="error",
                    error="Orchestrátor se neočekávaně ukončil",
                ),
                timeout=5.0,
            )
            logger.info("CRASH_CLEANUP: reported error for thread %s", thread_id)
        except Exception:
            pass  # Best-effort — nothing we can do if Kotlin is also down

    try:
        channel.close()
    except Exception:
        pass


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

    # Start proactive scheduler (morning briefing, overdue check, weekly summary)
    from app.proactive import scheduler as proactive_scheduler
    await proactive_scheduler.start()

    yield

    # Stop proactive scheduler
    await proactive_scheduler.stop()

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

# Register meeting helper router (real-time translation + suggestions)
from app.meeting.routes import router as meeting_helper_router
app.include_router(meeting_helper_router)

# Register proactive communication router (scheduled triggers)
from app.proactive.routes import router as proactive_router
app.include_router(proactive_router)

# Register Claude companion router (adhoc + session lifecycle)
from app.companion.routes import router as companion_router
app.include_router(companion_router)


# --- Voice Pipeline Endpoint ---

@app.post("/voice/process")
async def voice_process(request_body: dict):
    """Process transcribed voice input — intent classification + quick/full response.

    Called by Kotlin VoiceChatRouting AFTER Whisper STT.
    Receives text (not audio), classifies intent, and streams SSE events.

    Events: preliminary_answer, responding, token, response, stored, error, done
    """
    from app.voice.models import VoiceStreamRequest
    from app.voice.stream_handler import handle_voice_stream

    voice_request = VoiceStreamRequest(**request_body)
    logger.info("VOICE_PROCESS | text=%s | source=%s", voice_request.text[:80], voice_request.source)

    async def event_generator():
        try:
            async for event in handle_voice_stream(voice_request):
                yield {"event": event.event, "data": json.dumps(event.data, ensure_ascii=False)}
        except Exception as e:
            logger.exception("Voice handler failed: %s", e)
            yield {"event": "error", "data": json.dumps({"text": str(e)[:100]})}
            yield {"event": "done", "data": "{}"}

    return EventSourceResponse(event_generator())


@app.post("/voice/hint")
async def voice_hint(request_body: dict):
    """Generate a KB-based hint for live assist mode.

    Called by Kotlin server during session-based voice streaming.
    Returns JSON with hint text (or empty if nothing relevant).
    """
    from app.voice.stream_handler import generate_hint

    text = request_body.get("text", "").strip()
    client_id = request_body.get("client_id", "")
    project_id = request_body.get("project_id", "")

    if not text:
        return {"hint": None}

    hint = await generate_hint(text, client_id, project_id)
    return {"hint": hint}


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
async def get_task_graph(task_id: str, client_id: str | None = None):
    """Return the full AgentGraph for a given task_id.

    Called by Kotlin to serve graph data to the UI.
    For master graph, returns the live RAM version (always fresh).
    When client_id is provided for master graph, returns client-filtered copy.
    """
    from app.agent.persistence import agent_store

    if task_id == "master":
        # Return live RAM version — always up-to-date
        graph = agent_store.get_memory_graph_cached()
        if not graph:
            graph = await agent_store.get_or_create_memory_graph()
        if not graph:
            raise HTTPException(status_code=404, detail="Memory graph not found")
        # Client isolation: filter to show only this client's vertices
        if client_id:
            return _filter_graph_for_client(graph, client_id)
        return graph.model_dump()
    else:
        # Try RAM cache first (active sub-graphs), then DB
        graph = agent_store.get_cached_subgraph(task_id)
        if not graph:
            graph = await agent_store.load(task_id)
        if not graph:
            # Fallback: localContext stores graph.id ("tg-..."), but load() queries by task_id.
            # Try searching by the graph's own id field.
            graph = await agent_store.load_by_graph_id(task_id)

    if not graph:
        raise HTTPException(status_code=404, detail=f"No graph for task {task_id}")

    result = graph.model_dump()
    # Add hidden flag for completed thinking graphs older than 10min (debug visibility)
    if graph.graph_type == GraphType.THINKING_GRAPH:
        from app.agent.persistence import _THINKING_GRAPH_HIDE_S
        if graph.status in (GraphStatus.COMPLETED, GraphStatus.FAILED) and graph.completed_at:
            hide_cutoff = (datetime.now(timezone.utc) - timedelta(seconds=_THINKING_GRAPH_HIDE_S)).isoformat()
            if graph.completed_at < hide_cutoff:
                result["hidden"] = True
    return result


def _filter_graph_for_client(graph: "AgentGraph", client_id: str) -> dict:
    """Return a filtered copy of the memory graph for a specific client.

    Keeps: root, hierarchy ancestors, and all vertices belonging to client_id.
    Never mutates the singleton graph.
    """
    from app.agent.models import VertexType, GLOBAL_CLIENT_ID

    keep_ids: set[str] = set()

    # Keep root
    if graph.root_vertex_id:
        keep_ids.add(graph.root_vertex_id)

    # Keep all vertices belonging to this client
    for vid, v in graph.vertices.items():
        if v.client_id == client_id:
            keep_ids.add(vid)

    # Walk parent chains to include necessary hierarchy vertices
    for vid in list(keep_ids):
        current = graph.vertices.get(vid)
        while current and current.parent_id:
            keep_ids.add(current.parent_id)
            current = graph.vertices.get(current.parent_id)

    result = graph.model_dump()
    result["vertices"] = {
        vid: vdata for vid, vdata in result["vertices"].items()
        if vid in keep_ids
    }
    result["edges"] = [
        e for e in result["edges"]
        if e["source_id"] in keep_ids and e["target_id"] in keep_ids
    ]
    return result


# --- Memory Graph Admin Endpoints ---


@app.delete("/graph/master/vertex/{vertex_id}")
async def delete_memory_graph_vertex(vertex_id: str):
    """Delete a vertex from the memory graph (RAM + DB flush)."""
    from app.agent.persistence import agent_store

    graph = agent_store.get_memory_graph_cached()
    if not graph:
        raise HTTPException(status_code=404, detail="Memory graph not loaded")

    if vertex_id == graph.root_vertex_id:
        raise HTTPException(status_code=400, detail="Cannot delete root vertex")

    if vertex_id not in graph.vertices:
        raise HTTPException(status_code=404, detail=f"Vertex '{vertex_id}' not found")

    del graph.vertices[vertex_id]
    graph.edges = [e for e in graph.edges if e.source_id != vertex_id and e.target_id != vertex_id]
    agent_store._dirty.add(graph.task_id)
    await agent_store.flush_dirty()
    try:
        await kotlin_client.notify_memory_graph_changed()
    except Exception:
        pass
    return {"deleted": vertex_id, "remaining_vertices": len(graph.vertices)}


@app.patch("/graph/master/vertex/{vertex_id}")
async def update_memory_graph_vertex(vertex_id: str, request: Request):
    """Update a vertex in the memory graph (title, status, description).

    Body: { "title": "...", "status": "completed", "description": "..." }
    All fields optional.
    """
    from app.agent.persistence import agent_store
    from app.agent.models import VertexStatus

    graph = agent_store.get_memory_graph_cached()
    if not graph:
        raise HTTPException(status_code=404, detail="Memory graph not loaded")

    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        raise HTTPException(status_code=404, detail=f"Vertex '{vertex_id}' not found")

    body = await request.json()
    if "title" in body:
        vertex.title = body["title"]
    if "description" in body:
        vertex.description = body["description"]
    if "parent_id" in body:
        vertex.parent_id = body["parent_id"]
    if "input_request" in body:
        vertex.input_request = body["input_request"]
    if "status" in body:
        try:
            vertex.status = VertexStatus(body["status"])
        except ValueError:
            raise HTTPException(status_code=400, detail=f"Invalid status: {body['status']}")

    agent_store._dirty.add(graph.task_id)
    await agent_store.flush_dirty()
    try:
        await kotlin_client.notify_memory_graph_changed()
    except Exception:
        pass
    return {"updated": vertex_id, "vertex": vertex.model_dump()}


@app.post("/graph/master/vertex")
async def create_memory_graph_vertex(request: Request):
    """Create a new vertex in the memory graph.

    Body: { "id": "v-...", "title": "...", "vertex_type": "group", "status": "completed",
            "parent_id": "v-client-...", "input_request": "...", "client_id": "..." }
    """
    from app.agent.persistence import agent_store
    from app.agent.models import GraphVertex, VertexType, VertexStatus

    graph = agent_store.get_memory_graph_cached()
    if not graph:
        raise HTTPException(status_code=404, detail="Memory graph not loaded")

    body = await request.json()
    vertex_id = body.get("id")
    if not vertex_id:
        raise HTTPException(status_code=400, detail="'id' is required")
    if vertex_id in graph.vertices:
        raise HTTPException(status_code=409, detail=f"Vertex '{vertex_id}' already exists")

    vertex = GraphVertex(
        id=vertex_id,
        title=body.get("title", ""),
        description=body.get("description", ""),
        vertex_type=VertexType(body.get("vertex_type", "executor")),
        status=VertexStatus(body.get("status", "completed")),
        parent_id=body.get("parent_id"),
        depth=body.get("depth", 1),
        client_id=body.get("client_id", ""),
        input_request=body.get("input_request", ""),
    )

    graph.vertices[vertex_id] = vertex
    agent_store._dirty.add(graph.task_id)
    await agent_store.flush_dirty()
    try:
        await kotlin_client.notify_memory_graph_changed()
    except Exception:
        pass
    return {"created": vertex_id, "total_vertices": len(graph.vertices)}


@app.post("/graph/master/cleanup")
async def force_cleanup_memory_graph():
    """Force cleanup of the memory graph — removes stale vertices."""
    from app.agent.persistence import agent_store

    graph = agent_store.get_memory_graph_cached()
    if not graph:
        raise HTTPException(status_code=404, detail="Memory graph not loaded")

    removed = agent_store.cleanup_memory_graph()
    if removed > 0:
        if hasattr(agent_store, "_pending_archive") and agent_store._pending_archive:
            await agent_store._archive_vertices(agent_store._pending_archive)
            agent_store._pending_archive = []
        await agent_store.flush_dirty()

    try:
        await kotlin_client.notify_memory_graph_changed()
    except Exception:
        pass
    return {
        "removed": removed,
        "remaining_vertices": len(graph.vertices),
    }


@app.get("/memory/search")
async def search_memory(query: str, client_id: str):
    """3-tier memory search cascade.

    Tier 1: RAM (Paměťový graf) — instant
    Tier 2: MongoDB archive (7d) — fast
    Tier 3: KB — slower

    Client isolation enforced at all tiers.
    """
    import httpx
    from app.agent.persistence import agent_store

    if not client_id:
        raise HTTPException(status_code=400, detail="client_id required")

    results: dict[str, list] = {"tier1_ram": [], "tier2_archive": [], "tier3_kb": []}
    query_lower = query.lower()

    # Tier 1: RAM search in memory graph
    graph = agent_store.get_memory_graph_cached()
    if graph:
        for v in graph.vertices.values():
            if v.client_id != client_id:
                continue
            searchable = f"{v.title or ''} {v.result_summary or ''} {v.description or ''}"
            if query_lower in searchable.lower():
                results["tier1_ram"].append({
                    "vertex_id": v.id,
                    "title": v.title,
                    "vertex_type": v.vertex_type.value if hasattr(v.vertex_type, 'value') else str(v.vertex_type),
                    "result_summary": v.result_summary or "",
                    "status": v.status.value if hasattr(v.status, 'value') else str(v.status),
                    "completed_at": v.completed_at,
                })

    # Tier 2: MongoDB archive (only if Tier 1 insufficient)
    if len(results["tier1_ram"]) < 5:
        archive_results = await agent_store.search_archive(query, client_id, limit=10)
        results["tier2_archive"] = archive_results

    # Tier 3: KB (only if Tier 1+2 insufficient)
    if len(results["tier1_ram"]) + len(results["tier2_archive"]) < 3:
        kb_url = settings.knowledgebase_url
        if kb_url:
            try:
                async with httpx.AsyncClient(timeout=10) as http:
                    resp = await http.post(
                        f"{kb_url}/api/v1/retrieve",
                        json={"query": query, "clientId": client_id, "maxResults": 5},
                    )
                    if resp.status_code == 200:
                        results["tier3_kb"] = resp.json().get("chunks", [])
            except Exception:
                pass

    return results


# ---------------------------------------------------------------------------
# 3-Phase Idle Maintenance Pipeline
# ---------------------------------------------------------------------------


@app.post("/maintenance/run")
async def run_maintenance(phase: int = 1, client_id: str | None = None):
    """3-phase idle GPU maintenance pipeline.

    Phase 1 (CPU-only, <5s): memory graph cleanup, thinking graph eviction,
    LQM drain, affair archival. Returns next client for Phase 2.

    Phase 2 (GPU-light, 30s-2min): KB dedup for ONE client.
    Uses NORMAL priority LLM calls → auto-preempted by CRITICAL.
    """
    from app.agent.persistence import agent_store

    if phase == 1:
        return await _maintenance_phase1(agent_store)
    elif phase == 2:
        if not client_id:
            raise HTTPException(status_code=400, detail="client_id required for phase 2")
        return await _maintenance_phase2(agent_store, client_id)
    else:
        raise HTTPException(status_code=400, detail=f"Unknown phase: {phase}")


async def _maintenance_phase1(agent_store) -> dict:
    """Phase 1: CPU-only maintenance — idempotent, killable."""
    # 1. Memory graph cleanup (per-client, 24h lifecycle)
    mem_removed = agent_store.cleanup_memory_graph()
    if mem_removed > 0 and agent_store._pending_archive:
        await agent_store._persist_to_kb(agent_store._pending_archive)
        await agent_store._archive_vertices(agent_store._pending_archive)
        agent_store._pending_archive = []
    await agent_store.flush_dirty()

    # 2. Thinking graph RAM eviction
    thinking_evicted = agent_store.cleanup_thinking_graphs()

    # 3. Drain LQM write buffer → KB
    lqm_drained = await _drain_global_lqm()

    # 4. Archive old resolved affairs
    affairs_archived = await _archive_old_affairs()

    # 5. Find next client for Phase 2
    all_client_ids = await _get_all_client_ids()
    next_client = None
    if all_client_ids:
        next_client = await agent_store.get_oldest_maintenance_client("kb_dedup", all_client_ids)

    logger.info(
        "MAINTENANCE_P1: mem=%d thinking=%d lqm=%d affairs=%d next=%s",
        mem_removed, thinking_evicted, lqm_drained, affairs_archived, next_client,
    )
    return {
        "phase": 1,
        "mem_removed": mem_removed,
        "thinking_evicted": thinking_evicted,
        "lqm_drained": lqm_drained,
        "affairs_archived": affairs_archived,
        "next_client_for_phase2": next_client,
    }


async def _maintenance_phase2(agent_store, client_id: str) -> dict:
    """Phase 2: GPU-light KB maintenance for one client."""
    findings = await _run_kb_dedup(client_id)
    summary = "; ".join(findings[:5]) if findings else "No issues found"
    await agent_store.update_maintenance_cycle(client_id, "kb_dedup", summary)

    logger.info("MAINTENANCE_P2: client=%s findings=%d", client_id, len(findings))
    return {
        "phase": 2,
        "client_id": client_id,
        "findings": findings,
    }


async def _drain_global_lqm() -> int:
    """Drain LQM write buffer and flush to KB."""
    import httpx
    from app.memory.agent import _get_or_create_lqm

    lqm = _get_or_create_lqm()
    writes = await lqm.drain_write_buffer()
    if not writes:
        return 0

    kb_url = settings.knowledgebase_write_url or settings.knowledgebase_url
    if not kb_url:
        return 0

    count = 0
    for write in writes:
        try:
            async with httpx.AsyncClient(timeout=10) as http:
                resp = await http.post(
                    f"{kb_url}/api/v1/ingest",
                    json={
                        "sourceUrn": write.source_urn,
                        "clientId": write.metadata.get("client_id", ""),
                        "content": write.content,
                        "kind": write.kind,
                        "metadata": write.metadata,
                    },
                    headers={"X-Ollama-Priority": "1"},
                )
                if resp.status_code in (200, 201, 202):
                    lqm.mark_synced(write.source_urn)
                    count += 1
        except Exception:
            pass
    return count


async def _archive_old_affairs() -> int:
    """Archive RESOLVED affairs older than 24h from LQM."""
    from app.memory.agent import _get_or_create_lqm
    from app.memory.models import AffairStatus

    lqm = _get_or_create_lqm()
    cutoff = (datetime.now(timezone.utc) - timedelta(hours=24)).isoformat()
    archived = 0

    # Scan all affairs for RESOLVED ones older than 24h
    all_affairs = list(lqm._affairs.values())
    for affair in all_affairs:
        if affair.status != AffairStatus.RESOLVED:
            continue
        if affair.updated_at and affair.updated_at < cutoff:
            lqm.remove_affair(affair.id)
            archived += 1
    return archived


async def _get_all_client_ids() -> list[str]:
    """Get all client IDs from memory graph hierarchy vertices."""
    from app.agent.persistence import agent_store
    from app.agent.models import VertexType

    graph = agent_store.get_memory_graph_cached()
    if not graph:
        return []
    return [
        v.input_request or v.client_id
        for v in graph.vertices.values()
        if v.vertex_type == VertexType.CLIENT and (v.input_request or v.client_id)
    ]


async def _run_kb_dedup(client_id: str) -> list[str]:
    """Run KB deduplication for one client using LLM similarity check.

    Searches KB for potential duplicates, uses LLM to confirm, merges via alias API.
    Uses NORMAL priority → auto-preempted by CRITICAL (foreground chat).
    """
    import httpx

    kb_url = settings.knowledgebase_url
    if not kb_url:
        return []

    findings: list[str] = []
    try:
        # Search for potential duplicates — look for common topics
        async with httpx.AsyncClient(timeout=30) as http:
            resp = await http.post(
                f"{kb_url}/api/v1/retrieve",
                json={
                    "query": "duplicate similar redundant",
                    "clientId": client_id,
                    "maxResults": 20,
                },
                headers={"X-Ollama-Priority": "1"},
            )
            if resp.status_code != 200:
                return []
            chunks = resp.json().get("chunks", [])

        if len(chunks) < 2:
            return []

        # Group by source URN prefix to find near-duplicates
        seen_content: dict[str, str] = {}  # content_hash → source_urn
        for chunk in chunks:
            content = chunk.get("content", "")[:200]
            urn = chunk.get("source_urn", "")
            # Simple dedup: if two entries have very similar short content
            for existing_content, existing_urn in seen_content.items():
                if _similarity(content, existing_content) > 0.85:
                    findings.append(f"Potential duplicate: {urn} ≈ {existing_urn}")
                    break
            else:
                seen_content[content] = urn

    except Exception as e:
        logger.warning("KB dedup failed for client %s: %s", client_id, e)

    return findings


def _similarity(a: str, b: str) -> float:
    """Simple Jaccard similarity on word sets."""
    if not a or not b:
        return 0.0
    words_a = set(a.lower().split())
    words_b = set(b.lower().split())
    if not words_a or not words_b:
        return 0.0
    intersection = len(words_a & words_b)
    union = len(words_a | words_b)
    return intersection / union if union > 0 else 0.0


@app.post("/graph/master/purge-stale")
async def purge_stale_vertices():
    """Purge stale RUNNING vertices and duplicates from memory graph.

    Targets:
    - Duplicate vertices with same title (keeps most recent)
    - RUNNING vertices older than 1 hour (marks as FAILED)
    """
    from app.agent.persistence import agent_store
    from app.agent.models import VertexStatus
    from datetime import datetime, timezone, timedelta

    graph = agent_store.get_memory_graph_cached()
    if not graph:
        raise HTTPException(status_code=404, detail="Memory graph not loaded")

    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=1)
    purged = 0
    failed = 0

    # 1. Find and remove duplicate titles (keep most recent by completed_at)
    title_groups: dict[str, list[str]] = {}
    for vid, v in graph.vertices.items():
        if v.vertex_type in ("task_ref", "request", "incoming") and vid != graph.root_vertex_id:
            key = v.title.strip().lower()
            title_groups.setdefault(key, []).append(vid)

    for title, vids in title_groups.items():
        if len(vids) > 1:
            # Sort by completed_at desc, keep first
            sorted_vids = sorted(
                vids,
                key=lambda vid: graph.vertices[vid].completed_at or "0",
                reverse=True,
            )
            for vid in sorted_vids[1:]:  # Remove all but most recent
                del graph.vertices[vid]
                graph.edges = [e for e in graph.edges if e.source_id != vid and e.target_id != vid]
                purged += 1

    # 2. Mark stale RUNNING vertices as FAILED
    for vid, v in list(graph.vertices.items()):
        if v.status == VertexStatus.RUNNING:
            started = None
            if v.started_at:
                try:
                    started = datetime.fromisoformat(v.started_at.replace("Z", "+00:00"))
                except (ValueError, AttributeError):
                    pass
            if started and started < cutoff:
                v.status = VertexStatus.FAILED
                v.result_summary = "Stale RUNNING vertex — auto-failed by purge"
                v.completed_at = now.isoformat()
                failed += 1

    if purged > 0 or failed > 0:
        agent_store._dirty.add(graph.task_id)
        await agent_store.flush_dirty()

    try:
        await kotlin_client.notify_memory_graph_changed()
    except Exception:
        pass
    return {
        "duplicates_removed": purged,
        "stale_running_failed": failed,
        "remaining_vertices": len(graph.vertices),
    }


# --- Entry point ---

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
