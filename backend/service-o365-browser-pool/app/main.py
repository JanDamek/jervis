"""O365 Browser Pool – FastAPI entry point.

Autonomous AI-driven browser pod. Manages its own lifecycle:
- Self-restores session from PVC cookies on restart
- AI login flow (VLM sees screen, LLM decides, Playwright executes)
- Session health monitoring (60s check, auto-recovery)
- Chat monitoring (new message detection, priority notifications)
- Accepts instructions from JERVIS (change password, navigate, etc.)

Port 8090 serves ALL traffic — HTTP server starts immediately (never blocked by login).
"""

from __future__ import annotations

import asyncio
import json as _json
import logging
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI

from app.browser_manager import BrowserManager
from app.config import settings
from app.health_loop import ChatMonitor, HealthLoop
from app.pod_state import PodState, PodStateManager
from app.routes.graph_proxy import create_graph_proxy_router
from app.routes.health import create_health_router
from app.routes.instruction import create_instruction_router
from app.routes.scrape import create_scrape_router
from app.routes.session import create_session_router
from app.routes.token import create_token_router
from app.routes.vnc_auth import create_vnc_auth_router
from app.routes.meeting import create_meeting_router
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import ScreenScraper
from app.teams_crawler import TeamsCrawler
from app.session_monitor import SessionMonitor
from app.tab_manager import TabManager
from app.token_extractor import TokenExtractor
from app.vnc_auth import VncAuthManager
from app.meeting_recorder import MeetingRecorder
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

# State manager — created during self-restore, shared across components
_state_manager: PodStateManager | None = None
_health_loop: HealthLoop | None = None
_chat_monitor: ChatMonitor | None = None


async def _try_self_restore():
    """Check PVC for saved init config and auto-restore session.

    Runs as background task — HTTP server is already accepting requests.
    Uses AI login flow (VLM + LLM) instead of hardcoded selectors.
    """
    global _state_manager, _health_loop, _chat_monitor

    config_path = Path(settings.profiles_dir) / "init-config.json"
    if not config_path.exists():
        logger.info("No init-config.json found — waiting for server init call")
        return

    config = _json.loads(config_path.read_text())
    client_id = config.get("client_id", settings.connection_id)
    connection_id = config.get("connection_id", client_id)
    login_url = config.get("login_url", "https://teams.microsoft.com")
    capabilities = config.get("capabilities", [])
    username = config.get("username")
    password = config.get("password")
    credentials = None
    if username:
        credentials = {"email": username, "password": password or ""}

    logger.info("Self-restore: client=%s, user=%s", client_id, username)

    # Create state manager
    _state_manager = PodStateManager(client_id, connection_id)

    # Register with instruction router
    if hasattr(instruction_router, "register_state_manager"):
        instruction_router.register_state_manager(client_id, _state_manager)

    # Wait for Kotlin server to be reachable (MFA notifications need it)
    server_url = settings.kotlin_server_url
    if server_url:
        for attempt in range(12):  # 60s max wait
            try:
                async with httpx.AsyncClient(timeout=5) as client:
                    resp = await client.get(server_url)
                    if resp.status_code == 200:
                        logger.info("Self-restore: Kotlin server reachable")
                        break
            except Exception:
                pass
            await asyncio.sleep(5)

    # Create browser context (persistent — tabs survive restart)
    try:
        context = await browser_manager.get_or_create_context(client_id)
        logger.info("Browser context ready for client %s", client_id)

        # Persistent tabs — don't create new ones if they already exist
        existing_pages = [p for p in context.pages if not p.is_closed()]
        if not existing_pages:
            # First start — create initial page
            page = await context.new_page()
        else:
            page = existing_pages[0]
            logger.info("Reusing %d existing tabs for %s", len(existing_pages), client_id)

        # Setup token interception on all pages
        for p in context.pages:
            if not p.is_closed():
                await token_extractor.setup_interception(client_id, p)

        # Check if already logged in (persistent session from PVC cookies)
        # Wait briefly for page to settle (redirect from about:blank)
        await asyncio.sleep(3)
        url = page.url or ""
        logger.info("Self-restore: page URL = %s", url[:100])

        # Check URL — if on Teams/Outlook domain, session is likely valid
        is_on_app = any(d in url for d in [
            "teams.cloud.microsoft", "teams.microsoft.com",
            "outlook.office.com", "outlook.live.com",
            "teams.live.com",
        ])
        # Also check if on MCAS-wrapped Teams
        if "mcas.ms" in url and "login" not in url:
            is_on_app = True

        if is_on_app:
            logger.info("Self-restore: session active at %s", url[:80])
            await _state_manager.transition(PodState.ACTIVE, reason="Restored from PVC cookies")
        elif "login" in url:
            # On login page — need to authenticate
            from app.ai_login import ai_login
            await ai_login(page, _state_manager, credentials=credentials, login_url=login_url)
        else:
            # Unknown page (about:blank, etc.) — navigate to Teams and check
            logger.info("Self-restore: unknown page %s — navigating to Teams", url[:60])
            try:
                await page.goto(login_url, wait_until="domcontentloaded", timeout=30000)
                await asyncio.sleep(5)
            except Exception:
                pass
            url = page.url or ""
            is_on_app = any(d in url for d in ["teams.cloud.microsoft", "teams.microsoft.com"])
            if is_on_app or ("mcas.ms" in url and "login" not in url):
                await _state_manager.transition(PodState.ACTIVE, reason="Navigated to Teams, session valid")
            else:
                from app.ai_login import ai_login
                await ai_login(page, _state_manager, credentials=credentials, login_url=login_url)

        # Start health loop and chat monitor (regardless of login result)
        _health_loop = HealthLoop(browser_manager, _state_manager, credentials=credentials)
        await _health_loop.start()

        _chat_monitor = ChatMonitor(browser_manager, _state_manager)
        await _chat_monitor.start()

        # Setup tabs for scraping (only open missing tabs, don't touch existing)
        if _state_manager.state == PodState.ACTIVE:
            await tab_manager.setup_tabs(client_id, context, capabilities)
            # Notify server about available capabilities
            available = tab_manager.get_available_tabs(client_id)
            if available:
                from app.kotlin_callback import notify_capabilities_discovered
                cap_names = [t.value.upper() + "_READ" for t in available]
                await notify_capabilities_discovered(client_id, connection_id, cap_names)

        # Start legacy session monitor (token-based check)
        await session_monitor.start()

        logger.info("Self-restore completed for client %s (state=%s)", client_id, _state_manager.state)

    except Exception as e:
        logger.error("Self-restore failed: %s", e, exc_info=True)
        if _state_manager:
            await _state_manager.transition(PodState.ERROR, reason=f"Self-restore failed: {e}")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(
        "Starting O365 Browser Pool on %s:%d (connection=%s)",
        settings.host, settings.port, settings.connection_id,
    )
    await browser_manager.startup()
    await scrape_storage.start()
    await screen_scraper.start()

    # Self-restore runs as background task — HTTP server available immediately
    if settings.connection_id:
        asyncio.create_task(_try_self_restore())

    yield

    # Shutdown
    if _chat_monitor:
        await _chat_monitor.stop()
    if _health_loop:
        await _health_loop.stop()
    await screen_scraper.stop()
    await session_monitor.stop()
    await scrape_storage.stop()
    await browser_manager.shutdown()
    from app.kotlin_callback import shutdown as callback_shutdown
    await callback_shutdown()
    logger.info("O365 Browser Pool stopped")


app = FastAPI(
    title="Jervis O365 Browser Pool",
    version="0.3.0",
    lifespan=lifespan,
)

# API routers
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

# Instruction API — JERVIS sends commands, pod executes via VLM+LLM
instruction_router = create_instruction_router(browser_manager)
app.include_router(instruction_router)

# VNC auth + proxy
app.include_router(create_vnc_auth_router(vnc_auth_manager))
app.include_router(create_vnc_proxy_router(vnc_auth_manager))
app.middleware("http")(create_vnc_static_middleware(vnc_auth_manager))


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
    )
