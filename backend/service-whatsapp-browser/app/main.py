"""WhatsApp Browser – FastAPI entry point.

Manages a Playwright browser instance for WhatsApp Web integration.
Data extraction via VLM screen scraping (screenshots + Qwen3-VL).

Port 8091 serves ALL traffic:
- Internal API (session, health, scrape) — called by Kotlin server
- VNC auth (vnc-login, vnc-token) — token generation and login
- noVNC static files + WebSocket proxy — served with cookie auth
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.browser_manager import BrowserManager
from app.config import settings
from app.routes.health import create_health_router
from app.routes.scrape import create_scrape_router
from app.routes.session import create_session_router
from app.routes.vnc_auth import create_vnc_auth_router
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import WhatsAppScraper
from app.session_monitor import SessionMonitor
from app.vnc_auth import VncAuthManager
from app.vnc_proxy import create_vnc_proxy_router, create_vnc_static_middleware

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
)
logger = logging.getLogger("whatsapp-browser")

browser_manager = BrowserManager()
vnc_auth_manager = VncAuthManager()
scrape_storage = ScrapeStorage()
scraper = WhatsAppScraper(browser_manager, scrape_storage)
session_monitor = SessionMonitor(browser_manager, scraper)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting WhatsApp Browser on %s:%d", settings.host, settings.port)
    await browser_manager.startup()
    await scrape_storage.start()
    await session_monitor.start()
    await scraper.start()
    yield
    await scraper.stop()
    await session_monitor.stop()
    await scrape_storage.stop()
    await browser_manager.shutdown()
    from app.kotlin_callback import shutdown as callback_shutdown
    await callback_shutdown()
    logger.info("WhatsApp Browser stopped")


app = FastAPI(
    title="Jervis WhatsApp Browser",
    version="0.1.0",
    lifespan=lifespan,
)

# API routers
app.include_router(create_health_router(browser_manager))
app.include_router(
    create_session_router(browser_manager, scraper, scrape_storage)
)
app.include_router(
    create_scrape_router(scraper, browser_manager, scrape_storage)
)

# VNC auth endpoints
app.include_router(create_vnc_auth_router(vnc_auth_manager))

# VNC WebSocket proxy
app.include_router(create_vnc_proxy_router(vnc_auth_manager))

# Middleware: serve noVNC static files with cookie auth
app.middleware("http")(create_vnc_static_middleware(vnc_auth_manager))


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
