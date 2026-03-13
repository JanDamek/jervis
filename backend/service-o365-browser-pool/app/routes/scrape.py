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

    return router
