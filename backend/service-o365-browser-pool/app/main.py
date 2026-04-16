"""O365 Browser Pool — FastAPI entry point.

Per-connection pod hosting a single LangGraph agent (`app/agent/graph.py`).
The agent drives every behavior through tools + a system prompt:
- navigation, login, MFA, retry-on-error, scraping, recording.

Infrastructure here is kept deliberately dumb:
- launch Chromium + restore profile (browser_manager)
- hold token interception hooks (token_extractor)
- dumb name→page map (TabRegistry)
- Mongo writes (scrape_storage)
- Kotlin server notify hooks (kotlin_callback)

No bootstrap loops, no retry logic, no tab-type mapping, no hard-coded
URLs. All of that belongs in the agent.
"""

from __future__ import annotations

import asyncio
import json as _json
import logging
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI

from app import agent_registry
from app.agent.persistence import init_checkpointer, shutdown_checkpointer
from app.agent.runner import PodAgent
from app.browser_manager import BrowserManager
from app.config import settings
from app.meeting_recorder import MeetingRecorder
from app.pod_state import PodState, get_or_create_state_manager
from app.routes.graph_proxy import create_graph_proxy_router
from app.routes.health import create_health_router
from app.routes.instruction import create_instruction_router
from app.routes.meeting import create_meeting_router
from app.routes.scrape import create_scrape_router
from app.routes.session import create_session_router
from app.routes.token import create_token_router
from app.routes.vnc_auth import create_vnc_auth_router
from app.scrape_storage import ScrapeStorage
from app.tab_manager import TabRegistry
from app.token_extractor import TokenExtractor
from app.vnc_auth import VncAuthManager
from app.vnc_proxy import create_vnc_proxy_router, create_vnc_static_middleware

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
)
logger = logging.getLogger("o365-browser-pool")

browser_manager = BrowserManager()
token_extractor = TokenExtractor()
vnc_auth_manager = VncAuthManager()
tab_registry = TabRegistry()
scrape_storage = ScrapeStorage()
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
    """Launch Chromium, attach TabRegistry, start the agent.

    Everything else (login, scraping, tab naming) is the agent's job.
    """
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
        if not context.pages:
            await context.new_page()
        for page in context.pages:
            if not page.is_closed():
                await token_extractor.setup_interception(client_id, page)

        tab_registry.attach_context(client_id, context)

        agent = PodAgent(
            client_id=client_id,
            connection_id=connection_id,
            browser_context=context,
            state_manager=state_manager,
            tab_registry=tab_registry,
            storage=scrape_storage,
            credentials=credentials,
            login_url=login_url,
            capabilities=capabilities,
            meeting_recorder=meeting_recorder,
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
    try:
        init_checkpointer()
    except Exception:
        logger.exception("LangGraph checkpointer init failed — agent will fail to start")

    if settings.connection_id:
        asyncio.create_task(_try_self_restore())

    yield

    for ag in agent_registry.all():
        try:
            await ag.stop()
        except Exception:
            logger.exception("Agent stop failed")
    await scrape_storage.stop()
    shutdown_checkpointer()
    await browser_manager.shutdown()
    from app.kotlin_callback import shutdown as callback_shutdown
    await callback_shutdown()
    logger.info("O365 Browser Pool stopped")


app = FastAPI(
    title="Jervis O365 Browser Pool",
    version="0.6.0",
    lifespan=lifespan,
)

app.include_router(create_health_router(browser_manager))
app.include_router(create_token_router(token_extractor))
app.include_router(
    create_session_router(
        browser_manager, token_extractor, tab_registry, scrape_storage,
        meeting_recorder,
    )
)
app.include_router(create_graph_proxy_router(browser_manager, token_extractor))
app.include_router(
    create_scrape_router(tab_registry, browser_manager, scrape_storage)
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
