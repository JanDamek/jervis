"""FastAPI application – Python Orchestrator Service.

Accepts requests from Kotlin server, runs LangGraph orchestration,
streams progress via SSE.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from app.config import settings
from app.graph.orchestrator import run_orchestration
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


@app.post("/orchestrate", response_model=OrchestrateResponse)
async def orchestrate(request: OrchestrateRequest):
    """Start orchestration workflow.

    Called by Kotlin server when a user sends a coding task.
    The orchestrator:
    1. Decomposes the query into goals
    2. Plans steps for each goal
    3. Executes steps via K8s Jobs (coding agents)
    4. Evaluates results
    5. Handles git operations (commit/push) via delegation to agents
    6. Returns final result
    """
    try:
        final_state = await run_orchestration(request)

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
        )
    except Exception as e:
        logger.exception("Orchestration failed for task %s", request.task_id)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/approve/{thread_id}")
async def approve(thread_id: str, response: ApprovalResponse):
    """Handle approval response from user.

    Called by Kotlin server when user approves/rejects a risky action.
    This will resume the LangGraph execution from the interrupt point.
    """
    # TODO: Wire to LangGraph checkpointer resume with approval response
    logger.info(
        "Approval for thread %s: approved=%s",
        thread_id,
        response.approved,
    )
    return {"status": "acknowledged", "thread_id": thread_id}


@app.post("/resume/{thread_id}")
async def resume(thread_id: str):
    """Resume a paused orchestration.

    Called after approval or when continuing from a checkpoint.
    """
    # TODO: Wire to LangGraph checkpointer resume
    logger.info("Resume requested for thread %s", thread_id)
    return {"status": "not_implemented", "thread_id": thread_id}


# --- Entry point ---

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
