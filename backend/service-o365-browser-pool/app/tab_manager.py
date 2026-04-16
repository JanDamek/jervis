"""Tab registry — dumb name→Page mapping.

The pod holds zero business logic about which tab belongs where. The
LangGraph agent opens tabs (`open_tab` tool) with a chosen name, the
registry keeps references so subsequent tools (`inspect_dom`, `switch_tab`)
can resolve them.

Design invariants:
- No URL hardcoding, no per-capability mapping, no login-redirect detection,
  no retry loops. The agent drives all of that through its tools + prompt.
- `register(client_id, name, page)` — explicit by agent
- `get(client_id, name)` — resolve by name
- `list(client_id)` — inspect currently tracked tabs (name + url + closed?)
- `remove_client(client_id)` — cleanup on disconnect
- New pages that appear on the BrowserContext (agent clicked a link) get
  auto-registered under a synthetic name `tab-N` so they're not lost; the
  agent can rename them via `register` again.
"""

from __future__ import annotations

import logging

from playwright.async_api import BrowserContext, Page

logger = logging.getLogger("o365-browser-pool.tabs")


class TabRegistry:
    """Name→Page map per client. Infrastructure only, no business logic."""

    def __init__(self) -> None:
        self._tabs: dict[str, dict[str, Page]] = {}
        self._auto_counter: dict[str, int] = {}

    def attach_context(self, client_id: str, context: BrowserContext) -> None:
        """Auto-register existing and future pages so the agent never loses
        track of a tab it opened indirectly (via click on a link, etc.).
        Agent can always re-register under a semantic name later."""
        self._tabs.setdefault(client_id, {})
        self._auto_counter.setdefault(client_id, 0)

        # Register already-open pages.
        for page in context.pages:
            if not page.is_closed():
                self._auto_register(client_id, page)

        # Future pages: listen on 'page' event.
        context.on("page", lambda page: self._auto_register(client_id, page))

    def _auto_register(self, client_id: str, page: Page) -> None:
        tabs = self._tabs.setdefault(client_id, {})
        # Skip if already registered.
        if any(p is page for p in tabs.values()):
            return
        self._auto_counter[client_id] = self._auto_counter.get(client_id, 0) + 1
        name = f"tab-{self._auto_counter[client_id]}"
        tabs[name] = page
        logger.info("Auto-registered page as %r for %s (url=%s)", name, client_id, (page.url or "")[:80])

    def register(self, client_id: str, name: str, page: Page) -> None:
        """Explicit registration under a semantic name. Overwrites any prior
        page with that name."""
        self._tabs.setdefault(client_id, {})[name] = page
        logger.info("Registered page as %r for %s (url=%s)", name, client_id, (page.url or "")[:80])

    def get(self, client_id: str, name: str) -> Page | None:
        page = self._tabs.get(client_id, {}).get(name)
        if page is None or page.is_closed():
            return None
        return page

    def list(self, client_id: str) -> list[dict]:
        """Return {name, url, closed} for every tab tracked for this client."""
        return [
            {"name": name, "url": (page.url or ""), "closed": page.is_closed()}
            for name, page in self._tabs.get(client_id, {}).items()
        ]

    def remove(self, client_id: str, name: str) -> bool:
        tabs = self._tabs.get(client_id) or {}
        return tabs.pop(name, None) is not None

    def remove_client(self, client_id: str) -> None:
        self._tabs.pop(client_id, None)
        self._auto_counter.pop(client_id, None)
