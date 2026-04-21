"""Session management endpoints for WhatsApp Browser service."""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter, HTTPException

from app.browser_manager import BrowserManager
from app.config import settings
from app.kotlin_callback import notify_capabilities_discovered, notify_session_state
from app.models import SessionState, SessionStatus
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import WhatsAppScraper

logger = logging.getLogger("whatsapp-browser.session")

router = APIRouter(tags=["session"])


def create_session_router(
    browser_manager: BrowserManager,
    scraper: WhatsAppScraper,
    scrape_storage: ScrapeStorage,
) -> APIRouter:

    # Session init flow moved to gRPC `WhatsAppBrowserService.InitSession`
    # (see app/grpc_server.py). Legacy FastAPI `init_session` helper was
    # never wired to an @router decorator — removed 2026-04-20 to kill
    # the Pydantic SessionInitRequest mirror (guideline §11).

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

    async def close_session(client_id: str) -> dict:
        """Close and clean up a browser session."""
        context = browser_manager.get_context()
        if context:
            await browser_manager.save_state(client_id)
        return {"status": "closed", "client_id": client_id}

    # /session/{client_id}/debug-dom removed — pod-local REST debug surface
    # that never belonged on the input side. DOM inspection in the running
    # pod happens via VNC + devtools when needed.

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
