"""Session health monitoring – detects expired sessions and triggers refresh."""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from app.browser_manager import BrowserManager
from app.kotlin_callback import notify_session_state
from app.models import SessionState
from app.token_extractor import TokenExtractor

logger = logging.getLogger("o365-browser-pool")


class SessionMonitor:
    """Periodically checks browser sessions for token expiry and liveness.

    Before marking a session as EXPIRED, attempts to re-acquire a Graph
    token from the browser's MSAL cache.
    """

    def __init__(
        self,
        browser_manager: BrowserManager,
        token_extractor: TokenExtractor,
        check_interval: int = 300,  # 5 minutes
    ) -> None:
        self._bm = browser_manager
        self._te = token_extractor
        self._interval = check_interval
        self._task: asyncio.Task | None = None
        self._last_check: dict[str, datetime] = {}

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
                await self._check_all()
            except asyncio.CancelledError:
                break
            except Exception:
                logger.exception("SessionMonitor check failed")

    async def _check_all(self) -> None:
        now = datetime.now(timezone.utc)
        for client_id in self._bm.client_ids:
            state = self._bm.get_state(client_id)
            if state != SessionState.ACTIVE:
                continue

            # Web scraping mode — no token needed, session stays active
            # as long as browser is logged in (detected by URL check)
            if not self._te.has_any_token(client_id):
                # No token was ever captured — this is a scraping-only session
                # Check if browser is still on a logged-in page
                context = self._bm.get_context(client_id)
                if context and context.pages:
                    page = context.pages[0]
                    url = page.url
                    if "login.microsoftonline.com" in url or "login.live.com" in url:
                        logger.warning(
                            "Browser redirected to login for %s, marking EXPIRED",
                            client_id,
                        )
                        self._bm.set_state(client_id, SessionState.EXPIRED)
                        await notify_session_state(
                            client_id, client_id, "EXPIRED",
                        )
                self._last_check[client_id] = now
                continue

            # Token-based mode (OAuth proxy) — check token validity
            if not self._te.has_valid_token(client_id):
                context = self._bm.get_context(client_id)
                if context and context.pages:
                    acquired = await self._te.acquire_graph_token(
                        client_id, context.pages[0],
                    )
                    if acquired:
                        logger.info(
                            "Re-acquired Graph token for %s via MSAL cache",
                            client_id,
                        )
                        self._last_check[client_id] = now
                        continue

                # Token refresh failed — but browser session may still be valid
                # (e.g. MCAS proxy captures tokens that can't be refreshed via MSAL)
                # Only mark EXPIRED if browser is actually on a login page
                context2 = self._bm.get_context(client_id)
                if context2 and context2.pages:
                    url = context2.pages[0].url or ""
                    if "login.microsoftonline.com" in url or "login.live.com" in url:
                        logger.warning(
                            "Token expired AND browser on login page for %s — marking EXPIRED",
                            client_id,
                        )
                        self._bm.set_state(client_id, SessionState.EXPIRED)
                        await notify_session_state(
                            client_id, client_id, "EXPIRED",
                        )
                    else:
                        logger.info(
                            "Token expired for %s but browser still on %s — session OK",
                            client_id, url[:60],
                        )
                else:
                    logger.warning(
                        "Token expired for %s and no browser context — marking EXPIRED",
                        client_id,
                    )
                    self._bm.set_state(client_id, SessionState.EXPIRED)
                    await notify_session_state(
                        client_id, client_id, "EXPIRED",
                    )

            self._last_check[client_id] = now

    def get_last_check(self, client_id: str) -> datetime | None:
        return self._last_check.get(client_id)
