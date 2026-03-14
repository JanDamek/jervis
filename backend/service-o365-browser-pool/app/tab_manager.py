"""Tab manager — maintains browser tabs based on connection capabilities.

Only opens tabs for capabilities the connection actually has:
- CHAT_READ → Teams Chat tab
- EMAIL_READ → Outlook Email tab
- CALENDAR_READ → Outlook Calendar tab

Auto-detects consumer (*.live.com) vs business (*.office.com) URLs.
"""

from __future__ import annotations

import asyncio
import logging
from enum import Enum

from playwright.async_api import BrowserContext, Page

from app.token_extractor import TokenExtractor

logger = logging.getLogger("o365-browser-pool.tabs")


class TabType(str, Enum):
    CHAT = "chat"
    CALENDAR = "calendar"
    EMAIL = "email"


# Capability → TabType mapping
CAPABILITY_TO_TAB: dict[str, TabType] = {
    "CHAT": TabType.CHAT,
    "CHAT_READ": TabType.CHAT,
    "CHAT_SEND": TabType.CHAT,
    "EMAIL": TabType.EMAIL,
    "EMAIL_READ": TabType.EMAIL,
    "EMAIL_SEND": TabType.EMAIL,
    "CALENDAR": TabType.CALENDAR,
    "CALENDAR_READ": TabType.CALENDAR,
    "CALENDAR_WRITE": TabType.CALENDAR,
}

# O365 web URLs per account type
_BUSINESS_URLS = {
    TabType.CHAT: "https://teams.microsoft.com",
    TabType.CALENDAR: "https://outlook.office.com/calendar",
    TabType.EMAIL: "https://outlook.office.com/mail",
}

_CONSUMER_URLS = {
    TabType.CHAT: "https://teams.live.com",
    TabType.CALENDAR: "https://outlook.live.com/calendar",
    TabType.EMAIL: "https://outlook.live.com/mail",
}


def _detect_urls(context: BrowserContext) -> dict[TabType, str]:
    """Detect business vs consumer account from current page URLs."""
    for page in context.pages:
        url = page.url or ""
        if "live.com" in url:
            return dict(_CONSUMER_URLS)
    return dict(_BUSINESS_URLS)


def capabilities_to_tabs(capabilities: list[str]) -> set[TabType]:
    """Convert connection capabilities to required tab types."""
    tabs = set()
    for cap in capabilities:
        tab = CAPABILITY_TO_TAB.get(cap)
        if tab:
            tabs.add(tab)
    # If no capabilities specified, open all tabs (backward compat)
    return tabs if tabs else set(TabType)


class TabManager:
    """Manages browser tabs per client session, based on capabilities."""

    def __init__(self, token_extractor: TokenExtractor) -> None:
        self._tabs: dict[str, dict[TabType, Page]] = {}
        self._enabled_tabs: dict[str, set[TabType]] = {}
        self._token_extractor = token_extractor

    async def setup_tabs(
        self,
        client_id: str,
        context: BrowserContext,
        capabilities: list[str] | None = None,
    ) -> None:
        """Open tabs based on connection capabilities.

        Detects consumer vs business account and uses appropriate URLs.
        Only opens tabs for enabled capabilities.
        """
        tab_urls = _detect_urls(context)
        enabled = capabilities_to_tabs(capabilities or [])
        self._enabled_tabs[client_id] = enabled
        tabs = self._tabs.get(client_id, {})

        # Reuse existing pages — match them to tabs by current URL first
        existing_pages = [p for p in context.pages if not p.is_closed()]
        unassigned_pages = list(existing_pages)

        # Phase 1: Match existing pages to tabs by URL domain
        # This ensures the logged-in Teams page stays as the chat tab
        for tab_type in enabled:
            if tab_type in tabs and not tabs[tab_type].is_closed():
                continue

            target_url = tab_urls[tab_type]
            target_domain = target_url.split("//")[1].split("/")[0]

            # Find an existing page already on the right domain
            matched_page = None
            for p in unassigned_pages:
                page_url = p.url or ""
                if target_domain in page_url:
                    matched_page = p
                    break

            if matched_page:
                unassigned_pages.remove(matched_page)
                tabs[tab_type] = matched_page
                await self._token_extractor.setup_interception(client_id, matched_page)
                logger.info(
                    "Tab %s matched existing page at %s for %s",
                    tab_type.value, matched_page.url[:60], client_id,
                )

        # Phase 2: Assign remaining tabs — reuse unassigned pages or create new
        for tab_type in enabled:
            if tab_type in tabs and not tabs[tab_type].is_closed():
                continue

            if unassigned_pages:
                page = unassigned_pages.pop(0)
            else:
                page = await context.new_page()

            await self._token_extractor.setup_interception(client_id, page)

            # Navigate to the correct URL
            current_url = page.url or ""
            target_url = tab_urls[tab_type]
            target_domain = target_url.split("//")[1].split("/")[0]

            if target_domain not in current_url:
                try:
                    await page.goto(
                        target_url,
                        wait_until="domcontentloaded",
                        timeout=30000,
                    )
                    # Check if we ended up on a login page instead
                    final_url = page.url or ""
                    is_login_redirect = (
                        "login.microsoftonline.com" in final_url
                        or "login.live.com" in final_url
                    )

                    # Also check for Outlook/Microsoft marketing page
                    # (consumer accounts without Outlook access land here)
                    # The marketing page has "Sign in" links and "Buy now" buttons.
                    # Wait a bit for the page JS to render before checking.
                    if not is_login_redirect:
                        await asyncio.sleep(3)
                        # Check if the page is a marketing/promo page instead of
                        # the actual Outlook app. Marketing pages don't have the
                        # Outlook app shell elements.
                        try:
                            is_app_loaded = False
                            app_indicator = page.locator(
                                # Outlook app indicators
                                '[data-app-section], '
                                '#LeftRail, '
                                'div[role="navigation"], '
                                '#MainModule, '
                                'div[data-testid="reading-pane"], '
                                'div[data-testid="folder-list"]'
                            ).first
                            try:
                                is_app_loaded = await app_indicator.is_visible(timeout=3000)
                            except Exception:
                                pass

                            if not is_app_loaded:
                                # Not an Outlook app page — likely marketing page
                                is_login_redirect = True
                                logger.info(
                                    "Tab %s shows marketing/promo page (no app shell) for %s",
                                    tab_type.value, client_id,
                                )
                        except Exception:
                            pass

                    if is_login_redirect:
                        logger.warning(
                            "Tab %s redirected to login/marketing for %s — "
                            "service not available with current session",
                            tab_type.value, client_id,
                        )
                        # Close the page — this service is not accessible
                        try:
                            await page.close()
                        except Exception:
                            pass
                        continue
                    logger.info(
                        "Tab %s navigated to %s for %s",
                        tab_type.value, target_url, client_id,
                    )
                except Exception as e:
                    logger.warning(
                        "Tab %s navigation failed for %s: %s",
                        tab_type.value, client_id, e,
                    )

            tabs[tab_type] = page

        # Close any leftover pages (not assigned to a tab)
        for extra_page in unassigned_pages:
            try:
                await extra_page.close()
            except Exception:
                pass

        self._tabs[client_id] = tabs
        logger.info(
            "Tabs set up for %s: %s",
            client_id, [t.value for t in enabled],
        )

    def get_enabled_tabs(self, client_id: str) -> set[TabType]:
        """Get which tabs are enabled for a client."""
        return self._enabled_tabs.get(client_id, set())

    async def get_tab(self, client_id: str, tab_type: TabType) -> Page | None:
        """Get a specific tab page."""
        tabs = self._tabs.get(client_id, {})
        page = tabs.get(tab_type)
        if page and not page.is_closed():
            return page
        return None

    async def screenshot_tab(
        self,
        client_id: str,
        tab_type: TabType,
    ) -> bytes | None:
        """Take a screenshot of a specific tab.

        Returns JPEG bytes or None if tab is not available.
        """
        page = await self.get_tab(client_id, tab_type)
        if not page:
            return None

        try:
            await page.bring_to_front()
            return await page.screenshot(
                type="jpeg",
                quality=80,
                full_page=False,
            )
        except Exception as e:
            logger.warning(
                "Screenshot failed for %s/%s: %s",
                client_id, tab_type.value, e,
            )
            return None

    async def click_at(
        self,
        client_id: str,
        tab_type: TabType,
        x: int,
        y: int,
    ) -> bool:
        """Click at specific coordinates on a tab."""
        page = await self.get_tab(client_id, tab_type)
        if not page:
            return False
        try:
            await page.mouse.click(x, y)
            return True
        except Exception as e:
            logger.warning("Click failed for %s/%s: %s", client_id, tab_type.value, e)
            return False

    async def navigate_tab(
        self,
        client_id: str,
        tab_type: TabType,
        url: str,
    ) -> bool:
        """Navigate a tab to a specific URL."""
        page = await self.get_tab(client_id, tab_type)
        if not page:
            return False
        try:
            await page.goto(url, wait_until="domcontentloaded", timeout=30000)
            return True
        except Exception as e:
            logger.warning(
                "Navigation failed for %s/%s: %s",
                client_id, tab_type.value, e,
            )
            return False

    def remove_client(self, client_id: str) -> None:
        """Clean up tabs for a client."""
        self._tabs.pop(client_id, None)
        self._enabled_tabs.pop(client_id, None)

    def has_tabs(self, client_id: str) -> bool:
        """Check if tabs are set up for a client."""
        return client_id in self._tabs and len(self._tabs[client_id]) > 0
