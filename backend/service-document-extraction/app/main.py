"""Document Extraction Service — converts any file to plain text/structured description.

Single responsibility: take any binary content (PDF, DOCX, XLSX, HTML, images)
and return clean plain text that models/indexers can work with.

Used by:
- KB indexer (before RAG + graph extraction)
- Orchestrator (chat attachments → text inline)
- Email processor (HTML emails, attachments)
- Any service that needs text from non-text content
"""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, UploadFile, HTTPException
from fastapi.responses import JSONResponse

from app.config import settings
from app.services.extractor import DocumentExtractor

logging.basicConfig(level=settings.LOG_LEVEL, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.extractor = DocumentExtractor(ollama_router_url=settings.OLLAMA_ROUTER_URL)
    logger.info("Document Extraction Service ready (router=%s)", settings.OLLAMA_ROUTER_URL)
    yield


app = FastAPI(title="Document Extraction Service", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "ok", "service": "document-extraction"}


@app.post("/extract")
async def extract(
    file: UploadFile = File(...),
    filename: str = Form(None),
    mime_type: str = Form(None),
    max_tier: str = Form("NONE"),
):
    """Extract text from any file.

    Returns:
        {
            "text": "extracted plain text...",
            "method": "pymupdf|vlm|hybrid|direct|html|docx|xlsx",
            "metadata": {...},
            "pages": [{"page_number": 1, "text": "..."}]  // for PDFs
        }
    """
    actual_filename = filename or file.filename or "unknown"
    actual_mime = mime_type or file.content_type or ""

    file_bytes = await file.read()
    if not file_bytes:
        raise HTTPException(status_code=400, detail="Empty file")

    logger.info("EXTRACT: file=%s mime=%s size=%d tier=%s", actual_filename, actual_mime, len(file_bytes), max_tier)

    try:
        result = await app.state.extractor.extract(file_bytes, actual_filename, actual_mime, max_tier)
        return {
            "text": result.text,
            "method": result.method,
            "metadata": result.metadata,
            "pages": [{"page_number": p.page_number, "text": p.text} for p in result.pages] if result.pages else [],
        }
    except Exception as e:
        logger.error("EXTRACT_FAILED: file=%s error=%s", actual_filename, e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Extraction failed: {e}")


@app.post("/extract-base64")
async def extract_base64(
    content_base64: str = Form(...),
    filename: str = Form("unknown"),
    mime_type: str = Form(""),
    max_tier: str = Form("NONE"),
):
    """Extract text from base64-encoded file content.

    Used by services that already have content in base64 (chat attachments, emails).
    """
    import base64

    try:
        file_bytes = base64.b64decode(content_base64)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid base64 content")

    if not file_bytes:
        raise HTTPException(status_code=400, detail="Empty content")

    logger.info("EXTRACT_B64: file=%s mime=%s size=%d tier=%s", filename, mime_type, len(file_bytes), max_tier)

    try:
        result = await app.state.extractor.extract(file_bytes, filename, mime_type, max_tier)
        return {
            "text": result.text,
            "method": result.method,
            "metadata": result.metadata,
            "pages": [{"page_number": p.page_number, "text": p.text} for p in result.pages] if result.pages else [],
        }
    except Exception as e:
        logger.error("EXTRACT_B64_FAILED: file=%s error=%s", filename, e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Extraction failed: {e}")
