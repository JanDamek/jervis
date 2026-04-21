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
# app.chat.router retired — /chat, /orchestrate, /qualify, /job-logs + the
# two /internal/* chat-context routes are all on gRPC now.
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

    # Start pod-to-pod gRPC surface (:5501). Runs alongside FastAPI (:8090)
    # for the duration of Phase 3 — each slice moves more traffic off REST.
    from app.grpc_server import start_grpc_server

    grpc_port = int(getattr(settings, "grpc_port", 5501))
    grpc_server = await start_grpc_server(port=grpc_port)
    app.state.grpc_server = grpc_server

    yield

    # Drain gRPC first so in-flight RPCs finish, then stop the rest.
    await grpc_server.stop(grace=5.0)
    logger.info("gRPC server stopped")

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

# Chat + meeting helper + job logs all on gRPC now — no REST routers registered.

# Proactive triggers — scheduler calls functions directly (no HTTP route).
# See app/proactive/routes.py + scheduler.py.

# Companion — fully migrated to OrchestratorCompanionService gRPC.
# No HTTP router registered.


# /voice/process + /voice/hint migrated to gRPC
# (OrchestratorVoiceService.{Process,Hint} on :5501 — see app/grpc_server.py).


# --- Foreground Chat Endpoint ---

# Active chat stop events per session (for explicit stop button)
_active_chat_stops: dict[str, asyncio.Event] = {}


# /chat (streaming SSE) + /chat/stop migrated to gRPC
# (OrchestratorChatService.{Chat,Stop} on :5501 — see app/grpc_server.py).
# `_active_chat_stops` stays on main.py because the chat servicer and
# the stop RPC both mutate it; ChatRequest dedup logic moved into the
# servicer verbatim.


# /status/{thread_id} migrated to gRPC (OrchestratorControlService.GetStatus).
# /health kept only for K8s probes — actual health monitoring goes through
# OrchestratorControlService.Health on :5501.


@app.get("/health")
async def health():
    return {"status": "ok", "service": "orchestrator", "active_tasks": len(_active_tasks)}


# /approve/{thread_id}, /interrupt/{thread_id}, /cancel/{thread_id} migrated to
# gRPC (OrchestratorControlService.{Approve,Interrupt,Cancel} on :5501).


# /graph/{task_id} migrated to gRPC
# (OrchestratorGraphService.GetTaskGraph on :5501). The servicer
# delegates to _filter_graph_for_client below for the master-graph
# client-filter branch.


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


# ---------------------------------------------------------------------------
# 3-Phase Idle Maintenance Pipeline
# ---------------------------------------------------------------------------


# /maintenance/run migrated to gRPC
# (OrchestratorGraphService.RunMaintenance on :5501). The servicer
# delegates to the existing _maintenance_phase{1,2} helpers below.


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

    from jervis_contracts import kb_client

    count = 0
    for write in writes:
        try:
            await kb_client.ingest(
                caller="orchestrator.main.flush_write_buffer",
                source_urn=write.source_urn,
                content=write.content,
                client_id=write.metadata.get("client_id", ""),
                kind=write.kind,
                metadata={str(k): v for k, v in (write.metadata or {}).items()},
                timeout=10.0,
            )
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
    findings: list[str] = []
    try:
        from jervis_contracts import kb_client

        # Search for potential duplicates — look for common topics
        chunks = await kb_client.retrieve(
            caller="orchestrator.main.dedup_search",
            query="duplicate similar redundant",
            client_id=client_id,
            max_results=20,
            timeout=30.0,
        )

        if len(chunks) < 2:
            return []

        # Group by source URN prefix to find near-duplicates
        seen_content: dict[str, str] = {}  # content_hash → source_urn
        for chunk in chunks:
            content = (chunk.get("content", "") or "")[:200]
            urn = chunk.get("sourceUrn", "")
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


# --- Entry point ---

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
