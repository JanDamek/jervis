"""FastAPI application – Python Orchestrator Service.

Accepts requests from Kotlin server, runs LangGraph orchestration,
streams progress via SSE, handles approval flow via LangGraph interrupt().

Communication architecture:
- Kotlin → Python: POST /orchestrate/stream (fire-and-forget, returns thread_id)
- Python → UI: SSE /stream/{thread_id} (progress + approval requests)
- Kotlin polls: GET /status/{thread_id} (running/interrupted/done/error)
- Kotlin → Python: POST /approve/{thread_id} (fire-and-forget, resumes graph)
- No Python → Kotlin callbacks needed (full request model)

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
    get_graph_state,
    init_checkpointer,
    close_checkpointer,
)
from app.models import (
    ApprovalResponse,
    OrchestrateRequest,
    OrchestrateResponse,
    StepResult,
)
from app.tools.kotlin_client import kotlin_client
from app.whisper.correction_agent import correction_agent

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger(__name__)


class _HealthCheckAccessFilter(logging.Filter):
    """Drop GET /health from uvicorn access log."""

    def filter(self, record: logging.LogRecord) -> bool:
        msg = record.getMessage()
        if "GET /health " in msg:
            return False
        return True

# In-memory store for active SSE streams
_active_streams: dict[str, asyncio.Queue] = {}

# Concurrency control: only one orchestration at a time
# LLM (Ollama/Anthropic) can't handle multiple concurrent requests efficiently
_orchestration_semaphore = asyncio.Semaphore(1)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup and shutdown events."""
    logger.info("Orchestrator starting on port %d", settings.port)
    logging.getLogger("uvicorn.access").addFilter(_HealthCheckAccessFilter())
    # Initialize persistent MongoDB checkpointer for LangGraph state
    await init_checkpointer()
    logger.info("MongoDB checkpointer ready (state survives restarts)")
    yield
    # Cleanup
    await close_checkpointer()
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
        else:
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
    queue: asyncio.Queue = asyncio.Queue()
    _active_streams[thread_id] = queue

    # Run orchestration in background with semaphore, pushing events to queue
    asyncio.create_task(_run_and_stream(request, thread_id, queue))

    return {"thread_id": thread_id, "stream_url": f"/stream/{thread_id}"}


async def _run_and_stream(
    request: OrchestrateRequest,
    thread_id: str,
    queue: asyncio.Queue,
):
    """Background task: run orchestration and push events to SSE queue.

    Acquires the orchestration semaphore to ensure only one runs at a time.
    """
    try:
        async with _orchestration_semaphore:
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

    # Fire-and-forget: resume in background, result polled via GET /status
    asyncio.create_task(_resume_in_background(thread_id, resume_value))

    return {"status": "resuming", "thread_id": thread_id}


async def _resume_in_background(thread_id: str, resume_value: dict):
    """Background task: resume orchestration from interrupt with semaphore."""
    try:
        async with _orchestration_semaphore:
            await resume_orchestration(thread_id, resume_value)
            logger.info("Resume completed for thread %s", thread_id)
    except Exception as e:
        logger.exception("Background resume failed for thread %s: %s", thread_id, e)


# --- Whisper Transcript Correction Agent ---


@app.post("/correction/submit")
async def submit_correction(request: dict):
    """Store a transcript correction rule in KB.

    The correction is stored as a regular KB chunk with kind="transcript_correction",
    so both the orchestrator and any agent with KB access can retrieve it.
    """
    try:
        result = await correction_agent.submit_correction(
            client_id=request["clientId"],
            project_id=request.get("projectId"),
            original=request["original"],
            corrected=request["corrected"],
            category=request.get("category", "general"),
            context=request.get("context"),
        )
        return result
    except Exception as e:
        logger.exception("Failed to submit correction")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/correction/correct")
async def correct_transcript(request: dict):
    """Correct transcript segments using KB-stored corrections + Ollama GPU.

    Returns best-effort corrections + questions when uncertain.
    Response: {segments: [...], questions: [...], status: "success"|"needs_input"}
    """
    try:
        segments = request["segments"]
        result = await correction_agent.correct_transcript(
            client_id=request["clientId"],
            project_id=request.get("projectId"),
            segments=segments,
            chunk_size=request.get("chunkSize", 20),
        )
        return result
    except Exception as e:
        logger.exception("Failed to correct transcript")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/correction/list")
async def list_corrections(request: dict):
    """List all stored corrections for a client/project."""
    try:
        corrections = await correction_agent.list_corrections(
            client_id=request["clientId"],
            project_id=request.get("projectId"),
            max_results=request.get("maxResults", 100),
        )
        return {"corrections": corrections}
    except Exception as e:
        logger.exception("Failed to list corrections")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/correction/delete")
async def delete_correction(request: dict):
    """Delete a correction rule from KB."""
    try:
        result = await correction_agent.delete_correction(request["sourceUrn"])
        return result
    except Exception as e:
        logger.exception("Failed to delete correction")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/correction/answer")
async def answer_correction_questions(request: dict):
    """Store user answers as correction rules in KB.

    Called when user answers questions from the correction agent.
    Each answer is saved as a correction rule for future use.
    """
    try:
        results = await correction_agent.apply_answers_as_corrections(
            client_id=request["clientId"],
            project_id=request.get("projectId"),
            answers=request.get("answers", []),
        )
        return {"status": "success", "rulesCreated": len(results)}
    except Exception as e:
        logger.exception("Failed to store correction answers")
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
