"""Session management endpoints – init, status, refresh, close, MFA."""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException

from app.auto_login import LoginStage, auto_login, submit_mfa_code
from app.browser_manager import BrowserManager
from app.kotlin_callback import notify_session_state
from app.models import (
    SessionInitRequest,
    SessionInitResponse,
    SessionState,
    SessionStatus,
)
from app.screen_scraper import ScreenScraper
from app.tab_manager import TabManager
from app.token_extractor import TokenExtractor

logger = logging.getLogger("o365-browser-pool")

router = APIRouter(tags=["session"])


def create_session_router(
    browser_manager: BrowserManager,
    token_extractor: TokenExtractor,
    tab_manager: TabManager,
    screen_scraper: ScreenScraper,
) -> APIRouter:
    # Store capabilities per client (set during init, used during activation)
    _client_capabilities: dict[str, list[str]] = {}
    # Store MFA state per client
    _mfa_state: dict[str, dict] = {}

    @router.get("/session/{client_id}")
    async def get_session(client_id: str) -> SessionStatus:
        """Get session status for a client."""
        state = browser_manager.get_state(client_id)
        has_token = token_extractor.has_valid_token(client_id)

        # If no token yet but session exists, try active acquisition from MSAL cache
        if not has_token and state == SessionState.PENDING_LOGIN:
            context = browser_manager.get_context(client_id)
            if context and context.pages:
                page = context.pages[0]
                acquired = await token_extractor.acquire_graph_token(client_id, page)
                if acquired:
                    has_token = True

        # Detect login completion: check if Teams/Outlook loaded (not on login page)
        if state in (SessionState.PENDING_LOGIN, SessionState.AWAITING_MFA) and not has_token:
            context = browser_manager.get_context(client_id)
            if context and context.pages:
                page_url = context.pages[0].url or ""
                # If we're past the login page and on Teams/Outlook, consider it active
                if any(
                    domain in page_url
                    for domain in [
                        "teams.microsoft.com/v2",
                        "teams.live.com",
                        "outlook.office.com",
                        "outlook.live.com",
                    ]
                ):
                    has_token = True  # Login detected via URL
                    logger.info(
                        "Login detected for %s via URL: %s",
                        client_id, page_url[:80],
                    )

        # When login detected, activate session and start scraping
        if has_token and state != SessionState.ACTIVE:
            browser_manager.set_state(client_id, SessionState.ACTIVE)
            state = SessionState.ACTIVE
            await browser_manager.save_state(client_id)
            _mfa_state.pop(client_id, None)
            logger.info("Session activated for %s", client_id)

            # Set up tabs and start scraping (using stored capabilities)
            context = browser_manager.get_context(client_id)
            if context:
                caps = _client_capabilities.get(client_id, [])
                await tab_manager.setup_tabs(client_id, context, caps)
                # client_id IS the connectionId (set by Kotlin server)
                screen_scraper.set_connection_id(client_id, client_id)
                await screen_scraper.start_scraping(client_id)

        # Include MFA info in status if awaiting
        mfa_info = _mfa_state.get(client_id)

        token_info = token_extractor.get_graph_token(client_id)
        return SessionStatus(
            client_id=client_id,
            state=state,
            has_token=has_token or state == SessionState.ACTIVE,
            last_activity=datetime.now(timezone.utc).isoformat(),
            last_token_extract=(
                token_info.extracted_at.isoformat() if token_info else None
            ),
            mfa_type=mfa_info.get("mfa_type") if mfa_info else None,
            mfa_message=mfa_info.get("mfa_message") if mfa_info else None,
            mfa_number=mfa_info.get("mfa_number") if mfa_info else None,
        )

    @router.post("/session/{client_id}/init")
    async def init_session(
        client_id: str,
        request: SessionInitRequest | None = None,
    ) -> SessionInitResponse:
        """Create a browser context and start login.

        If credentials (username/password) are provided, auto-login is attempted.
        If MFA is required, state changes to AWAITING_MFA.
        Without credentials, opens login page for manual VNC login.
        """
        req = request or SessionInitRequest()

        # Store capabilities for later tab setup
        if req.capabilities:
            _client_capabilities[client_id] = req.capabilities

        try:
            context = await browser_manager.get_or_create_context(
                client_id, user_agent=req.user_agent
            )
        except RuntimeError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc

        # Reuse existing page if available — never open duplicate tabs
        if context.pages:
            page = context.pages[0]
            # Close extra tabs if any
            for extra in context.pages[1:]:
                try:
                    await extra.close()
                except Exception:
                    pass
        else:
            page = await context.new_page()
            await token_extractor.setup_interception(client_id, page)

        # Auto-login if credentials provided
        if req.username and req.password:
            browser_manager.set_state(client_id, SessionState.PENDING_LOGIN)
            logger.info("Auto-login starting for %s (user: %s)", client_id, req.username)

            result = await auto_login(page, req.username, req.password, req.login_url)

            if result.stage == LoginStage.LOGGED_IN:
                browser_manager.set_state(client_id, SessionState.ACTIVE)
                await browser_manager.save_state(client_id)
                _mfa_state.pop(client_id, None)
                logger.info("Auto-login successful for %s", client_id)

                # Set up tabs and start scraping
                caps = _client_capabilities.get(client_id, [])
                await tab_manager.setup_tabs(client_id, context, caps)
                screen_scraper.set_connection_id(client_id, client_id)
                await screen_scraper.start_scraping(client_id)

                return SessionInitResponse(
                    client_id=client_id,
                    state=SessionState.ACTIVE,
                    message="Automatické přihlášení úspěšné",
                )

            if result.stage == LoginStage.MFA_REQUIRED:
                browser_manager.set_state(client_id, SessionState.AWAITING_MFA)
                mfa_type_val = result.mfa_type.value if result.mfa_type else None
                _mfa_state[client_id] = {
                    "mfa_type": mfa_type_val,
                    "mfa_message": result.mfa_message,
                    "mfa_number": result.mfa_number,
                }
                logger.info(
                    "Auto-login: MFA required for %s — %s",
                    client_id, result.mfa_type,
                )
                # Notify Kotlin server (creates USER_TASK if user not in settings)
                await notify_session_state(
                    client_id, client_id, "AWAITING_MFA",
                    mfa_type=mfa_type_val,
                    mfa_message=result.mfa_message,
                    mfa_number=result.mfa_number,
                )
                return SessionInitResponse(
                    client_id=client_id,
                    state=SessionState.AWAITING_MFA,
                    message=result.mfa_message or "Vyžadováno dvoufaktorové ověření",
                )

            if result.stage == LoginStage.ERROR:
                browser_manager.set_state(client_id, SessionState.ERROR)
                return SessionInitResponse(
                    client_id=client_id,
                    state=SessionState.ERROR,
                    message=f"Přihlášení selhalo: {result.error}",
                )

            # Unexpected — fall through to pending login
            browser_manager.set_state(client_id, SessionState.PENDING_LOGIN)
            return SessionInitResponse(
                client_id=client_id,
                state=SessionState.PENDING_LOGIN,
                message=f"Přihlášení nedokončeno (stage: {result.stage})",
            )

        # No credentials — manual login via VNC
        current_url = page.url or ""
        if (
            "teams.microsoft.com" not in current_url
            and "login.microsoftonline.com" not in current_url
            and "teams.live.com" not in current_url
        ):
            try:
                await page.goto(
                    req.login_url, wait_until="domcontentloaded", timeout=30000,
                )
            except Exception:
                logger.warning(
                    "Navigation to %s timed out for %s", req.login_url, client_id,
                )

        browser_manager.set_state(client_id, SessionState.PENDING_LOGIN)
        logger.info("Session initialized for %s → %s (manual login)", client_id, req.login_url)

        return SessionInitResponse(
            client_id=client_id,
            state=SessionState.PENDING_LOGIN,
            message=f"Session created. Navigate to login page: {req.login_url}",
        )

    @router.post("/session/{client_id}/mfa")
    async def submit_mfa(client_id: str, body: dict) -> SessionInitResponse:
        """Submit MFA code for a session awaiting MFA.

        Body: {"code": "123456"}
        """
        code = body.get("code", "")
        if not code:
            raise HTTPException(
                status_code=400,
                detail="Missing 'code' in request body",
            )

        state = browser_manager.get_state(client_id)
        if state != SessionState.AWAITING_MFA:
            raise HTTPException(
                status_code=409,
                detail=f"Session not awaiting MFA (state: {state})",
            )

        context = browser_manager.get_context(client_id)
        if not context or not context.pages:
            raise HTTPException(
                status_code=404,
                detail=f"No browser page for client '{client_id}'",
            )

        page = context.pages[0]
        result = await submit_mfa_code(page, code)

        if result.stage == LoginStage.LOGGED_IN:
            browser_manager.set_state(client_id, SessionState.ACTIVE)
            await browser_manager.save_state(client_id)
            _mfa_state.pop(client_id, None)
            logger.info("MFA verified, session active for %s", client_id)

            # Set up tabs and start scraping
            caps = _client_capabilities.get(client_id, [])
            await tab_manager.setup_tabs(client_id, context, caps)
            screen_scraper.set_connection_id(client_id, client_id)
            await screen_scraper.start_scraping(client_id)

            return SessionInitResponse(
                client_id=client_id,
                state=SessionState.ACTIVE,
                message="MFA ověřeno — přihlášení úspěšné",
            )

        if result.stage == LoginStage.ERROR:
            return SessionInitResponse(
                client_id=client_id,
                state=SessionState.AWAITING_MFA,
                message=f"MFA selhalo: {result.error}",
            )

        # Still awaiting (wrong code?)
        return SessionInitResponse(
            client_id=client_id,
            state=SessionState.AWAITING_MFA,
            message="Kód nebyl přijat — zkuste znovu",
        )

    @router.post("/session/{client_id}/refresh")
    async def refresh_session(client_id: str) -> SessionStatus:
        """Force navigation to Teams to trigger a token refresh."""
        context = browser_manager.get_context(client_id)
        if not context:
            raise HTTPException(
                status_code=404,
                detail=f"No session for client '{client_id}'",
            )

        page = await context.new_page()
        await token_extractor.setup_interception(client_id, page)

        try:
            await page.goto(
                "https://teams.microsoft.com",
                wait_until="networkidle",
                timeout=30000,
            )
        except Exception:
            logger.warning("Refresh navigation timed out for %s", client_id)

        await token_extractor.acquire_graph_token(client_id, page)
        await page.close()

        if token_extractor.has_valid_token(client_id):
            browser_manager.set_state(client_id, SessionState.ACTIVE)
        else:
            browser_manager.set_state(client_id, SessionState.EXPIRED)

        await browser_manager.save_state(client_id)

        return SessionStatus(
            client_id=client_id,
            state=browser_manager.get_state(client_id),
            last_activity=datetime.now(timezone.utc).isoformat(),
        )

    @router.delete("/session/{client_id}")
    async def delete_session(client_id: str) -> dict:
        """Close browser context and clean up."""
        screen_scraper.stop_scraping(client_id)
        tab_manager.remove_client(client_id)
        await browser_manager.close_context(client_id)
        token_extractor.invalidate(client_id)
        _mfa_state.pop(client_id, None)
        logger.info("Session closed for %s", client_id)
        return {"status": "closed", "client_id": client_id}

    return router
