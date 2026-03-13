"""Screen scraper — periodic VLM-based content extraction from browser tabs.

Takes screenshots of each tab at configurable intervals, sends to VLM
for analysis, and posts structured results to Kotlin server.

Intervals are adaptive: chat tab speeds up when active conversation detected.
"""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone

import httpx

from app.browser_manager import BrowserManager
from app.config import settings
from app.models import SessionState
from app.tab_manager import TabManager, TabType
from app.vlm_client import analyze_screenshot

logger = logging.getLogger("o365-browser-pool.scraper")

# VLM prompts per tab type
_PROMPTS = {
    TabType.CHAT: """Analyze this Microsoft Teams screenshot. Extract:
1. List of visible chats/conversations in the sidebar (name, last message preview, unread count)
2. If a conversation is open: all visible messages (sender, timestamp, content)
3. Any notifications or badges

Return JSON:
{
  "chats": [{"name": "...", "preview": "...", "unread": 0}],
  "open_conversation": {
    "name": "...",
    "messages": [{"sender": "...", "time": "...", "content": "..."}]
  },
  "has_unread": false,
  "active_conversation": false
}""",

    TabType.CALENDAR: """Analyze this Outlook Calendar screenshot. Extract:
1. Current view (day/week/month) and visible date range
2. All visible calendar events (title, time, duration, attendees if visible)
3. Any pending meeting notifications

Return JSON:
{
  "view": "week",
  "date_range": "...",
  "events": [{"title": "...", "start": "...", "end": "...", "attendees": []}],
  "notifications": []
}""",

    TabType.EMAIL: """Analyze this Outlook Email screenshot. Extract:
1. Folder currently viewed (Inbox, Sent, etc.)
2. List of visible emails (sender, subject, preview, timestamp, read/unread)
3. If an email is open: full content summary
4. Any notifications or badges

Return JSON:
{
  "folder": "Inbox",
  "emails": [{"sender": "...", "subject": "...", "preview": "...", "time": "...", "unread": true}],
  "open_email": null,
  "unread_count": 0
}""",
}

# Default intervals (seconds) — can be overridden by adaptive logic
_DEFAULT_INTERVALS = {
    TabType.CHAT: settings.scraper_chat_interval,
    TabType.CALENDAR: settings.scraper_calendar_interval,
    TabType.EMAIL: settings.scraper_email_interval,
}

# Faster interval when active conversation detected
_ACTIVE_CHAT_INTERVAL = 60  # 1 min during active conversation


class ScreenScraper:
    """Periodically screenshots browser tabs and analyzes with VLM."""

    def __init__(
        self,
        browser_manager: BrowserManager,
        tab_manager: TabManager,
    ) -> None:
        self._bm = browser_manager
        self._tm = tab_manager
        self._tasks: dict[str, asyncio.Task] = {}
        self._intervals: dict[str, dict[TabType, int]] = {}
        self._last_results: dict[str, dict[TabType, dict]] = {}
        self._running = False

    async def start(self) -> None:
        self._running = True
        logger.info("ScreenScraper started")

    async def stop(self) -> None:
        self._running = False
        for task in self._tasks.values():
            task.cancel()
        for task in self._tasks.values():
            try:
                await task
            except asyncio.CancelledError:
                pass
        self._tasks.clear()
        logger.info("ScreenScraper stopped")

    async def start_scraping(self, client_id: str) -> None:
        """Start scraping loop for a client after successful login."""
        if client_id in self._tasks:
            return

        # Initialize intervals
        self._intervals[client_id] = dict(_DEFAULT_INTERVALS)
        self._last_results[client_id] = {}

        task = asyncio.create_task(self._scrape_loop(client_id))
        self._tasks[client_id] = task
        logger.info("Started scraping for %s", client_id)

    def stop_scraping(self, client_id: str) -> None:
        """Stop scraping loop for a client."""
        task = self._tasks.pop(client_id, None)
        if task:
            task.cancel()
        self._intervals.pop(client_id, None)
        self._last_results.pop(client_id, None)
        logger.info("Stopped scraping for %s", client_id)

    def get_last_result(
        self, client_id: str, tab_type: TabType,
    ) -> dict | None:
        """Get the last VLM analysis result for a tab."""
        return self._last_results.get(client_id, {}).get(tab_type)

    async def _scrape_loop(self, client_id: str) -> None:
        """Main scraping loop — only scrapes enabled tabs at their intervals."""
        last_scrape: dict[TabType, float] = {t: 0 for t in TabType}

        # Wait for tabs to be set up
        await asyncio.sleep(10)

        while self._running:
            try:
                state = self._bm.get_state(client_id)
                if state != SessionState.ACTIVE:
                    await asyncio.sleep(30)
                    continue

                now = asyncio.get_event_loop().time()
                intervals = self._intervals.get(client_id, _DEFAULT_INTERVALS)
                enabled_tabs = self._tm.get_enabled_tabs(client_id)

                for tab_type in enabled_tabs:
                    elapsed = now - last_scrape[tab_type]
                    interval = intervals.get(tab_type, _DEFAULT_INTERVALS[tab_type])

                    if elapsed >= interval:
                        await self._scrape_tab(client_id, tab_type)
                        last_scrape[tab_type] = asyncio.get_event_loop().time()

                # Sleep until next tab needs scraping
                await asyncio.sleep(30)

            except asyncio.CancelledError:
                break
            except Exception:
                logger.exception("Scrape loop error for %s", client_id)
                await asyncio.sleep(60)

    async def _scrape_tab(self, client_id: str, tab_type: TabType) -> None:
        """Screenshot a tab and analyze with VLM."""
        screenshot = await self._tm.screenshot_tab(client_id, tab_type)
        if not screenshot:
            logger.debug("No screenshot for %s/%s", client_id, tab_type.value)
            return

        prompt = _PROMPTS[tab_type]

        try:
            result_text = await analyze_screenshot(screenshot, prompt)
        except Exception as e:
            logger.warning(
                "VLM analysis failed for %s/%s: %s",
                client_id, tab_type.value, e,
            )
            return

        # Parse VLM response
        parsed = _parse_vlm_response(result_text)
        if not parsed:
            logger.warning(
                "Failed to parse VLM response for %s/%s",
                client_id, tab_type.value,
            )
            return

        # Store result
        if client_id not in self._last_results:
            self._last_results[client_id] = {}
        self._last_results[client_id][tab_type] = {
            "data": parsed,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "tab_type": tab_type.value,
        }

        # Adaptive interval for chat — speed up during active conversation
        if tab_type == TabType.CHAT:
            active = parsed.get("active_conversation", False)
            has_unread = parsed.get("has_unread", False)
            intervals = self._intervals.get(client_id, {})
            if active or has_unread:
                intervals[TabType.CHAT] = _ACTIVE_CHAT_INTERVAL
                logger.info(
                    "Chat active for %s — interval → %ds",
                    client_id, _ACTIVE_CHAT_INTERVAL,
                )
            else:
                intervals[TabType.CHAT] = _DEFAULT_INTERVALS[TabType.CHAT]

        # Post result to Kotlin server
        await self._post_result(client_id, tab_type, parsed)

        logger.info(
            "Scraped %s/%s — %d items",
            client_id, tab_type.value,
            _count_items(tab_type, parsed),
        )

    async def _post_result(
        self,
        client_id: str,
        tab_type: TabType,
        data: dict,
    ) -> None:
        """Post scraped data to Kotlin server internal API."""
        try:
            async with httpx.AsyncClient(timeout=30) as client:
                await client.post(
                    f"{settings.kotlin_server_url}/internal/o365-scrape",
                    json={
                        "clientId": client_id,
                        "tabType": tab_type.value,
                        "data": data,
                        "scrapedAt": datetime.now(timezone.utc).isoformat(),
                    },
                )
        except Exception as e:
            logger.debug(
                "Failed to post scrape result for %s/%s: %s",
                client_id, tab_type.value, e,
            )


def _parse_vlm_response(text: str) -> dict | None:
    """Extract JSON from VLM response (may contain markdown fences)."""
    # Strip markdown code fences
    cleaned = text.strip()
    if cleaned.startswith("```"):
        lines = cleaned.split("\n")
        # Remove first line (```json) and last line (```)
        lines = [l for l in lines if not l.strip().startswith("```")]
        cleaned = "\n".join(lines)

    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        # Try to find JSON object in the text
        start = cleaned.find("{")
        end = cleaned.rfind("}")
        if start >= 0 and end > start:
            try:
                return json.loads(cleaned[start : end + 1])
            except json.JSONDecodeError:
                pass
    return None


def _count_items(tab_type: TabType, data: dict) -> int:
    """Count items in parsed data for logging."""
    if tab_type == TabType.CHAT:
        return len(data.get("chats", []))
    elif tab_type == TabType.CALENDAR:
        return len(data.get("events", []))
    elif tab_type == TabType.EMAIL:
        return len(data.get("emails", []))
    return 0
