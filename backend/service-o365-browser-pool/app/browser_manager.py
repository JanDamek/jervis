"""Core browser context management – one Playwright context per client."""

from __future__ import annotations

import json
import logging
from pathlib import Path

from playwright.async_api import Browser, BrowserContext, Playwright, async_playwright

from app.config import settings
from app.models import SessionState

logger = logging.getLogger("o365-browser-pool")


class BrowserManager:
    """Manages one Playwright browser context per client.

    Each client gets a persistent Chromium profile stored under
    ``{profiles_dir}/{client_id}/``.  Cookies and local-storage are
    persisted so that O365 sessions survive pod restarts.
    """

    def __init__(self) -> None:
        self._contexts: dict[str, BrowserContext] = {}
        self._states: dict[str, SessionState] = {}
        self._playwright: Playwright | None = None
        self._browser: Browser | None = None

    # -- lifecycle -------------------------------------------------------------

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
        for client_id in list(self._contexts):
            await self._close_context(client_id)
        if self._browser:
            await self._browser.close()
        if self._playwright:
            await self._playwright.stop()
        logger.info("BrowserManager shut down")

    # -- context management ----------------------------------------------------

    async def get_or_create_context(
        self,
        client_id: str,
        user_agent: str | None = None,
    ) -> BrowserContext:
        if client_id in self._contexts:
            return self._contexts[client_id]

        if len(self._contexts) >= settings.max_contexts:
            raise RuntimeError(
                f"Max browser contexts reached ({settings.max_contexts})"
            )

        state_path = self._state_path(client_id)
        storage_state = str(state_path) if state_path.exists() else None

        context = await self._browser.new_context(  # type: ignore[union-attr]
            storage_state=storage_state,
            user_agent=user_agent
            or (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/131.0.0.0 Safari/537.36"
            ),
            viewport={"width": 1920, "height": 1080},
        )

        self._contexts[client_id] = context
        self._states[client_id] = SessionState.PENDING_LOGIN
        logger.info("Created browser context for client %s", client_id)
        return context

    async def save_state(self, client_id: str) -> None:
        ctx = self._contexts.get(client_id)
        if not ctx:
            return
        profile_dir = Path(settings.profiles_dir) / client_id
        profile_dir.mkdir(parents=True, exist_ok=True)
        state = await ctx.storage_state()
        state_path = self._state_path(client_id)
        state_path.write_text(json.dumps(state))
        logger.debug("Saved browser state for client %s", client_id)

    async def close_context(self, client_id: str) -> None:
        await self._close_context(client_id)

    def get_context(self, client_id: str) -> BrowserContext | None:
        return self._contexts.get(client_id)

    def get_state(self, client_id: str) -> SessionState:
        return self._states.get(client_id, SessionState.EXPIRED)

    def set_state(self, client_id: str, state: SessionState) -> None:
        self._states[client_id] = state

    @property
    def active_count(self) -> int:
        return len(self._contexts)

    @property
    def client_ids(self) -> list[str]:
        return list(self._contexts.keys())

    # -- private ---------------------------------------------------------------

    def _state_path(self, client_id: str) -> Path:
        return Path(settings.profiles_dir) / client_id / "state.json"

    async def _close_context(self, client_id: str) -> None:
        ctx = self._contexts.pop(client_id, None)
        self._states.pop(client_id, None)
        if ctx:
            try:
                await self.save_state(client_id)
                await ctx.close()
            except Exception:
                logger.exception("Error closing context for %s", client_id)
