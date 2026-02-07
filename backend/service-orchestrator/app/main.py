"""FastAPI application – Python Orchestrator Service.

Accepts requests from Kotlin server, runs LangGraph orchestration,
streams progress via SSE, handles approval flow.
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
    get_checkpointer,
    run_orchestration,
    run_orchestration_streaming,
    resume_orchestration,
)
from app.models import (
    ApprovalResponse,
    OrchestrateRequest,
    OrchestrateResponse,
    StepResult,
)
from app.tools.kotlin_client import kotlin_client

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger(__name__)

# In-memory store for active orchestrations and their SSE queues
_active_streams: dict[str, asyncio.Queue] = {}
_pending_approvals: dict[str, asyncio.Future] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup and shutdown events."""
    logger.info("Orchestrator starting on port %d", settings.port)
    yield
    # Cleanup
    await kotlin_client.close()
    logger.info("Orchestrator stopped")


app = FastAPI(
    title="Jervis Orchestrator",
    description="Python orchestrator service (LangGraph) for Jervis AI Assistant",
    version="0.1.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health():
    """Health check endpoint."""
    return {"status": "ok", "service": "orchestrator"}


@app.post("/orchestrate")
async def orchestrate(request: OrchestrateRequest):
    """Start orchestration workflow (blocking).

    Called by Kotlin server when a user sends a coding task.
    Returns final result after all steps complete.
    For streaming progress, use /orchestrate/stream instead.
    """
    try:
        thread_id = f"thread-{request.task_id}-{uuid.uuid4().hex[:8]}"
        final_state = await run_orchestration(request, thread_id=thread_id)

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
    except Exception as e:
        logger.exception("Orchestration failed for task %s", request.task_id)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/orchestrate/stream")
async def orchestrate_stream(request: OrchestrateRequest):
    """Start orchestration with SSE streaming.

    Returns thread_id immediately. Client should subscribe to
    GET /stream/{thread_id} for live updates.
    """
    thread_id = f"thread-{request.task_id}-{uuid.uuid4().hex[:8]}"
    queue: asyncio.Queue = asyncio.Queue()
    _active_streams[thread_id] = queue

    # Run orchestration in background, pushing events to queue
    asyncio.create_task(_run_and_stream(request, thread_id, queue))

    return {"thread_id": thread_id, "stream_url": f"/stream/{thread_id}"}


async def _run_and_stream(
    request: OrchestrateRequest,
    thread_id: str,
    queue: asyncio.Queue,
):
    """Background task: run orchestration and push events to SSE queue."""
    try:
        async for event in run_orchestration_streaming(request, thread_id):
            await queue.put(event)
        await queue.put({"type": "done", "thread_id": thread_id})
    except Exception as e:
        logger.exception("Streaming orchestration failed: %s", e)
        await queue.put({"type": "error", "error": str(e)})
    finally:
        await queue.put(None)  # Sentinel to close SSE


@app.get("/stream/{thread_id}")
async def stream(thread_id: str):
    """SSE endpoint for streaming orchestration progress.

    Events:
        node_start: {node: "decompose", ...}
        node_end:   {node: "execute_step", result: ...}
        log:        {message: "Agent output line..."}
        approval:   {action: "commit", description: "..."}
        done:       {thread_id: "..."}
        error:      {error: "..."}
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
    """Handle approval response from user.

    Called by Kotlin server when user approves/rejects a risky action.
    Resumes the paused LangGraph execution.
    """
    logger.info(
        "Approval for thread %s: approved=%s reason=%s",
        thread_id,
        response.approved,
        response.reason,
    )

    # Resolve pending approval future if exists
    future = _pending_approvals.pop(thread_id, None)
    if future and not future.done():
        future.set_result(response)

    return {"status": "acknowledged", "thread_id": thread_id}


@app.post("/resume/{thread_id}")
async def resume(thread_id: str):
    """Resume a paused orchestration from checkpoint.

    Called after approval or external event.
    """
    logger.info("Resume requested for thread %s", thread_id)

    try:
        final_state = await resume_orchestration(thread_id)
        step_results = [
            StepResult(**r) for r in final_state.get("step_results", [])
        ]
        return OrchestrateResponse(
            task_id=final_state.get("task", {}).get("id", ""),
            success=all(r.success for r in step_results) if step_results else False,
            summary=final_state.get("final_result", "No result"),
            branch=final_state.get("branch"),
            artifacts=final_state.get("artifacts", []),
            step_results=step_results,
            thread_id=thread_id,
        ).model_dump()
    except Exception as e:
        logger.exception("Resume failed for thread %s", thread_id)
        raise HTTPException(status_code=500, detail=str(e))


async def request_user_approval(
    thread_id: str,
    action_type: str,
    description: str,
    details: dict | None = None,
) -> ApprovalResponse:
    """Request approval from user and wait for response.

    Called by graph nodes when they need user permission.
    Sends approval request via Kotlin server, then waits for POST /approve.
    """
    # Notify Kotlin server about pending approval
    try:
        task_id = thread_id.split("-")[1] if "-" in thread_id else thread_id
        await kotlin_client.emit_approval_request(
            client_id="",  # Will be filled from state
            project_id="",
            task_id=task_id,
            action_type=action_type,
            description=description,
            details=details or {},
        )
    except Exception as e:
        logger.warning("Failed to emit approval request: %s", e)

    # Push event to SSE stream
    queue = _active_streams.get(thread_id)
    if queue:
        await queue.put({
            "type": "approval",
            "action": action_type,
            "description": description,
            "details": details or {},
        })

    # Create future and wait for approval response
    loop = asyncio.get_event_loop()
    future = loop.create_future()
    _pending_approvals[thread_id] = future

    try:
        # Wait up to 24h for approval (configurable)
        response = await asyncio.wait_for(future, timeout=86400)
        return response
    except asyncio.TimeoutError:
        _pending_approvals.pop(thread_id, None)
        return ApprovalResponse(approved=False, reason="Approval timed out")


# --- Entry point ---

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
