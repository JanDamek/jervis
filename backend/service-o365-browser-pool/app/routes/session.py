"""Session endpoints — driven by PodAgent.

Init starts (or returns existing) PodAgent. MFA submit fills the code via the
agent. Status reads from PodStateManager.
"""

from __future__ import annotations

import json as _json
import logging
from datetime import datetime, timezone
from pathlib import Path

from fastapi import APIRouter, HTTPException

from app import agent_registry
from app.agent.pod_agent import PodAgent
from app.browser_manager import BrowserManager
from app.config import settings
from app.models import (
    SessionInitRequest,
    SessionInitResponse,
    SessionStatus,
)
from app.pod_state import PodState, get_or_create_state_manager
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import ScreenScraper
from app.tab_manager import TabManager
from app.teams_crawler import TeamsCrawler
from app.token_extractor import TokenExtractor

logger = logging.getLogger("o365-browser-pool")

router = APIRouter(tags=["session"])


def _save_init_config(
    client_id: str,
    connection_id: str,
    login_url: str,
    capabilities: list[str],
    username: str | None,
    password: str | None,
) -> None:
    config_path = Path(settings.profiles_dir) / "init-config.json"
    config_path.write_text(_json.dumps({
        "client_id": client_id,
        "connection_id": connection_id,
        "login_url": login_url,
        "capabilities": capabilities,
        "username": username,
        "password": password,
    }))
    logger.info("Saved init config to %s", config_path)


def _state_to_message(sm) -> str:
    state = sm.state
    if state == PodState.ACTIVE:
        return "Přihlášení úspěšné"
    if state == PodState.AWAITING_MFA:
        msg = sm._mfa_message or "Vyžadováno dvoufaktorové ověření"
        if sm._mfa_number:
            msg = f"Potvrďte přihlášení v Microsoft Authenticator. Zadejte číslo: {sm._mfa_number}"
        return msg
    if state == PodState.AUTHENTICATING:
        return "Probíhá přihlášení..."
    if state == PodState.STARTING:
        return "Pod startuje..."
    if state == PodState.ERROR:
        return f"Chyba: {sm.error_reason or 'neznámá'}"
    return f"Stav: {state.value}"


def create_session_router(
    browser_manager: BrowserManager,
    token_extractor: TokenExtractor,
    tab_manager: TabManager,
    screen_scraper: ScreenScraper,
    teams_crawler: TeamsCrawler | None = None,
    scrape_storage: ScrapeStorage | None = None,
) -> APIRouter:

    @router.get("/session/{client_id}")
    async def get_session(client_id: str) -> SessionStatus:
        sm = get_or_create_state_manager(client_id, client_id)
        d = sm.to_dict()
        return SessionStatus(
            client_id=client_id,
            state=d["state"],
            has_token=(sm.state == PodState.ACTIVE),
            last_activity=datetime.now(timezone.utc).isoformat(),
            mfa_type=d["mfa_type"],
            mfa_message=d["mfa_message"],
            mfa_number=d["mfa_number"],
        )

    @router.post("/session/{client_id}/init")
    async def init_session(
        client_id: str,
        request: SessionInitRequest | None = None,
    ) -> SessionInitResponse:
        """Initialize session: ensure a PodAgent is running for this client."""
        req = request or SessionInitRequest()
        _save_init_config(
            client_id=client_id, connection_id=client_id,
            login_url=req.login_url,
            capabilities=req.capabilities or [],
            username=req.username, password=req.password,
        )

        sm = get_or_create_state_manager(client_id, client_id)

        try:
            context = await browser_manager.get_or_create_context(
                client_id, user_agent=req.user_agent,
            )
        except RuntimeError as exc:
            await sm.transition(PodState.ERROR, reason=str(exc))
            raise HTTPException(status_code=503, detail=str(exc)) from exc

        # Ensure at least one page exists
        if not context.pages:
            page = await context.new_page()
            await token_extractor.setup_interception(client_id, page)

        existing = agent_registry.get(client_id)
        if existing is not None:
            return SessionInitResponse(
                client_id=client_id, state=sm.state.value,
                message=_state_to_message(sm),
            )

        credentials = None
        if req.username:
            credentials = {"email": req.username, "password": req.password or ""}

        agent = PodAgent(
            client_id=client_id,
            connection_id=client_id,
            browser_context=context,
            state_manager=sm,
            credentials=credentials,
            login_url=req.login_url,
            capabilities=req.capabilities or [],
            scraper=screen_scraper,
            tab_manager=tab_manager,
        )
        agent_registry.register(client_id, agent)
        await agent.start()
        logger.info("init: PodAgent started for %s", client_id)

        return SessionInitResponse(
            client_id=client_id, state=sm.state.value,
            message=_state_to_message(sm),
        )

    @router.post("/session/{client_id}/mfa")
    async def submit_mfa(client_id: str, body: dict) -> SessionInitResponse:
        """Submit MFA verification code (used for SMS / Authenticator code MFA)."""
        code = body.get("code", "")
        if not code:
            raise HTTPException(status_code=400, detail="Missing 'code' in request body")

        sm = get_or_create_state_manager(client_id, client_id)
        if sm.state != PodState.AWAITING_MFA:
            raise HTTPException(
                status_code=409,
                detail=f"Pod not awaiting MFA (state: {sm.state.value})",
            )

        agent = agent_registry.get(client_id)
        context = browser_manager.get_context(client_id)
        if not agent or not context or not context.pages:
            raise HTTPException(status_code=404, detail=f"No agent/page for '{client_id}'")

        page = context.pages[0]
        ok = await agent.submit_mfa_code(page, code)
        if not ok:
            raise HTTPException(status_code=500, detail="MFA code input not found")

        return SessionInitResponse(
            client_id=client_id, state=sm.state.value,
            message=_state_to_message(sm),
        )

    @router.post("/session/{client_id}/refresh")
    async def refresh_session(client_id: str) -> SessionStatus:
        """Force navigate to Teams. Agent will resume from there."""
        context = browser_manager.get_context(client_id)
        if not context:
            raise HTTPException(status_code=404, detail=f"No session for '{client_id}'")

        page = context.pages[0] if context.pages else await context.new_page()
        try:
            await page.goto("https://teams.microsoft.com",
                            wait_until="domcontentloaded", timeout=30000)
        except Exception:
            logger.warning("Refresh navigation timed out for %s", client_id)

        sm = get_or_create_state_manager(client_id, client_id)
        d = sm.to_dict()
        return SessionStatus(
            client_id=client_id, state=d["state"],
            has_token=(sm.state == PodState.ACTIVE),
            last_activity=datetime.now(timezone.utc).isoformat(),
        )

    @router.post("/session/{client_id}/rediscover")
    async def rediscover_capabilities(client_id: str) -> dict:
        from app.kotlin_callback import notify_capabilities_discovered
        context = browser_manager.get_context(client_id)
        if not context:
            raise HTTPException(status_code=404, detail=f"No session for '{client_id}'")

        sm = get_or_create_state_manager(client_id, client_id)
        if sm.state != PodState.ACTIVE:
            raise HTTPException(
                status_code=409,
                detail=f"Session not active (state: {sm.state.value})",
            )

        screen_scraper.stop_scraping(client_id)
        await tab_manager.setup_tabs(client_id, context, None)
        available = tab_manager.get_available_capabilities(client_id)
        await notify_capabilities_discovered(client_id, client_id, available)
        screen_scraper.set_connection_id(client_id, client_id)
        await screen_scraper.start_scraping(client_id)

        return {"client_id": client_id, "available_capabilities": available}

    @router.delete("/session/{client_id}")
    async def delete_session(client_id: str) -> dict:
        agent = agent_registry.get(client_id)
        if agent:
            await agent.stop()
            agent_registry.remove(client_id)
        screen_scraper.stop_scraping(client_id)
        tab_manager.remove_client(client_id)
        await browser_manager.close_context(client_id)
        token_extractor.invalidate(client_id)
        logger.info("Session closed for %s", client_id)
        return {"status": "closed", "client_id": client_id}

    return router
