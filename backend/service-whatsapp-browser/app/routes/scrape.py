"""Scrape endpoints — state-aware DOM scraping with optional VLM for attachments.

Scraping is triggered by Kotlin server during polling cycle. The trigger
request body carries the per-client tier policy (max_tier + processing_mode)
so the WhatsApp browser does not hardcode any defaults.
"""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.browser_manager import BrowserManager
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import WhatsAppScraper

logger = logging.getLogger("whatsapp-browser.scrape")

router = APIRouter(tags=["scrape"])


class ScrapeTriggerRequest(BaseModel):
    max_tier: str = Field(..., description="VLM tier policy: NONE/FREE/PAID/PREMIUM")
    processing_mode: str = Field(..., description="FOREGROUND or BACKGROUND")


def create_scrape_router(
    scraper: WhatsAppScraper,
    browser_manager: BrowserManager,
    scrape_storage: ScrapeStorage,
) -> APIRouter:

    async def trigger_scrape(client_id: str, body: ScrapeTriggerRequest) -> dict:
        """Fire-and-forget: trigger a scrape cycle in background.

        Tier policy must be supplied by the caller (Kotlin server). The
        WhatsApp browser does not hardcode any defaults.
        """
        if scraper.is_scraping:
            return {"status": "already_running"}

        asyncio.create_task(
            _run_scrape(client_id, body.max_tier, body.processing_mode),
        )
        return {"status": "triggered"}

    async def _run_scrape(client_id: str, max_tier: str, processing_mode: str) -> None:
        try:
            result = await scraper.scrape_now(
                client_id,
                max_tier=max_tier,
                processing_mode=processing_mode,
            )
            logger.info("Scrape completed for %s: %s", client_id, result.get("status"))
        except Exception:
            logger.exception("Background scrape failed for %s", client_id)

    async def get_latest_scrape(client_id: str) -> dict:
        """Get the latest persisted scrape state for a client."""
        state = await scrape_storage.get_sidebar_state(client_id)
        if not state:
            return {"status": "no_data", "message": "No scrape state yet"}
        return {
            "status": "ok",
            "last_scraped_at": state.get("last_scraped_at"),
            "chat_count": len(state.get("chats", {})),
        }

    return router
