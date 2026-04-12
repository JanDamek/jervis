"""O365 Browser Pool – FastAPI entry point.

Manages Playwright browser instances for O365 integration.
Two modes of data extraction:
1. Graph API proxy — uses captured Bearer tokens (for standard OAuth2)
2. VLM screen scraping — screenshots + vision model (for Conditional Access)

Port 8090 serves ALL traffic:
- Internal API (session, token, health, scrape) — called by Kotlin server
- Graph API proxy — called by O365 Gateway
- VNC auth (vnc-login, vnc-token) — token generation and login
- noVNC static files + WebSocket proxy — served with cookie auth
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.browser_manager import BrowserManager
from app.config import settings
from app.routes.graph_proxy import create_graph_proxy_router
from app.routes.health import create_health_router
from app.routes.scrape import create_scrape_router
from app.routes.session import create_session_router
from app.routes.token import create_token_router
from app.routes.vnc_auth import create_vnc_auth_router
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import ScreenScraper
from app.teams_crawler import TeamsCrawler
from app.session_monitor import SessionMonitor
from app.tab_manager import TabManager
from app.token_extractor import TokenExtractor
from app.vnc_auth import VncAuthManager
from app.meeting_recorder import MeetingRecorder
from app.routes.meeting import create_meeting_router
from app.vnc_proxy import create_vnc_proxy_router, create_vnc_static_middleware

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
)
logger = logging.getLogger("o365-browser-pool")

browser_manager = BrowserManager()
token_extractor = TokenExtractor()
vnc_auth_manager = VncAuthManager()
tab_manager = TabManager(token_extractor)
scrape_storage = ScrapeStorage()
screen_scraper = ScreenScraper(browser_manager, tab_manager, scrape_storage)
teams_crawler = TeamsCrawler()
meeting_recorder = MeetingRecorder(browser_manager)
session_monitor = SessionMonitor(browser_manager, token_extractor)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting O365 Browser Pool on %s:%d", settings.host, settings.port)
    await browser_manager.startup()
    await scrape_storage.start()
    await session_monitor.start()
    await screen_scraper.start()
    yield
    await screen_scraper.stop()
    await session_monitor.stop()
    await scrape_storage.stop()
    await browser_manager.shutdown()
    from app.kotlin_callback import shutdown as callback_shutdown
    await callback_shutdown()
    logger.info("O365 Browser Pool stopped")


app = FastAPI(
    title="Jervis O365 Browser Pool",
    version="0.2.0",
    lifespan=lifespan,
)

# API routers (no VNC auth needed — called via K8s service)
app.include_router(create_health_router(browser_manager))
app.include_router(create_token_router(token_extractor))
app.include_router(
    create_session_router(
        browser_manager, token_extractor, tab_manager, screen_scraper,
        teams_crawler, scrape_storage,
    )
)
app.include_router(create_graph_proxy_router(browser_manager, token_extractor))
app.include_router(
    create_scrape_router(screen_scraper, tab_manager, teams_crawler,
                         browser_manager, scrape_storage)
)
app.include_router(create_meeting_router(meeting_recorder))

# VNC auth endpoints (vnc-login, vnc-token)
app.include_router(create_vnc_auth_router(vnc_auth_manager))

# VNC WebSocket proxy (with cookie auth)
app.include_router(create_vnc_proxy_router(vnc_auth_manager))

# Middleware: serve noVNC static files with cookie auth
# Must be added AFTER routers so routers take priority
app.middleware("http")(create_vnc_static_middleware(vnc_auth_manager))


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
