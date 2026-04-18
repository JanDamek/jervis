"""Document Extraction Service — converts any file to plain text/structured description.

Single responsibility: take any binary content (PDF, DOCX, XLSX, HTML, images)
and return clean plain text that models/indexers can work with.

Used by:
- KB indexer (before RAG + graph extraction)
- Orchestrator (chat attachments → text inline)
- Email processor (HTML emails, attachments)
- Any service that needs text from non-text content

Transport: gRPC on :5501 (DocumentExtractionService.Extract). The
FastAPI app only hosts the lifespan hook that owns the extractor +
gRPC server; there are no HTTP routes.
"""

import asyncio
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import settings
from app.grpc_server import start_grpc_server
from app.services.extractor import DocumentExtractor

logging.basicConfig(level=settings.LOG_LEVEL, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    extractor = DocumentExtractor(ollama_router_url=settings.OLLAMA_ROUTER_URL)
    app.state.extractor = extractor
    logger.info("Document Extraction Service ready (router=%s)", settings.OLLAMA_ROUTER_URL)

    grpc_port = int(os.getenv("DOCEXT_GRPC_PORT", "5501"))
    grpc_server = await start_grpc_server(extractor, port=grpc_port)
    app.state.grpc_server = grpc_server
    try:
        yield
    finally:
        try:
            await asyncio.wait_for(grpc_server.stop(grace=5.0), timeout=10.0)
        except Exception as e:
            logger.warning("gRPC shutdown failed: %s", e)


app = FastAPI(title="Document Extraction Service", lifespan=lifespan)


@app.get("/health")
async def health() -> dict:
    """K8s probe endpoint. Actual traffic runs on gRPC :5501."""
    return {"status": "ok", "service": "document-extraction"}
