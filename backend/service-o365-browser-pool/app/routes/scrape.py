"""Scrape endpoints — manual trigger and result retrieval."""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter, HTTPException

from app.browser_manager import BrowserManager
from app.scrape_storage import ScrapeStorage
from app.screen_scraper import ScreenScraper
from app.tab_manager import TabManager, TabType
from app.teams_crawler import TeamsCrawler

logger = logging.getLogger("o365-browser-pool.scrape")

router = APIRouter(tags=["scrape"])


def create_scrape_router(
    screen_scraper: ScreenScraper,
    tab_manager: TabManager,
    teams_crawler: TeamsCrawler | None = None,
    browser_manager: BrowserManager | None = None,
    scrape_storage: ScrapeStorage | None = None,
) -> APIRouter:

    # ── Teams crawler endpoints (registered FIRST to avoid {tab_type} catch-all) ──

    _crawl_tasks: dict[str, asyncio.Task] = {}

    @router.post("/scrape/{client_id}/crawl")
    async def trigger_crawl(client_id: str, body: dict | None = None) -> dict:
        """Start systematic crawl of all Teams chats.

        Body (optional): {"max_chats": 50, "max_scrolls_per_chat": 5}

        Returns immediately — crawl runs in background.
        Use GET /scrape/{client_id}/crawl to check status.
        """
        if not teams_crawler or not browser_manager:
            raise HTTPException(status_code=501, detail="Crawler not available")

        if client_id in _crawl_tasks and not _crawl_tasks[client_id].done():
            return {"client_id": client_id, "status": "already_running"}

        # Get the chat tab page
        page = await tab_manager.get_tab(client_id, TabType.CHAT)
        if not page:
            # Fallback: get any page from the browser context
            context = browser_manager.get_context(client_id)
            if not context or not context.pages:
                raise HTTPException(
                    status_code=404,
                    detail=f"No browser page for client '{client_id}'",
                )
            page = context.pages[0]

        body = body or {}
        max_chats = body.get("max_chats", 50)
        max_scrolls = body.get("max_scrolls_per_chat", 5)
        connection_id = screen_scraper._connection_ids.get(client_id, client_id)

        async def _run_crawl():
            try:
                return await teams_crawler.crawl_all_chats(
                    page, client_id, connection_id, scrape_storage,
                    max_chats=max_chats,
                    max_scrolls_per_chat=max_scrolls,
                )
            except Exception as e:
                logger.error("Crawl task failed for %s: %s", client_id, e)

        task = asyncio.create_task(_run_crawl())
        _crawl_tasks[client_id] = task

        return {
            "client_id": client_id,
            "status": "started",
            "max_chats": max_chats,
            "max_scrolls_per_chat": max_scrolls,
        }

    @router.get("/scrape/{client_id}/crawl")
    async def get_crawl_status(client_id: str) -> dict:
        """Get crawl status/result for a client."""
        task = _crawl_tasks.get(client_id)
        if not task:
            return {"client_id": client_id, "status": "not_started"}

        if not task.done():
            return {"client_id": client_id, "status": "running"}

        # Task completed
        try:
            result = task.result()
            if result:
                return {
                    "client_id": client_id,
                    "status": "completed",
                    "chats_found": result.chats_found,
                    "chats_crawled": result.chats_crawled,
                    "messages_extracted": result.messages_extracted,
                    "errors": result.errors[:10],
                }
        except Exception as e:
            return {"client_id": client_id, "status": "error", "error": str(e)}

        return {"client_id": client_id, "status": "completed"}

    # ── Screenshot endpoint (registered before {tab_type} catch-all) ──

    @router.get("/scrape/{client_id}/screenshot/{tab_type}")
    async def get_screenshot_by_tab(client_id: str, tab_type: str):
        """Get raw screenshot JPEG for debugging. Path: /scrape/{id}/screenshot/{tab}"""
        from fastapi.responses import Response as RawResponse
        try:
            tt = TabType(tab_type)
        except ValueError:
            raise HTTPException(status_code=400, detail=f"Invalid tab type: {tab_type}")

        screenshot = await tab_manager.screenshot_tab(client_id, tt)
        if not screenshot or len(screenshot) < 1000:
            raise HTTPException(
                status_code=500,
                detail=f"Screenshot failed or too small ({len(screenshot) if screenshot else 0} bytes)",
            )
        return RawResponse(content=screenshot, media_type="image/jpeg")

    # ── Tab-specific endpoints (catch-all {tab_type}) ──

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

    @router.get("/scrape/{client_id}/{tab_type}/screenshot")
    async def get_screenshot(client_id: str, tab_type: str):
        """Get raw screenshot JPEG for debugging."""
        from fastapi.responses import Response as RawResponse
        try:
            tt = TabType(tab_type)
        except ValueError:
            raise HTTPException(status_code=400, detail=f"Invalid tab type: {tab_type}")

        screenshot = await tab_manager.screenshot_tab(client_id, tt)
        if not screenshot or len(screenshot) < 1000:
            raise HTTPException(
                status_code=500,
                detail=f"Screenshot failed or too small ({len(screenshot) if screenshot else 0} bytes)",
            )
        if not screenshot[:2] == b"\xff\xd8":
            raise HTTPException(
                status_code=500,
                detail="Screenshot is not valid JPEG data",
            )
        return RawResponse(content=screenshot, media_type="image/jpeg")

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

    @router.post("/scrape/{client_id}/backfill")
    async def backfill_channel(client_id: str, body: dict) -> dict:
        """Navigate to a channel and scroll back to capture historical messages.

        Body: {"channel_name": "Team / Channel", "max_scrolls": 20}

        The scraper navigates to the channel, scrolls up repeatedly,
        takes screenshots, and extracts messages via VLM. Messages are
        stored in o365_scrape_messages with state=NEW for the polling handler.
        """
        channel_name = body.get("channel_name", "")
        if not channel_name:
            raise HTTPException(
                status_code=400,
                detail="Missing 'channel_name' in request body",
            )

        max_scrolls = body.get("max_scrolls", 20)

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
        from app.vlm_client import analyze_screenshot as vlm_analyze
        from app.screen_scraper import _PROMPTS, _parse_vlm_response

        total_messages = 0
        scrolls_done = 0

        # Navigate to the channel using Teams search
        try:
            await page.keyboard.press("Control+e")
            await asyncio.sleep(1)
            await page.keyboard.type(channel_name, delay=50)
            await asyncio.sleep(3)
            await page.keyboard.press("Enter")
            await asyncio.sleep(3)
        except Exception as e:
            raise HTTPException(
                status_code=500,
                detail=f"Navigation to channel failed: {e}",
            )

        # Scroll up and scrape repeatedly
        for i in range(max_scrolls):
            try:
                # Screenshot current view
                screenshot = await tab_manager.screenshot_tab(client_id, TabType.CHAT)
                if not screenshot:
                    break

                # Extract messages via VLM
                result_text = await vlm_analyze(screenshot, _PROMPTS[TabType.CHAT])
                parsed = _parse_vlm_response(result_text)
                if not parsed:
                    break

                # Check for login page
                if parsed.get("login_page") or parsed.get("sign_in"):
                    logger.warning("Login page detected during backfill for %s", client_id)
                    break

                # Store messages
                open_conv = parsed.get("open_conversation", {})
                if open_conv and open_conv.get("messages"):
                    msgs = [
                        {**m, "chat_name": open_conv.get("name", channel_name)}
                        for m in open_conv["messages"]
                    ]
                    stored = await screen_scraper._storage.store_messages(
                        client_id,
                        screen_scraper._connection_ids.get(client_id, client_id),
                        msgs,
                        "chat",
                    )
                    total_messages += stored
                else:
                    # No messages found — likely at the top
                    break

                scrolls_done += 1

                # Scroll up
                await page.mouse.wheel(0, -3000)
                await asyncio.sleep(2)

            except Exception as e:
                logger.warning(
                    "Backfill scroll %d failed for %s: %s",
                    i, client_id, e,
                )
                break

        logger.info(
            "Backfill complete for %s/%s: %d scrolls, %d messages",
            client_id, channel_name, scrolls_done, total_messages,
        )

        return {
            "client_id": client_id,
            "channel_name": channel_name,
            "scrolls_done": scrolls_done,
            "messages_stored": total_messages,
        }

    return router
