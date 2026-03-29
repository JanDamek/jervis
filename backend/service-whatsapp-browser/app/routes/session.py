"""Session management endpoints for WhatsApp Browser service."""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter, HTTPException

from app.browser_manager import BrowserManager
from app.config import settings
from app.kotlin_callback import notify_capabilities_discovered, notify_session_state
from app.models import SessionInitRequest, SessionInitResponse, SessionState, SessionStatus
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import WhatsAppScraper

logger = logging.getLogger("whatsapp-browser.session")

router = APIRouter(tags=["session"])


def create_session_router(
    browser_manager: BrowserManager,
    scraper: WhatsAppScraper,
    scrape_storage: ScrapeStorage,
) -> APIRouter:

    @router.post("/session/{client_id}/init")
    async def init_session(client_id: str, request: SessionInitRequest) -> SessionInitResponse:
        """Initialize a WhatsApp Web browser session.

        Opens WhatsApp Web in a Playwright context. The user must scan the QR code
        via noVNC to complete login.
        """
        context = await browser_manager.get_or_create_context(
            client_id,
            user_agent=request.user_agent,
        )

        # Open WhatsApp Web
        page = await context.new_page()
        await page.goto(request.login_url, wait_until="domcontentloaded", timeout=30000)

        # Set connection ID for storage
        scraper.set_connection_id(client_id)

        # Start QR monitoring in background
        asyncio.create_task(_monitor_qr_login(client_id, browser_manager, scraper, scrape_storage))

        return SessionInitResponse(
            client_id=client_id,
            state=SessionState.PENDING_LOGIN,
            novnc_url=f"{settings.novnc_external_url}/vnc-login",
            message="WhatsApp Web otevřen. Naskenujte QR kód telefonem.",
        )

    @router.get("/session/{client_id}")
    async def get_session_status(client_id: str) -> SessionStatus:
        """Get current session status."""
        state = browser_manager.get_state(client_id)

        message = {
            SessionState.PENDING_LOGIN: "Čekání na naskenování QR kódu",
            SessionState.ACTIVE: "WhatsApp Web připojen",
            SessionState.EXPIRED: "Session vypršela — naskenujte QR znovu",
            SessionState.ERROR: "Chyba session",
        }.get(state, str(state))

        return SessionStatus(
            client_id=client_id,
            state=state,
            message=message,
        )

    @router.delete("/session/{client_id}")
    async def close_session(client_id: str) -> dict:
        """Close and clean up a browser session."""
        context = browser_manager.get_context()
        if context:
            await browser_manager.save_state(client_id)
        return {"status": "closed", "client_id": client_id}

    return router


async def _monitor_qr_login(
    client_id: str,
    browser_manager: BrowserManager,
    scraper: WhatsAppScraper,
    scrape_storage: ScrapeStorage,
) -> None:
    """Background task: poll VLM to detect when QR code is scanned and login completes."""
    logger.info("Starting QR login monitor for %s", client_id)

    max_wait = 300  # 5 minutes timeout
    elapsed = 0

    while elapsed < max_wait:
        try:
            state = browser_manager.get_state(client_id)
            if state != SessionState.PENDING_LOGIN:
                return

            login_state = await scraper.check_login_state(client_id)

            if login_state == "logged_in":
                logger.info("WhatsApp login detected for %s!", client_id)
                browser_manager.set_state(SessionState.ACTIVE)

                # Save browser state immediately (preserves session)
                await browser_manager.save_state(client_id)

                # Notify Kotlin server
                await notify_session_state(client_id, client_id, "ACTIVE")
                await notify_capabilities_discovered(
                    client_id, client_id, ["CHAT_READ"],
                )

                return

            elif login_state == "error":
                logger.warning("WhatsApp login error for %s", client_id)
                browser_manager.set_state(SessionState.ERROR)
                await notify_session_state(client_id, client_id, "ERROR")
                return

        except Exception as e:
            logger.warning("QR monitor error: %s", e)

        await asyncio.sleep(settings.qr_check_interval)
        elapsed += settings.qr_check_interval

    # Timeout
    logger.warning("QR login timeout for %s after %ds", client_id, max_wait)
    browser_manager.set_state(SessionState.EXPIRED)
    await notify_session_state(client_id, client_id, "EXPIRED")
