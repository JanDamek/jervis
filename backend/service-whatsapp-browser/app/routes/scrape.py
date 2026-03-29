"""Scrape endpoints — on-demand VLM-based WhatsApp data extraction.

Scraping is triggered by Kotlin server during polling cycle.
Trigger endpoint is fire-and-forget — scrape runs in background,
results are read from MongoDB by subsequent polling cycles.
"""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter

from app.browser_manager import BrowserManager
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import WhatsAppScraper

logger = logging.getLogger("whatsapp-browser.scrape")

router = APIRouter(tags=["scrape"])


def create_scrape_router(
    scraper: WhatsAppScraper,
    browser_manager: BrowserManager,
    scrape_storage: ScrapeStorage,
) -> APIRouter:

    @router.post("/scrape/{client_id}/trigger")
    async def trigger_scrape(client_id: str) -> dict:
        """Fire-and-forget: trigger a scrape cycle in background.

        Returns immediately. If a scrape is already running, returns
        {"status": "already_running"}. Results end up in MongoDB and
        are read by subsequent polling cycles.
        """
        if scraper.is_scraping:
            return {"status": "already_running"}

        asyncio.create_task(_run_scrape(client_id))
        return {"status": "triggered"}

    async def _run_scrape(client_id: str) -> None:
        """Background scrape task."""
        try:
            result = await scraper.scrape_now(client_id)
            logger.info("Scrape completed for %s: %s", client_id, result.get("status"))
        except Exception:
            logger.exception("Background scrape failed for %s", client_id)

    @router.get("/scrape/{client_id}/latest")
    async def get_latest_scrape(client_id: str) -> dict:
        """Get the latest VLM scrape result (sidebar chat list)."""
        result = await scrape_storage.get_latest_result(client_id)
        if not result:
            return {"status": "no_data", "message": "No scrape results yet"}
        return result

    @router.get("/scrape/{client_id}/sidebar")
    async def get_sidebar(client_id: str) -> dict:
        """Get the last in-memory sidebar data."""
        data = scraper.get_last_sidebar()
        if not data:
            return {"status": "no_data", "message": "No sidebar data yet"}
        return data

    @router.post("/scrape/{client_id}/discover")
    async def discover_resources(client_id: str) -> dict:
        """Trigger resource discovery and return discovered chats/groups."""
        data = scraper.get_last_sidebar()
        if not data:
            return {"resources": []}

        chats = data.get("chats", [])
        resources = [
            {
                "id": f"chat_{c.get('name', '').lower().replace(' ', '_')[:50]}",
                "name": c.get("name", ""),
                "description": c.get("last_message"),
                "type": "group" if c.get("is_group") else "chat",
            }
            for c in chats if c.get("name")
        ]
        return {"resources": resources}

    return router
