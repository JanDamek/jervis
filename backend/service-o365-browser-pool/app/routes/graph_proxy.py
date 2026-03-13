"""Graph API proxy – executes Graph API calls through the Playwright browser.

All requests are made FROM the browser (same IP, device fingerprint),
which is required for corporate environments with Conditional Access policies.
The captured Bearer token is used in the Authorization header.
"""

from __future__ import annotations

import json
import logging

from fastapi import APIRouter, HTTPException, Query

from app.browser_manager import BrowserManager
from app.token_extractor import TokenExtractor

logger = logging.getLogger("o365-browser-pool.graph-proxy")

GRAPH_BASE = "https://graph.microsoft.com/v1.0"

router = APIRouter(tags=["graph-proxy"])


def create_graph_proxy_router(
    browser_manager: BrowserManager,
    token_extractor: TokenExtractor,
) -> APIRouter:

    async def _get_page(client_id: str):
        """Get an active page from the browser context, or create one."""
        context = browser_manager.get_context(client_id)
        if not context:
            raise HTTPException(
                status_code=404,
                detail=f"No browser session for client '{client_id}'",
            )

        pages = context.pages
        if pages:
            return pages[0]

        # No pages — create one
        page = await context.new_page()
        await token_extractor.setup_interception(client_id, page)
        return page

    async def _graph_fetch(client_id: str, url: str) -> dict:
        """Execute a Graph API fetch() from within the browser page."""
        token_info = token_extractor.get_graph_token(client_id)
        if not token_info:
            raise HTTPException(
                status_code=401,
                detail=f"No valid Graph token for client '{client_id}'. Re-login required.",
            )

        page = await _get_page(client_id)

        try:
            result = await page.evaluate(
                """async ([url, token]) => {
                    const response = await fetch(url, {
                        headers: {
                            'Authorization': 'Bearer ' + token,
                            'Content-Type': 'application/json'
                        }
                    });
                    const status = response.status;
                    const text = await response.text();
                    return { status, body: text };
                }""",
                [url, token_info.token],
            )
        except Exception as e:
            logger.error("Graph fetch failed for %s: %s", client_id, e)
            raise HTTPException(status_code=502, detail=f"Browser fetch failed: {e}")

        status = result.get("status", 500)
        body_text = result.get("body", "")

        if status >= 400:
            logger.warning(
                "Graph API %s returned %d for client %s",
                url, status, client_id,
            )
            raise HTTPException(status_code=status, detail=body_text)

        try:
            return json.loads(body_text)
        except json.JSONDecodeError:
            return {"raw": body_text}

    # ── Chat endpoints ───────────────────────────────────────────────

    @router.get("/graph/{client_id}/me/chats")
    async def list_chats(client_id: str, top: int = Query(20)):
        """List user's chats."""
        url = f"{GRAPH_BASE}/me/chats?$top={top}&$orderby=lastMessagePreview/createdDateTime desc"
        return await _graph_fetch(client_id, url)

    @router.get("/graph/{client_id}/me/chats/{chat_id}/messages")
    async def list_chat_messages(
        client_id: str, chat_id: str, top: int = Query(20),
    ):
        """List messages in a chat."""
        url = f"{GRAPH_BASE}/me/chats/{chat_id}/messages?$top={top}"
        return await _graph_fetch(client_id, url)

    async def _graph_post(client_id: str, url: str, payload: dict) -> dict:
        """Execute a Graph API POST fetch() from within the browser page."""
        token_info = token_extractor.get_graph_token(client_id)
        if not token_info:
            raise HTTPException(
                status_code=401,
                detail=f"No valid Graph token for client '{client_id}'. Re-login required.",
            )

        page = await _get_page(client_id)

        try:
            result = await page.evaluate(
                """async ([url, token, payload]) => {
                    const response = await fetch(url, {
                        method: 'POST',
                        headers: {
                            'Authorization': 'Bearer ' + token,
                            'Content-Type': 'application/json'
                        },
                        body: JSON.stringify(payload)
                    });
                    const status = response.status;
                    const text = await response.text();
                    return { status, body: text };
                }""",
                [url, token_info.token, payload],
            )
        except Exception as e:
            logger.error("Graph POST failed for %s: %s", client_id, e)
            raise HTTPException(status_code=502, detail=f"Browser fetch failed: {e}")

        status = result.get("status", 500)
        body_text = result.get("body", "")

        if status >= 400:
            logger.warning("Graph API POST %s returned %d for client %s", url, status, client_id)
            raise HTTPException(status_code=status, detail=body_text)

        if not body_text:
            return {}
        try:
            return json.loads(body_text)
        except json.JSONDecodeError:
            return {"raw": body_text}

    @router.post("/graph/{client_id}/me/chats/{chat_id}/messages")
    async def send_chat_message(client_id: str, chat_id: str, body: dict):
        """Send a message to a chat."""
        url = f"{GRAPH_BASE}/me/chats/{chat_id}/messages"
        return await _graph_post(client_id, url, body)

    # ── Teams & Channels ─────────────────────────────────────────────

    @router.get("/graph/{client_id}/me/joinedTeams")
    async def list_teams(client_id: str):
        """List user's joined teams."""
        return await _graph_fetch(client_id, f"{GRAPH_BASE}/me/joinedTeams")

    @router.get("/graph/{client_id}/teams/{team_id}/channels")
    async def list_channels(client_id: str, team_id: str):
        """List channels in a team."""
        return await _graph_fetch(client_id, f"{GRAPH_BASE}/teams/{team_id}/channels")

    @router.get("/graph/{client_id}/teams/{team_id}/channels/{channel_id}/messages")
    async def list_channel_messages(
        client_id: str, team_id: str, channel_id: str, top: int = Query(20),
    ):
        """List messages in a channel."""
        url = f"{GRAPH_BASE}/teams/{team_id}/channels/{channel_id}/messages?$top={top}"
        return await _graph_fetch(client_id, url)

    # ── Generic Graph API proxy ──────────────────────────────────────

    @router.get("/graph/{client_id}/{path:path}")
    async def graph_get(client_id: str, path: str):
        """Generic Graph API GET proxy — any path."""
        url = f"{GRAPH_BASE}/{path}"
        return await _graph_fetch(client_id, url)

    @router.post("/graph/{client_id}/{path:path}")
    async def graph_post(client_id: str, path: str, body: dict):
        """Generic Graph API POST proxy — any path."""
        url = f"{GRAPH_BASE}/{path}"
        return await _graph_post(client_id, url, body)

    return router
