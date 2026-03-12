"""O365 Browser Pool – FastAPI entry point.

Manages Playwright browser instances for extracting O365 Bearer tokens.
One browser context per client, persistent profiles on disk.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.browser_manager import BrowserManager
from app.config import settings
from app.routes.health import create_health_router
from app.routes.session import create_session_router
from app.routes.token import create_token_router
from app.session_monitor import SessionMonitor
from app.token_extractor import TokenExtractor

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
)
logger = logging.getLogger("o365-browser-pool")

browser_manager = BrowserManager()
token_extractor = TokenExtractor()
session_monitor = SessionMonitor(browser_manager, token_extractor)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting O365 Browser Pool on %s:%d", settings.host, settings.port)
    await browser_manager.startup()
    await session_monitor.start()
    yield
    await session_monitor.stop()
    await browser_manager.shutdown()
    logger.info("O365 Browser Pool stopped")


app = FastAPI(
    title="Jervis O365 Browser Pool",
    version="0.1.0",
    lifespan=lifespan,
)

app.include_router(create_health_router(browser_manager))
app.include_router(create_token_router(token_extractor))
app.include_router(create_session_router(browser_manager, token_extractor))


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
