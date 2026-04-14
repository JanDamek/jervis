"""Session health loop — autonomous monitoring every 60s.

DOM check first (fast), VLM fallback if DOM fails.
Detects session expiry and triggers recovery (1 attempt).
"""

from __future__ import annotations

import asyncio
import logging

from playwright.async_api import BrowserContext

from app.ai_login import ai_login
from app.ai_navigator import see_screen
from app.browser_manager import BrowserManager
from app.kotlin_callback import notify_session_state
from app.pod_state import PodState, PodStateManager

logger = logging.getLogger("o365-browser-pool.health")

# Teams DOM indicators — if any exists, session is OK
_TEAMS_SELECTORS = [
    '[data-tid="chat-list"]',
    '[data-tid="app-layout"]',
    '[data-tid="app-bar"]',
    'div[data-app-section]',
    '#LeftRail',
]

CHECK_INTERVAL_S = 60


class HealthLoop:
    """Periodically checks browser session health. Self-heals on expiry."""

    def __init__(
        self,
        browser_manager: BrowserManager,
        state_manager: PodStateManager,
        credentials: dict | None = None,
    ) -> None:
        self._bm = browser_manager
        self._sm = state_manager
        self._credentials = credentials
        self._task: asyncio.Task | None = None

    async def start(self) -> None:
        self._task = asyncio.create_task(self._loop())
        logger.info("HealthLoop started (interval=%ds)", CHECK_INTERVAL_S)

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    async def _loop(self) -> None:
        while True:
            try:
                await asyncio.sleep(CHECK_INTERVAL_S)

                # Only check when ACTIVE
                if self._sm.state != PodState.ACTIVE:
                    continue

                await self._check()
            except asyncio.CancelledError:
                break
            except Exception:
                logger.exception("HealthLoop check failed")

    async def _check(self) -> None:
        client_id = self._sm.client_id
        context = self._bm.get_context(client_id)
        if not context or not context.pages:
            return

        page = context.pages[0]

        # Fast path: DOM check
        for selector in _TEAMS_SELECTORS:
            try:
                el = page.locator(selector).first
                if await el.is_visible(timeout=2000):
                    return  # Session OK
            except Exception:
                continue

        # DOM check failed — check URL first
        url = page.url or ""
        if "login.microsoftonline.com" in url or "login.live.com" in url:
            logger.warning("HealthLoop: browser on login page — starting recovery")
            await self._recover(page)
            return

        # URL is not login — maybe Teams is on a different page (settings, etc.)
        # Use VLM to check
        screen_info = await see_screen(page)
        screen_type = screen_info.get("screen_type", "unknown")

        if screen_type in ("teams_loaded", "outlook_loaded", "calendar_loaded", "teams_loading"):
            return  # Session OK, just not on chat page

        if screen_type in ("login_email", "login_password", "mfa_method_picker",
                           "mfa_approval", "mfa_code_entry"):
            logger.warning("HealthLoop: VLM detected login screen (%s) — starting recovery", screen_type)
            await self._recover(page)
            return

        # Unknown state — log but don't panic
        logger.info("HealthLoop: VLM screen=%s, url=%s — assuming OK", screen_type, url[:80])

    async def _recover(self, page) -> None:
        """Attempt session recovery — 1 try. Fail → ERROR."""
        await self._sm.transition(PodState.RECOVERING, reason="Session expired, attempting re-login")
        success = await ai_login(
            page,
            self._sm,
            credentials=self._credentials,
        )
        if not success:
            logger.error("HealthLoop: recovery failed — pod in ERROR state")


class ChatMonitor:
    """Monitors Teams chat sidebar for new messages."""

    def __init__(
        self,
        browser_manager: BrowserManager,
        state_manager: PodStateManager,
    ) -> None:
        self._bm = browser_manager
        self._sm = state_manager
        self._task: asyncio.Task | None = None
        self._last_chat_state: str | None = None
        self._active_interval = 30
        self._idle_interval = 300

    async def start(self) -> None:
        self._task = asyncio.create_task(self._loop())
        logger.info("ChatMonitor started")

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    async def _loop(self) -> None:
        while True:
            try:
                interval = self._active_interval if self._last_chat_state else self._idle_interval

                # Only monitor when ACTIVE
                if self._sm.state != PodState.ACTIVE:
                    await asyncio.sleep(30)
                    continue

                await asyncio.sleep(interval)
                await self._check_for_new_messages()
            except asyncio.CancelledError:
                break
            except Exception:
                logger.exception("ChatMonitor check failed")

    async def _check_for_new_messages(self) -> None:
        client_id = self._sm.client_id
        context = self._bm.get_context(client_id)
        if not context or not context.pages:
            return

        page = context.pages[0]

        # Check chat sidebar for unread indicators
        try:
            # Teams v2: unread badges in chat list
            unread = page.locator('[data-tid="chat-list"] [data-tid="unread-badge"]')
            unread_count = await unread.count()

            if unread_count > 0:
                # Get chat names with unread messages
                items = page.locator('[data-tid="chat-list"] [role="listitem"], [data-tid="chat-list"] [role="treeitem"]')
                count = await items.count()

                for i in range(min(count, 10)):
                    item = items.nth(i)
                    try:
                        badge = item.locator('[data-tid="unread-badge"]')
                        if await badge.count() > 0:
                            label = await item.get_attribute("aria-label") or ""
                            logger.info("ChatMonitor: unread message in: %s", label[:100])

                            # Notify server about new message
                            await notify_session_state(
                                client_id=client_id,
                                connection_id=self._sm.connection_id,
                                state="NEW_MESSAGE",
                                mfa_message=label[:200],  # Reuse field for message preview
                            )
                            self._last_chat_state = "active"
                    except Exception:
                        continue

                return

        except Exception as e:
            logger.debug("ChatMonitor: DOM check failed: %s", e)

        self._last_chat_state = None
