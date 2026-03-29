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
        self._browser = await self._playwright.chromium.launch(
            headless=settings.headless,
            args=[
                "--disable-blink-features=AutomationControlled",
                "--disable-dev-shm-usage",
                "--no-sandbox",
            ],
        )
        logger.info("Playwright Chromium launched (headless=%s)", settings.headless)

    async def shutdown(self) -> None:
        if self._context and self._client_id:
            await self.save_state(self._client_id)
            try:
                await self._context.close()
            except Exception:
                logger.exception("Error closing context")
        if self._browser:
            await self._browser.close()
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
            await self.save_state(self._client_id or "")
            await self._context.close()

        state_path = self._state_path(client_id)
        storage_state = str(state_path) if state_path.exists() else None

        self._context = await self._browser.new_context(
            storage_state=storage_state,
            user_agent=user_agent
            or (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/131.0.0.0 Safari/537.36"
            ),
            viewport={"width": 1920, "height": 1080},
        )
        self._client_id = client_id
        self._state = SessionState.PENDING_LOGIN
        logger.info("Created browser context for client %s", client_id)
        return self._context

    async def save_state(self, client_id: str) -> None:
        if not self._context:
            return
        profile_dir = Path(settings.profiles_dir) / client_id
        profile_dir.mkdir(parents=True, exist_ok=True)
        state = await self._context.storage_state()
        state_path = self._state_path(client_id)
        state_path.write_text(json.dumps(state))
        logger.debug("Saved browser state for client %s", client_id)

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
