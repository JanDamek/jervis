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

All correction RPCs are served over gRPC on port :5501 (see app.grpc_server).
The FastAPI app exists only to expose `/health` for K8s probes.
"""

import asyncio
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI


class HealthCheckFilter(logging.Filter):
    """Filter out healthcheck endpoint logs."""

    def filter(self, record: logging.LogRecord) -> bool:
        return "GET /health" not in record.getMessage()


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

logging.getLogger("uvicorn.access").addFilter(HealthCheckFilter())


@asynccontextmanager
async def lifespan(app: FastAPI):
    from app.grpc_server import start_grpc_server

    logger.info("Correction service starting up")
    grpc_port = int(os.getenv("CORRECTION_GRPC_PORT", "5501"))
    grpc_server = await start_grpc_server(port=grpc_port)
    app.state.grpc_server = grpc_server
    try:
        yield
    finally:
        try:
            await asyncio.wait_for(grpc_server.stop(grace=5.0), timeout=10.0)
        except Exception as e:
            logger.warning("gRPC shutdown failed: %s", e)
        logger.info("Correction service shutting down")


app = FastAPI(
    title="Jervis Correction Service",
    description="Transcript correction agent for meeting transcripts",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health():
    """Health check endpoint for K8s probes."""
    return {"status": "ok", "service": "correction"}
