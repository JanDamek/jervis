import asyncio
import logging
from contextlib import asynccontextmanager
from typing import AsyncGenerator

from fastapi import Depends, FastAPI

from app.core.config import settings

# Configure root logger
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

# Suppress noisy httpx INFO logs (every HTTP request to Weaviate/Ollama/etc.)
logging.getLogger("httpx").setLevel(logging.WARNING)

logger = logging.getLogger(__name__)


class _HealthCheckAccessFilter(logging.Filter):
    """Drop GET / and GET /health from uvicorn access log."""

    def filter(self, record: logging.LogRecord) -> bool:
        msg = record.getMessage()
        if "GET / " in msg or "GET /health " in msg:
            return False
        return True


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: apply filter when uvicorn's access logger is guaranteed to exist
    logging.getLogger("uvicorn.access").addFilter(_HealthCheckAccessFilter())

    # Initialize services
    from pathlib import Path
    from app.services.knowledge_service import KnowledgeService
    from app.services.graph_service import GraphService
    from app.api import routes
    from app.services.rag_service import RagService

    # Create base services
    rag_service = RagService()
    graph_service = GraphService()

    # Initialize extraction queue and worker ONLY for write mode
    extraction_queue = None
    worker = None
    if settings.KB_MODE in ("all", "write"):
        from app.services.llm_extraction_queue import LLMExtractionQueue
        from app.services.llm_extraction_worker import LLMExtractionWorker

        queue_file = Path("/opt/jervis/data/extraction-queue.json")
        extraction_queue = LLMExtractionQueue(queue_file)
        worker = LLMExtractionWorker(extraction_queue, graph_service, rag_service)
        await worker.start()
        logger.info("LLM extraction worker started with queue at %s", queue_file)

    knowledge_service = KnowledgeService(extraction_queue=extraction_queue)

    # Initialize global service in routes module
    routes.service = knowledge_service

    # Store in app state for access
    app.state.extraction_queue = extraction_queue
    app.state.knowledge_service = knowledge_service
    app.state.extraction_worker = worker

    logger.info("Knowledge Service ready (mode=%s, read_limit=%d, write_limit=%d)",
                settings.KB_MODE, settings.MAX_CONCURRENT_READS, settings.MAX_CONCURRENT_WRITES)

    yield

    # Shutdown: stop worker gracefully (only if it was started)
    if worker is not None:
        await worker.stop()
        logger.info("LLM extraction worker stopped")


# Concurrency limiters (prioritize reads over writes)
_read_semaphore = asyncio.Semaphore(settings.MAX_CONCURRENT_READS)
_write_semaphore = asyncio.Semaphore(settings.MAX_CONCURRENT_WRITES)


async def acquire_read_slot() -> AsyncGenerator[None, None]:
    """Dependency: acquire read concurrency slot (up to 40 concurrent)."""
    async with _read_semaphore:
        yield


async def acquire_write_slot() -> AsyncGenerator[None, None]:
    """Dependency: acquire write concurrency slot (up to 10 concurrent, queue others)."""
    async with _write_semaphore:
        yield


app = FastAPI(title="Knowledge Service", version="1.0.0", lifespan=lifespan)

# Conditionally include routers based on KB_MODE
if settings.KB_MODE in ("all", "read"):
    from app.api.routes import read_router
    app.include_router(
        read_router,
        prefix="/api/v1",
        dependencies=[Depends(acquire_read_slot)],
    )

if settings.KB_MODE in ("all", "write"):
    from app.api.routes import write_router
    app.include_router(
        write_router,
        prefix="/api/v1",
        dependencies=[Depends(acquire_write_slot)],
    )


@app.get("/health")
async def health():
    return {"status": "ok", "mode": settings.KB_MODE}

@app.get("/")
async def root():
    return {"status": "ok", "service": "knowledgebase-python", "mode": settings.KB_MODE}

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8080)
