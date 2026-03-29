"""Session health monitoring — detects expired WhatsApp sessions."""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from app.browser_manager import BrowserManager
from app.kotlin_callback import notify_session_state
from app.models import SessionState
from app.screen_scraper import WhatsAppScraper

logger = logging.getLogger("whatsapp-browser.monitor")


class SessionMonitor:
    """Periodically checks WhatsApp Web session liveness.

    Uses VLM to detect if QR code or disconnection banner appeared,
    which means the session expired and needs re-login.
    """

    def __init__(
        self,
        browser_manager: BrowserManager,
        scraper: WhatsAppScraper,
        check_interval: int = 300,  # 5 minutes
    ) -> None:
        self._bm = browser_manager
        self._scraper = scraper
        self._interval = check_interval
        self._task: asyncio.Task | None = None

    async def start(self) -> None:
        self._task = asyncio.create_task(self._monitor_loop())
        logger.info("SessionMonitor started (interval=%ds)", self._interval)

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("SessionMonitor stopped")

    async def _monitor_loop(self) -> None:
        while True:
            try:
                await asyncio.sleep(self._interval)
                await self._check()
            except asyncio.CancelledError:
                break
            except Exception:
                logger.exception("SessionMonitor check failed")

    async def _check(self) -> None:
        client_id = self._bm.get_client_id()
        if not client_id:
            return

        state = self._bm.get_state()
        if state != SessionState.ACTIVE:
            return

        # Check if browser is still on WhatsApp Web
        context = self._bm.get_context()
        if not context or not context.pages:
            return

        page = context.pages[0]
        url = page.url

        # If browser navigated away from WhatsApp
        if "web.whatsapp.com" not in url:
            logger.warning(
                "Browser navigated away from WhatsApp Web: %s — marking EXPIRED",
                url,
            )
            self._bm.set_state(SessionState.EXPIRED)
            await notify_session_state(client_id, client_id, "EXPIRED")
            return

        # Use VLM to check login state periodically
        login_state = await self._scraper.check_login_state(client_id)
        if login_state in ("qr_visible", "phone_disconnected"):
            logger.warning("WhatsApp session expired (VLM detected: %s)", login_state)
            self._bm.set_state(SessionState.EXPIRED)
            await notify_session_state(client_id, client_id, "EXPIRED")
