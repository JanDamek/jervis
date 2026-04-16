"""Scrape endpoints — diagnostic façade over the agent.

The LangGraph agent runs autonomously and composes its own scraping via
storage primitives. These endpoints exist only for diagnostics:

- `GET /scrape/{client_id}/tabs` — list currently tracked tabs.
- `GET /scrape/{client_id}/screenshot/{tab_name}` — raw JPEG of a tab.
- `GET /scrape/{client_id}/inspect/{tab_name}?selector=...` — one-shot
  generic scoped DOM query (debug helper only).

No `/crawl`, no `/force-*`, no capability→URL map, no `TabType` enum.
All of those are anti-patterns per docs/teams-pod-agent.md §15.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException, Query

from app.agent import _dom_query
from app.browser_manager import BrowserManager
from app.scrape_storage import ScrapeStorage
from app.tab_manager import TabRegistry

logger = logging.getLogger("o365-browser-pool.scrape")

router = APIRouter(tags=["scrape"])


def create_scrape_router(
    tab_registry: TabRegistry,
    browser_manager: BrowserManager | None = None,
    scrape_storage: ScrapeStorage | None = None,
) -> APIRouter:

    @router.get("/scrape/{client_id}/tabs")
    async def list_tabs(client_id: str) -> dict:
        """List all tabs currently tracked by the agent."""
        return {"client_id": client_id, "tabs": tab_registry.list(client_id)}

    @router.get("/scrape/{client_id}/screenshot/{tab_name}")
    async def get_screenshot_by_tab(client_id: str, tab_name: str):
        """Raw JPEG screenshot of a registered tab — debug only."""
        from fastapi.responses import Response as RawResponse
        page = tab_registry.get(client_id, tab_name)
        if page is None:
            raise HTTPException(status_code=404, detail=f"No tab named {tab_name!r}")
        try:
            await page.bring_to_front()
            screenshot = await page.screenshot(type="jpeg", quality=80, full_page=False)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Screenshot failed: {e}") from e
        if not screenshot:
            raise HTTPException(status_code=500, detail="Screenshot empty")
        return RawResponse(content=screenshot, media_type="image/jpeg")

    @router.get("/scrape/{client_id}/inspect/{tab_name}")
    async def inspect_dom_debug(
        client_id: str,
        tab_name: str,
        selector: str = Query(..., description="CSS selector"),
        attrs: str = Query("", description="Comma-separated attribute names"),
        max_matches: int = Query(50),
    ) -> dict:
        """Generic DOM query for debugging. Same helper the agent uses."""
        page = tab_registry.get(client_id, tab_name)
        if page is None:
            raise HTTPException(
                status_code=404,
                detail=f"No tab named {tab_name!r} for client '{client_id}'",
            )
        await page.bring_to_front()
        attr_list = [a.strip() for a in attrs.split(",") if a.strip()]
        return await _dom_query.query(
            page, selector=selector, attrs=attr_list,
            text=True, max_matches=max_matches,
        )

    return router
