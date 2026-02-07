"""FastAPI application – Python Orchestrator Service.

Accepts requests from Kotlin server, runs LangGraph orchestration,
streams progress via SSE, handles approval flow via LangGraph interrupt().

Communication architecture:
- Kotlin → Python: POST /orchestrate (all data sent upfront in request)
- Python → UI: SSE /stream/{thread_id} (progress + approval requests)
- UI → Python: POST /approve/{thread_id} (resumes interrupted graph)
- No Python → Kotlin callbacks needed (full request model)
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
    get_graph_state,
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

# In-memory store for active SSE streams
_active_streams: dict[str, asyncio.Queue] = {}


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

    If the graph hits an interrupt (approval required), the response
    will have status="interrupted" with the approval request details.
    The Kotlin server should then present the approval to the user
    and call POST /approve/{thread_id} with the response.
    """
    try:
        thread_id = f"thread-{request.task_id}-{uuid.uuid4().hex[:8]}"
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

        # Check if graph was interrupted
        graph_state = await get_graph_state(thread_id)
        if graph_state and graph_state.next:
            interrupt_data = None
            if graph_state.tasks:
                for task in graph_state.tasks:
                    if hasattr(task, "interrupts") and task.interrupts:
                        interrupt_data = task.interrupts[0].value
                        break
            await queue.put({
                "type": "approval_required",
                "thread_id": thread_id,
                "interrupt": interrupt_data,
            })
        else:
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
    """Handle approval response from user and resume graph.

    Called by Kotlin server when user approves/rejects a risky action.
    Uses LangGraph Command(resume=...) to continue from interrupt point.

    The approval response is passed directly to the interrupted node,
    which receives it as the return value of interrupt().
    """
    logger.info(
        "Approval for thread %s: approved=%s reason=%s",
        thread_id,
        response.approved,
        response.reason,
    )

    try:
        # Resume the graph with the approval response
        resume_value = {
            "approved": response.approved,
            "reason": response.reason,
            "modification": response.modification,
        }
        final_state = await resume_orchestration(thread_id, resume_value)

        # Check if graph hit another interrupt
        graph_state = await get_graph_state(thread_id)
        if graph_state and graph_state.next:
            interrupt_data = None
            if graph_state.tasks:
                for task in graph_state.tasks:
                    if hasattr(task, "interrupts") and task.interrupts:
                        interrupt_data = task.interrupts[0].value
                        break
            return {
                "status": "interrupted",
                "thread_id": thread_id,
                "interrupt": interrupt_data,
            }

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
        logger.exception("Approve/resume failed for thread %s", thread_id)
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
