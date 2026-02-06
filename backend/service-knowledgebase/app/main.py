import logging

from fastapi import FastAPI

from app.api.routes import router

# Configure root logger
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

logger = logging.getLogger(__name__)


class _HealthCheckAccessFilter(logging.Filter):
    """Drop GET / and GET /health from uvicorn access log."""

    def filter(self, record: logging.LogRecord) -> bool:
        msg = record.getMessage()
        if "GET / " in msg or "GET /health " in msg:
            return False
        return True


app = FastAPI(title="Knowledge Service", version="1.0.0")

app.include_router(router, prefix="/api/v1")


@app.on_event("startup")
async def _startup():
    # Apply filter here – uvicorn's access logger is guaranteed to exist at this point
    logging.getLogger("uvicorn.access").addFilter(_HealthCheckAccessFilter())
    logger.info("Knowledge Service ready")


@app.get("/health")
async def health():
    return {"status": "ok"}

@app.get("/")
async def root():
    return {"status": "ok", "service": "knowledgebase-python"}

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8080)
