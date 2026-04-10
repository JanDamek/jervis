"""Core browser context management for WhatsApp Web.

Single Playwright context with persistent profile for maintaining
WhatsApp Web session across pod restarts.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from playwright.async_api import Browser, BrowserContext, Playwright, async_playwright

from app.config import settings
from app.models import SessionState

logger = logging.getLogger("whatsapp-browser")


class BrowserManager:
    """Manages a single Playwright browser context for WhatsApp Web.

    Persistent Chromium profile stored under ``{profiles_dir}/{client_id}/``
    so that WhatsApp Web session survives pod restarts (no QR rescan needed).
    """

    def __init__(self) -> None:
        self._context: BrowserContext | None = None
        self._client_id: str | None = None
        self._state: SessionState = SessionState.EXPIRED
        self._playwright: Playwright | None = None
        self._browser: Browser | None = None

    async def startup(self) -> None:
        self._playwright = await async_playwright().start()
        # Don't launch browser here — persistent context creates its own browser
        logger.info("Playwright initialized (headless=%s)", settings.headless)

    async def shutdown(self) -> None:
        if self._context:
            try:
                await self._context.close()
            except Exception:
                logger.exception("Error closing context")
        if self._playwright:
            await self._playwright.stop()
        logger.info("BrowserManager shut down")

    async def get_or_create_context(
        self,
        client_id: str,
        user_agent: str | None = None,
    ) -> BrowserContext:
        if self._context and self._client_id == client_id:
            return self._context

        # Close existing context if different client
        if self._context:
            await self._context.close()

        # Persistent context stores ENTIRE Chromium profile on PVC (IndexedDB,
        # cookies, localStorage, Service Workers). WhatsApp Web session survives
        # pod restarts — no QR rescan needed.
        profile_dir = Path(settings.profiles_dir) / client_id
        profile_dir.mkdir(parents=True, exist_ok=True)

        ua = user_agent or (
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/131.0.0.0 Safari/537.36"
        )

        self._context = await self._playwright.chromium.launch_persistent_context(
            user_data_dir=str(profile_dir),
            headless=settings.headless,
            user_agent=ua,
            viewport={"width": 1920, "height": 1080},
            args=[
                "--disable-blink-features=AutomationControlled",
                "--disable-dev-shm-usage",
                "--no-sandbox",
            ],
        )
        self._client_id = client_id
        self._state = SessionState.PENDING_LOGIN
        logger.info("Created persistent browser context for client %s at %s", client_id, profile_dir)
        return self._context

    async def save_state(self, client_id: str) -> None:
        # Persistent context auto-saves to user_data_dir — no manual save needed
        pass

    def get_context(self) -> BrowserContext | None:
        return self._context

    def get_client_id(self) -> str | None:
        return self._client_id

    def get_state(self, client_id: str | None = None) -> SessionState:
        if client_id and client_id != self._client_id:
            return SessionState.EXPIRED
        return self._state

    def set_state(self, state: SessionState) -> None:
        self._state = state

    @property
    def active(self) -> bool:
        return self._context is not None and self._state == SessionState.ACTIVE

    @property
    def is_ready(self) -> bool:
        return self._browser is not None

    def _state_path(self, client_id: str) -> Path:
        return Path(settings.profiles_dir) / client_id / "state.json"
