import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

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
    logger.info("Knowledge Service ready (mode=%s)", settings.KB_MODE)
    yield


app = FastAPI(title="Knowledge Service", version="1.0.0", lifespan=lifespan)

# Conditionally include routers based on KB_MODE
if settings.KB_MODE in ("all", "read"):
    from app.api.routes import read_router
    app.include_router(read_router, prefix="/api/v1")

if settings.KB_MODE in ("all", "write"):
    from app.api.routes import write_router
    app.include_router(write_router, prefix="/api/v1")


@app.get("/health")
async def health():
    return {"status": "ok", "mode": settings.KB_MODE}

@app.get("/")
async def root():
    return {"status": "ok", "service": "knowledgebase-python", "mode": settings.KB_MODE}

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8080)
