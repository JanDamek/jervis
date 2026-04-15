"""O365 Browser Pool – FastAPI entry point.

Pod is driven by a single PodAgent (see app/agent/pod_agent.py) that runs the
entire lifecycle: login, MFA, monitoring, periodic scraping, instructions,
recovery. No separate health/chat/login loops.

Port 8090 serves ALL traffic — HTTP server starts immediately (never blocked
by login).
"""

from __future__ import annotations

import asyncio
import json as _json
import logging
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI

from app.agent.pod_agent import PodAgent
from app.browser_manager import BrowserManager
from app.config import settings
from app.pod_state import PodState, get_or_create_state_manager
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
from app.tab_manager import TabManager
from app.token_extractor import TokenExtractor
from app.vnc_auth import VncAuthManager
from app.meeting_recorder import MeetingRecorder
from app.vnc_proxy import create_vnc_proxy_router, create_vnc_static_middleware
from app import agent_registry

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


async def _wait_for_kotlin_server() -> None:
    server_url = settings.kotlin_server_url
    if not server_url:
        return
    for _ in range(12):
        try:
            async with httpx.AsyncClient(timeout=5) as client:
                resp = await client.get(server_url)
                if resp.status_code == 200:
                    logger.info("Kotlin server reachable")
                    return
        except Exception:
            pass
        await asyncio.sleep(5)


async def _try_self_restore() -> None:
    """Reload init config from PVC and start the agent. Runs in background;
    the HTTP server already accepts requests."""
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
    credentials = {"email": username, "password": password or ""} if username else None

    logger.info("Self-restore: client=%s, user=%s", client_id, username)

    state_manager = get_or_create_state_manager(client_id, connection_id)
    await _wait_for_kotlin_server()

    try:
        context = await browser_manager.get_or_create_context(client_id)

        # Persistent tabs — never close existing pages
        existing_pages = [p for p in context.pages if not p.is_closed()]
        if not existing_pages:
            await context.new_page()
        for p in context.pages:
            if not p.is_closed():
                await token_extractor.setup_interception(client_id, p)

        agent = PodAgent(
            client_id=client_id,
            connection_id=connection_id,
            browser_context=context,
            state_manager=state_manager,
            credentials=credentials,
            login_url=login_url,
            capabilities=capabilities,
            scraper=screen_scraper,
            tab_manager=tab_manager,
        )
        agent_registry.register(client_id, agent)
        await agent.start()
        logger.info("Self-restore: PodAgent started for %s", client_id)
    except Exception as e:
        logger.exception("Self-restore failed: %s", e)
        await state_manager.transition(PodState.ERROR, reason=f"Self-restore failed: {e}")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(
        "Starting O365 Browser Pool on %s:%d (connection=%s)",
        settings.host, settings.port, settings.connection_id,
    )
    await browser_manager.startup()
    await scrape_storage.start()
    await screen_scraper.start()

    if settings.connection_id:
        asyncio.create_task(_try_self_restore())

    yield

    # Shutdown
    for ag in agent_registry.all():
        try:
            await ag.stop()
        except Exception:
            logger.exception("Agent stop failed")
    await screen_scraper.stop()
    await scrape_storage.stop()
    await browser_manager.shutdown()
    from app.kotlin_callback import shutdown as callback_shutdown
    await callback_shutdown()
    logger.info("O365 Browser Pool stopped")


app = FastAPI(
    title="Jervis O365 Browser Pool",
    version="0.4.0",
    lifespan=lifespan,
)

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
app.include_router(create_instruction_router(browser_manager))

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
