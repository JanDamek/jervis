"""Session management endpoints – init, status, refresh, close, MFA."""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException

from app.auto_login import LoginStage, MfaType, auto_login, poll_mfa_approval, submit_mfa_code
from app.browser_manager import BrowserManager
from app.kotlin_callback import notify_capabilities_discovered, notify_session_state
from app.models import (
    SessionInitRequest,
    SessionInitResponse,
    SessionState,
    SessionStatus,
)
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import ScreenScraper
from app.tab_manager import TabManager, TabType
from app.teams_crawler import TeamsCrawler
from app.token_extractor import TokenExtractor

logger = logging.getLogger("o365-browser-pool")

router = APIRouter(tags=["session"])


def create_session_router(
    browser_manager: BrowserManager,
    token_extractor: TokenExtractor,
    tab_manager: TabManager,
    screen_scraper: ScreenScraper,
    teams_crawler: TeamsCrawler | None = None,
    scrape_storage: ScrapeStorage | None = None,
) -> APIRouter:
    # Store capabilities per client (set during init, used during activation)
    _client_capabilities: dict[str, list[str]] = {}
    # Store MFA state per client
    _mfa_state: dict[str, dict] = {}
    # Track crawl tasks per client
    _crawl_tasks: dict[str, asyncio.Task] = {}
    # Track MFA approval polling tasks per client
    _mfa_poll_tasks: dict[str, asyncio.Task] = {}

    async def _activate_session(client_id: str, context) -> None:
        """Common activation steps after successful login (direct or MFA).

        Sets up tabs, discovers capabilities, starts scraping and crawling.
        """
        browser_manager.set_state(client_id, SessionState.ACTIVE)
        await browser_manager.save_state(client_id)
        _mfa_state.pop(client_id, None)

        caps = _client_capabilities.get(client_id, [])
        await tab_manager.setup_tabs(client_id, context, caps)
        available = tab_manager.get_available_capabilities(client_id)
        await notify_capabilities_discovered(client_id, client_id, available)
        screen_scraper.set_connection_id(client_id, client_id)
        await screen_scraper.start_scraping(client_id)
        await _trigger_crawl(client_id, context)

    async def _poll_and_activate(client_id: str, page, context) -> None:
        """Background task: poll MFA approval, activate session on success."""
        try:
            result = await poll_mfa_approval(page, timeout_seconds=120)

            if result.stage == LoginStage.LOGGED_IN:
                logger.info("MFA approved for %s — activating session", client_id)
                await _activate_session(client_id, context)
            else:
                logger.warning("MFA polling ended for %s: %s", client_id, result.error)
                browser_manager.set_state(client_id, SessionState.ERROR)
                await notify_session_state(
                    client_id, client_id, "EXPIRED",
                    mfa_message="MFA ověření vypršelo — spusťte přihlášení znovu",
                )
        except Exception as e:
            logger.error("MFA poll task failed for %s: %s", client_id, e)
            browser_manager.set_state(client_id, SessionState.ERROR)
        finally:
            _mfa_poll_tasks.pop(client_id, None)

    async def _trigger_crawl(client_id: str, context) -> None:
        """Start Teams crawl in background after session activation."""
        if not teams_crawler or not scrape_storage:
            return
        if client_id in _crawl_tasks and not _crawl_tasks[client_id].done():
            return

        # Get chat tab or first available page
        page = await tab_manager.get_tab(client_id, TabType.CHAT)
        if not page and context and context.pages:
            page = context.pages[0]
        if not page:
            return

        connection_id = client_id  # connectionId == clientId for O365

        async def _run():
            # Wait for scraping to initialize tabs
            await asyncio.sleep(15)
            try:
                return await teams_crawler.crawl_all_chats(
                    page, client_id, connection_id, scrape_storage,
                    max_chats=50, max_scrolls_per_chat=3,
                )
            except Exception as e:
                logger.error("Auto-crawl failed for %s: %s", client_id, e)

        _crawl_tasks[client_id] = asyncio.create_task(_run())
        logger.info("Auto-crawl started for %s", client_id)

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
                available = tab_manager.get_available_capabilities(client_id)
                await notify_capabilities_discovered(client_id, client_id, available)
                # client_id IS the connectionId (set by Kotlin server)
                screen_scraper.set_connection_id(client_id, client_id)
                await screen_scraper.start_scraping(client_id)
                # Auto-crawl Teams chats
                await _trigger_crawl(client_id, context)

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
                logger.info("Auto-login successful for %s", client_id)
                await _activate_session(client_id, context)

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
                # Notify Kotlin server (creates USER_TASK notification)
                await notify_session_state(
                    client_id, client_id, "AWAITING_MFA",
                    mfa_type=mfa_type_val,
                    mfa_message=result.mfa_message,
                    mfa_number=result.mfa_number,
                )

                # For authenticator_number: start background polling
                # (user approves in Authenticator app, page auto-transitions)
                if result.mfa_type == MfaType.AUTHENTICATOR_NUMBER:
                    # Cancel any existing poll task
                    old_task = _mfa_poll_tasks.pop(client_id, None)
                    if old_task and not old_task.done():
                        old_task.cancel()
                    _mfa_poll_tasks[client_id] = asyncio.create_task(
                        _poll_and_activate(client_id, page, context)
                    )
                    logger.info("Started MFA approval polling for %s", client_id)

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

        # Cancel any running MFA poll task (user submitted code manually)
        old_poll = _mfa_poll_tasks.pop(client_id, None)
        if old_poll and not old_poll.done():
            old_poll.cancel()

        result = await submit_mfa_code(page, code)

        if result.stage == LoginStage.LOGGED_IN:
            logger.info("MFA verified, session active for %s", client_id)
            await _activate_session(client_id, context)

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

    @router.post("/session/{client_id}/rediscover")
    async def rediscover_capabilities(client_id: str) -> dict:
        """Re-run tab setup to rediscover available capabilities.

        Closes existing tabs, re-opens them, and pushes updated capabilities
        to the Kotlin server. Use when account licenses change or to verify
        current capability state.
        """
        context = browser_manager.get_context(client_id)
        if not context:
            raise HTTPException(
                status_code=404,
                detail=f"No session for client '{client_id}'",
            )

        state = browser_manager.get_state(client_id)
        if state != SessionState.ACTIVE:
            raise HTTPException(
                status_code=409,
                detail=f"Session not active (state: {state})",
            )

        # Stop scraping during rediscovery
        screen_scraper.stop_scraping(client_id)

        # Re-run tab setup with stored capabilities
        caps = _client_capabilities.get(client_id, [])
        await tab_manager.setup_tabs(client_id, context, caps)
        available = tab_manager.get_available_capabilities(client_id)
        await notify_capabilities_discovered(client_id, client_id, available)

        # Restart scraping
        screen_scraper.set_connection_id(client_id, client_id)
        await screen_scraper.start_scraping(client_id)

        logger.info("Rediscovery complete for %s: %s", client_id, available)
        return {
            "client_id": client_id,
            "available_capabilities": available,
        }

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
