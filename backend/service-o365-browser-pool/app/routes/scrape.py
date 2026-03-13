"""Scrape endpoints — manual trigger and result retrieval."""

from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException

from app.screen_scraper import ScreenScraper
from app.tab_manager import TabManager, TabType

logger = logging.getLogger("o365-browser-pool.scrape")

router = APIRouter(tags=["scrape"])


def create_scrape_router(
    screen_scraper: ScreenScraper,
    tab_manager: TabManager,
) -> APIRouter:

    @router.get("/scrape/{client_id}/{tab_type}")
    async def get_scrape_result(client_id: str, tab_type: str) -> dict:
        """Get last VLM analysis result for a tab."""
        try:
            tt = TabType(tab_type)
        except ValueError:
            raise HTTPException(
                status_code=400,
                detail=f"Invalid tab type: {tab_type}. Use: chat, calendar, email",
            )

        result = screen_scraper.get_last_result(client_id, tt)
        if not result:
            return {"client_id": client_id, "tab_type": tab_type, "data": None}
        return {"client_id": client_id, **result}

    @router.post("/scrape/{client_id}/{tab_type}")
    async def trigger_scrape(client_id: str, tab_type: str) -> dict:
        """Manually trigger a screenshot + VLM analysis for a tab."""
        try:
            tt = TabType(tab_type)
        except ValueError:
            raise HTTPException(
                status_code=400,
                detail=f"Invalid tab type: {tab_type}. Use: chat, calendar, email",
            )

        if not tab_manager.has_tabs(client_id):
            raise HTTPException(
                status_code=404,
                detail=f"No tabs set up for client '{client_id}'",
            )

        # Take screenshot
        screenshot = await tab_manager.screenshot_tab(client_id, tt)
        if not screenshot:
            raise HTTPException(
                status_code=500,
                detail=f"Screenshot failed for {client_id}/{tab_type}",
            )

        # Analyze with VLM
        from app.vlm_client import analyze_screenshot as vlm_analyze
        from app.screen_scraper import _PROMPTS, _parse_vlm_response

        prompt = _PROMPTS[tt]
        try:
            result_text = await vlm_analyze(screenshot, prompt)
        except Exception as e:
            raise HTTPException(
                status_code=502,
                detail=f"VLM analysis failed: {e}",
            )

        parsed = _parse_vlm_response(result_text)
        return {
            "client_id": client_id,
            "tab_type": tab_type,
            "data": parsed,
            "raw": result_text if not parsed else None,
        }

    @router.get("/scrape/{client_id}")
    async def get_all_scrape_results(client_id: str) -> dict:
        """Get last VLM analysis results for all tabs."""
        results = {}
        for tt in TabType:
            result = screen_scraper.get_last_result(client_id, tt)
            results[tt.value] = result
        return {"client_id": client_id, "tabs": results}

    @router.post("/scrape/{client_id}/{tab_type}/click")
    async def click_tab(
        client_id: str, tab_type: str, body: dict,
    ) -> dict:
        """Click at coordinates on a tab. Body: {"x": 100, "y": 200}"""
        try:
            tt = TabType(tab_type)
        except ValueError:
            raise HTTPException(status_code=400, detail=f"Invalid tab type: {tab_type}")

        x = body.get("x", 0)
        y = body.get("y", 0)
        success = await tab_manager.click_at(client_id, tt, x, y)
        return {"success": success, "x": x, "y": y}

    @router.post("/scrape/{client_id}/discover")
    async def discover_resources(client_id: str) -> dict:
        """Screenshot Teams sidebar and extract all visible chats/teams/channels via VLM.

        Returns structured list of resources for connection settings UI.
        """
        if not tab_manager.has_tabs(client_id):
            raise HTTPException(
                status_code=404,
                detail=f"No tabs set up for client '{client_id}'",
            )

        # Screenshot the chat tab (Teams sidebar shows chats/teams/channels)
        screenshot = await tab_manager.screenshot_tab(client_id, TabType.CHAT)
        if not screenshot:
            raise HTTPException(
                status_code=500,
                detail=f"Screenshot failed for {client_id}/chat",
            )

        from app.vlm_client import analyze_screenshot as vlm_analyze
        from app.screen_scraper import _parse_vlm_response

        discovery_prompt = """Analyze this Microsoft Teams screenshot. Extract ALL visible items:
1. Chats in the sidebar (name, last message preview)
2. Teams and their channels
3. Any other navigation items

Return JSON:
{
  "resources": [
    {"type": "chat", "id": "chat_<name_slug>", "name": "Chat Name", "description": "Last message preview"},
    {"type": "channel", "id": "channel_<team>_<channel>", "name": "Team / Channel", "description": "Channel description"},
    {"type": "team", "id": "team_<name_slug>", "name": "Team Name", "description": null}
  ]
}

List EVERY visible item. Use slugified names for IDs (lowercase, dashes)."""

        try:
            result_text = await vlm_analyze(screenshot, discovery_prompt)
        except Exception as e:
            raise HTTPException(
                status_code=502,
                detail=f"VLM discovery failed: {e}",
            )

        parsed = _parse_vlm_response(result_text)
        if not parsed:
            return {
                "client_id": client_id,
                "resources": [],
                "raw": result_text,
            }

        return {
            "client_id": client_id,
            "resources": parsed.get("resources", []),
        }

    @router.post("/scrape/{client_id}/discover/search")
    async def discover_search(client_id: str, body: dict) -> dict:
        """Type a query in Teams search bar, screenshot results, extract via VLM.

        Body: {"query": "search term"}
        """
        query = body.get("query", "")
        if not query:
            raise HTTPException(
                status_code=400,
                detail="Missing 'query' in request body",
            )

        if not tab_manager.has_tabs(client_id):
            raise HTTPException(
                status_code=404,
                detail=f"No tabs set up for client '{client_id}'",
            )

        page = await tab_manager.get_tab(client_id, TabType.CHAT)
        if not page:
            raise HTTPException(
                status_code=500,
                detail=f"Chat tab not available for {client_id}",
            )

        import asyncio

        # Click search bar and type query
        try:
            # Teams search bar — Ctrl+E or click the search box
            await page.keyboard.press("Control+e")
            await asyncio.sleep(1)
            await page.keyboard.type(query, delay=50)
            await asyncio.sleep(3)  # Wait for results to load
        except Exception as e:
            raise HTTPException(
                status_code=500,
                detail=f"Search interaction failed: {e}",
            )

        # Screenshot the search results
        screenshot = await tab_manager.screenshot_tab(client_id, TabType.CHAT)
        if not screenshot:
            raise HTTPException(
                status_code=500,
                detail=f"Screenshot failed after search for {client_id}",
            )

        # Clear search (Escape)
        try:
            await page.keyboard.press("Escape")
        except Exception:
            pass

        from app.vlm_client import analyze_screenshot as vlm_analyze
        from app.screen_scraper import _parse_vlm_response

        search_prompt = f"""Analyze this Microsoft Teams search results screenshot.
The search query was: "{query}"

Extract ALL visible search results:
- Chats, channels, teams, people, messages that match

Return JSON:
{{
  "query": "{query}",
  "resources": [
    {{"type": "chat|channel|team|person", "id": "<slugified_id>", "name": "Display Name", "description": "Context/preview"}}
  ]
}}"""

        try:
            result_text = await vlm_analyze(screenshot, search_prompt)
        except Exception as e:
            raise HTTPException(
                status_code=502,
                detail=f"VLM search analysis failed: {e}",
            )

        parsed = _parse_vlm_response(result_text)
        if not parsed:
            return {
                "client_id": client_id,
                "query": query,
                "resources": [],
                "raw": result_text,
            }

        return {
            "client_id": client_id,
            "query": query,
            "resources": parsed.get("resources", []),
        }

    return router
