"""WhatsApp screen scraper — on-demand VLM-based content extraction.

Called by Kotlin server during polling cycle (CentralPoller → WhatsAppPollingHandler).
Takes screenshots of WhatsApp Web, sends to VLM for analysis, stores in MongoDB.

Two scraping modes:
1. Chat list (sidebar) — extracts all visible conversations
2. Open conversation — extracts messages from the active chat

Attachment handling:
- VLM describes image/video/document attachments visible in the chat
- Attachment type and description are stored alongside message content
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone

from app.browser_manager import BrowserManager
from app.kotlin_callback import notify_session_state, notify_capabilities_discovered
from app.models import SessionState
from app.scrape_storage import ScrapeStorage
from app.vlm_client import analyze_screenshot

logger = logging.getLogger("whatsapp-browser.scraper")

# ─── VLM Prompts ───────────────────────────────────────────────────────────

QR_CHECK_PROMPT = """Analyze this WhatsApp Web screenshot. Determine the current state:

1. If you see a QR code for scanning → state: "qr_visible"
2. If you see "Phone not connected" or "Connect your phone" or similar banner → state: "phone_disconnected"
3. If you see a chat list with conversations in the sidebar → state: "logged_in"
4. If you see a loading spinner or "Loading" text → state: "loading"
5. If you see any error message → state: "error", include the error text

Return JSON:
{
  "state": "qr_visible|phone_disconnected|logged_in|loading|error",
  "description": "brief description of what you see"
}"""

CHAT_LIST_PROMPT = """Analyze this WhatsApp Web screenshot. Extract the chat list from the left sidebar.

For each visible chat/conversation extract:
- name: Contact or group name
- last_message: Preview text of the last message
- time: Timestamp shown (e.g. "14:32", "yesterday", "3/1/2026")
- unread_count: Number of unread messages (green badge), 0 if none
- is_group: true if group chat (group icon or multiple participants visible)
- is_pinned: true if pinned indicator visible

IMPORTANT: If any chat shows an attachment icon (camera, microphone, document clip) in the preview,
include it in last_message like "[Photo]", "[Voice message]", "[Document]", "[Video]", "[Sticker]".

Return JSON:
{
  "chats": [
    {"name": "...", "last_message": "...", "time": "...", "unread_count": 0, "is_group": false, "is_pinned": false}
  ],
  "total_unread": 0,
  "has_new_messages": false
}"""

CONVERSATION_PROMPT = """Analyze this WhatsApp Web screenshot showing an open conversation.
Extract ALL visible messages in chronological order.

For each message:
- sender: Name of sender (in group) or "me" / contact name (in 1:1 chat)
- time: Timestamp visible on the message
- content: Message text content. If the message is an attachment, describe it:
  - For images: "[Image: brief description of what the image shows]"
  - For videos: "[Video: brief description if thumbnail visible]"
  - For voice messages: "[Voice message: duration if visible]"
  - For documents: "[Document: filename if visible]"
  - For stickers: "[Sticker: description of the sticker]"
  - For links with preview: include both the link and preview text
- type: "text", "image", "voice", "document", "sticker", "video"
- is_forwarded: true if "Forwarded" label visible above the message
- reply_to: the quoted/replied text if this message is a reply, null otherwise
- attachment_type: null for text, or "image"/"video"/"document"/"voice"/"sticker"
- attachment_description: null for text, or a description of the attachment content

Also extract:
- conversation_name: Name shown at the top of the chat
- is_group: whether this is a group chat
- participant_count: number of participants if visible in group info

Return JSON:
{
  "conversation_name": "...",
  "is_group": false,
  "participant_count": null,
  "messages": [
    {
      "sender": "...",
      "time": "...",
      "content": "...",
      "type": "text",
      "is_forwarded": false,
      "reply_to": null,
      "attachment_type": null,
      "attachment_description": null
    }
  ]
}"""


class WhatsAppScraper:
    """On-demand scraper for WhatsApp Web.

    No autonomous loop — scraping is triggered by the Kotlin server
    during its polling cycle (CentralPoller → WhatsAppPollingHandler
    → POST /scrape/{client_id}/trigger).

    Guards:
    - Only one scrape runs at a time (_scraping lock)
    - If a scrape just finished, trigger returns immediately (DB already has data)
    - VLM calls can take 30-60s — trigger is fire-and-forget from server side
    """

    def __init__(
        self,
        browser_manager: BrowserManager,
        scrape_storage: ScrapeStorage,
    ) -> None:
        self._bm = browser_manager
        self._storage = scrape_storage
        self._connection_id: str | None = None
        self._last_sidebar_data: dict | None = None
        self._scraping = False  # lock: only one scrape at a time

    @property
    def is_scraping(self) -> bool:
        return self._scraping

    async def start(self) -> None:
        """No-op lifecycle hook.

        WhatsAppScraper has no autonomous loop — scrapes are triggered
        on-demand from the Kotlin server via POST /scrape/{client_id}/trigger.
        main.py's FastAPI lifespan still calls start()/stop() for symmetry
        with the other long-lived components (BrowserManager, SessionMonitor,
        ScrapeStorage) so that future work can add a background task here
        without touching the startup sequence.
        """
        logger.debug("WhatsAppScraper.start() — on-demand mode, no background loop")

    async def stop(self) -> None:
        """No-op lifecycle hook — see [start]."""
        logger.debug("WhatsAppScraper.stop() — on-demand mode, no background loop")

    def set_connection_id(self, connection_id: str) -> None:
        self._connection_id = connection_id

    def get_last_sidebar(self) -> dict | None:
        return self._last_sidebar_data

    async def check_login_state(self, client_id: str) -> str:
        """Check WhatsApp Web login state via DOM element detection.

        Uses reliable CSS selectors instead of VLM screenshot analysis.
        Returns: "qr_visible", "phone_disconnected", "logged_in", "loading", "error"
        """
        context = self._bm.get_context()
        if not context or not context.pages:
            return "error"

        page = context.pages[0]
        try:
            url = page.url or ""
            if "whatsapp.com" not in url:
                return "error"

            # Check for chat sidebar (logged in) — #pane-side or [data-testid="chat-list"]
            sidebar = await page.query_selector("#pane-side, [data-testid='chat-list']")
            if sidebar:
                logger.info("WhatsApp login state: logged_in (sidebar detected)")
                return "logged_in"

            # Check for QR code canvas
            qr = await page.query_selector("canvas[aria-label], [data-testid='qrcode']")
            if qr:
                logger.info("WhatsApp login state: qr_visible (QR canvas detected)")
                return "qr_visible"

            # Check for "Phone not connected" banner
            disconnected = await page.query_selector("[data-testid='alert-phone']")
            if disconnected:
                logger.info("WhatsApp login state: phone_disconnected")
                return "phone_disconnected"

            # Loading state (progress bar or spinner visible)
            loading = await page.query_selector("progress, [data-testid='startup']")
            if loading:
                logger.info("WhatsApp login state: loading")
                return "loading"

            # Fallback: page loaded but no known element — try title
            title = await page.title()
            if "WhatsApp" in (title or ""):
                logger.info("WhatsApp login state: loading (title match, no sidebar yet)")
                return "loading"

            logger.info("WhatsApp login state: error (no known elements)")
            return "error"
        except Exception as e:
            logger.warning("WhatsApp login state check failed: %s", e)
            return "error"

    async def scrape_now(self, client_id: str) -> dict:
        """Execute a single scrape cycle (sidebar + unread conversations).

        Called by Kotlin server on each polling cycle.
        Returns immediately if another scrape is already running.
        """
        if self._scraping:
            return {"status": "already_running"}

        state = self._bm.get_state()
        if state != SessionState.ACTIVE:
            return {"status": "not_active", "session_state": state.value}

        context = self._bm.get_context()
        if not context or not context.pages:
            return {"status": "no_context"}

        self._scraping = True
        try:
            return await self._do_scrape(client_id, context)
        finally:
            self._scraping = False

    async def _do_scrape(self, client_id: str, context) -> dict:
        """Internal scrape implementation."""

        page = context.pages[0]
        conn_id = self._connection_id or client_id

        # 1. Screenshot sidebar (chat list)
        screenshot = await page.screenshot(type="jpeg", quality=80)
        sidebar_data = await self._scrape_chat_list(client_id, screenshot)

        if not sidebar_data:
            return {"status": "scrape_failed", "sidebar": False}

        # Detect session expiry (VLM sees QR code or disconnection)
        if sidebar_data.get("state") in ("qr_visible", "phone_disconnected"):
            logger.warning("WhatsApp session expired — detected: %s", sidebar_data.get("state"))
            self._bm.set_state(SessionState.EXPIRED)
            await notify_session_state(client_id, conn_id, "EXPIRED")
            return {"status": "session_expired", "reason": sidebar_data.get("state")}

        self._last_sidebar_data = sidebar_data

        # Store sidebar result
        await self._storage.store_scrape_result(client_id, conn_id, sidebar_data)

        # Store discovered chats as resources
        chats = sidebar_data.get("chats", [])
        if chats:
            resources = [
                {
                    "id": f"chat_{c.get('name', '').lower().replace(' ', '_')[:50]}",
                    "name": c.get("name", ""),
                    "description": c.get("last_message"),
                    "type": "group" if c.get("is_group") else "chat",
                    "is_group": c.get("is_group", False),
                }
                for c in chats if c.get("name")
            ]
            await self._storage.store_discovered_resources(conn_id, client_id, resources)

            # Store sidebar preview messages (last message per chat) — no click needed,
            # no read receipts triggered. This captures content without marking as read.
            preview_messages = []
            for c in chats:
                last_msg = c.get("last_message", "")
                sender = c.get("last_sender", c.get("name", ""))
                if last_msg:
                    preview_messages.append({
                        "sender": sender,
                        "time": c.get("time", ""),
                        "content": last_msg,
                        "chat_name": c.get("name", ""),
                        "is_group": c.get("is_group", False),
                    })
            if preview_messages:
                stored = await self._storage.store_messages(client_id, conn_id, preview_messages)
                messages_scraped += stored

        # NOTE: Clicking on individual chats is disabled to avoid triggering
        # read receipts (blue checkmarks). Sidebar preview messages (above) are
        # sufficient for indexing — they capture the last message per chat without
        # marking anything as read. Full conversation scraping requires a different
        # approach (WhatsApp Web API / IndexedDB extraction).

        return {
            "status": "ok",
            "chats_total": len(chats),
            "chats_unread": unread_count,
            "messages_scraped": messages_scraped,
        }

    async def _open_chat(self, page, chat_name: str) -> None:
        """Click on a chat in the sidebar by name.

        Tries multiple strategies:
        1. span[title] — exact match (WhatsApp uses title attr on chat name)
        2. Partial text match in chat list area
        3. data-testid based selectors
        """
        strategies = [
            # WhatsApp Web uses span[title] for chat names in sidebar
            lambda: page.locator(f'#pane-side span[title="{chat_name}"]').first,
            # Partial match — VLM names may not be exact
            lambda: page.locator(f'#pane-side span[title*="{chat_name[:15]}"]').first,
            # Text-based search within sidebar only
            lambda: page.locator('#pane-side').get_by_text(chat_name, exact=False).first,
            # data-testid cell with matching text
            lambda: page.locator(f'[data-testid="cell-frame-container"]:has-text("{chat_name[:15]}")').first,
        ]
        for strategy in strategies:
            try:
                el = strategy()
                await el.click(timeout=5000)
                return
            except Exception:
                continue
        logger.debug("Could not click on chat '%s' with any strategy", chat_name)
        raise Exception(f"Chat '{chat_name}' not found in sidebar")

    async def _scrape_chat_list(self, client_id: str, screenshot: bytes) -> dict | None:
        """Analyze sidebar screenshot with VLM."""
        try:
            result_text = await analyze_screenshot(screenshot, CHAT_LIST_PROMPT)
        except Exception as e:
            logger.warning("VLM chat list analysis failed: %s", e)
            return None

        parsed = _parse_vlm_response(result_text)
        if not parsed:
            logger.warning("Failed to parse VLM chat list response")
            return None

        if parsed.get("state") in ("qr_visible", "phone_disconnected"):
            return parsed

        logger.info(
            "Scraped chat list: %d chats, %d unread",
            len(parsed.get("chats", [])),
            parsed.get("total_unread", 0),
        )
        return parsed

    async def _scrape_conversation(self, client_id: str, screenshot: bytes) -> dict | None:
        """Analyze open conversation screenshot with VLM."""
        try:
            result_text = await analyze_screenshot(screenshot, CONVERSATION_PROMPT)
        except Exception as e:
            logger.warning("VLM conversation analysis failed: %s", e)
            return None

        parsed = _parse_vlm_response(result_text)
        if not parsed:
            logger.warning("Failed to parse VLM conversation response")
            return None

        logger.info(
            "Scraped conversation '%s': %d messages",
            parsed.get("conversation_name", "?"),
            len(parsed.get("messages", [])),
        )
        return parsed


def _parse_vlm_response(text: str) -> dict | None:
    """Extract JSON from VLM response (may contain markdown fences)."""
    cleaned = text.strip()
    if cleaned.startswith("```"):
        lines = cleaned.split("\n")
        lines = [l for l in lines if not l.strip().startswith("```")]
        cleaned = "\n".join(lines)

    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        start = cleaned.find("{")
        end = cleaned.rfind("}")
        if start >= 0 and end > start:
            try:
                return json.loads(cleaned[start : end + 1])
            except json.JSONDecodeError:
                pass
    return None
