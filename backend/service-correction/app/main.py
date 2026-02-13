"""
Transcript Correction Service.

Standalone microservice for correcting Whisper transcripts using:
- KB-stored correction rules
- Ollama LLM for semantic correction
- Interactive question generation for unknown terms

This service was separated from the orchestrator to:
- Keep orchestrator focused on task decomposition and coding workflows
- Allow independent scaling and deployment
- Enable parallel development by separate teams
"""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from app.agent import correction_agent


class HealthCheckFilter(logging.Filter):
    """Filter out healthcheck endpoint logs."""

    def filter(self, record: logging.LogRecord) -> bool:
        return "GET /health" not in record.getMessage()


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

# Filter out healthcheck logs from uvicorn access logger
logging.getLogger("uvicorn.access").addFilter(HealthCheckFilter())


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifecycle manager."""
    logger.info("Correction service starting up")
    yield
    logger.info("Correction service shutting down")


app = FastAPI(
    title="Jervis Correction Service",
    description="Transcript correction agent for meeting transcripts",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health():
    """Health check endpoint."""
    return {"status": "ok", "service": "correction"}


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
            meeting_id=request.get("meetingId"),
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


@app.post("/correction/instruct")
async def correct_with_instruction(request: dict):
    """Re-correct transcript based on user's natural language instruction.

    The user describes what needs to be corrected and the agent applies it
    across the entire transcript, also extracting reusable rules for KB.
    """
    try:
        result = await correction_agent.correct_with_instruction(
            client_id=request["clientId"],
            project_id=request.get("projectId"),
            segments=request["segments"],
            instruction=request["instruction"],
        )
        return result
    except Exception as e:
        logger.exception("Failed instruction-based correction")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/correction/correct-targeted")
async def correct_targeted(request: dict):
    """Targeted correction for retranscribed segments.

    User corrections are applied directly, retranscribed segments go through
    the correction agent. Untouched segments pass through as-is.
    """
    try:
        result = await correction_agent.correct_targeted(
            client_id=request["clientId"],
            project_id=request.get("projectId"),
            segments=request["segments"],
            retranscribed_indices=request.get("retranscribedIndices", []),
            user_corrected_indices=request.get("userCorrectedIndices", {}),
            meeting_id=request.get("meetingId"),
        )
        return result
    except Exception as e:
        logger.exception("Failed targeted correction")
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
