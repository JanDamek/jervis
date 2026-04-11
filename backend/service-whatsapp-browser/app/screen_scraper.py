"""WhatsApp screen scraper — state-aware DOM scraping with optional VLM for attachments.

Approach:
1. DOM enumeration: scroll the sidebar in #pane-side and collect all chat rows
   via JavaScript evaluate(). Each row provides real chat name (span[title]),
   last message preview, timestamp, unread count and attachment indicator from
   the actual DOM — never from VLM hallucinations.
2. State diff: compare per-chat innerText hash against the last saved state in
   MongoDB (whatsapp_sidebar_state). Only changed chats need processing.
3. Selective VLM: text-only previews are stored directly from DOM. Only chat
   rows that contain an attachment placeholder ([Photo], [Voice], [Document], …)
   are screenshotted and sent to the VLM to extract the attachment description.
4. Persist: store new messages in whatsapp_scrape_messages and update sidebar
   state after each cycle.

Login state detection (check_login_state) is also DOM-based — no VLM, no
hallucinations, fast and deterministic.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
from datetime import datetime, timezone

from app.browser_manager import BrowserManager
from app.config import settings
from app.kotlin_callback import notify_session_state
from app.models import SessionState
from app.scrape_storage import ScrapeStorage
from app.vlm_client import analyze_screenshot

logger = logging.getLogger("whatsapp-browser.scraper")


# ─── VLM prompt for attachment description ────────────────────────────────

ATTACHMENT_PROMPT = """This is a single WhatsApp Web chat row from the sidebar.
The last message contains an attachment (image / video / voice / document / sticker).

Describe ONLY the attachment content visible in the screenshot. Be concise.

Return JSON:
{
  "attachment_type": "image|video|voice|document|sticker|location|contact",
  "attachment_description": "brief description of what is shown / said / written"
}"""


# ─── DOM extraction JavaScript ────────────────────────────────────────────
#
# WhatsApp Web 2026 DOM structure (verified via /session/{id}/debug-dom):
#   #pane-side                  ← sidebar root
#     div[role="grid"]          ← scrollable list
#       div[role="row"]         ← chat row
#         span[title="Name"]    ← real chat name
#         div                   ← last message preview text container
#         div                   ← timestamp
#         span                  ← unread badge (number) when present
#
# We use innerText of each row to compute a stable hash. Diffing the hash
# tells us when the row content (last message, sender, timestamp) changed
# regardless of why (new message, user read on phone, etc).

JS_RESET_SCROLL = """() => {
    const grid = document.querySelector('#pane-side div[role="grid"]');
    if (grid) grid.scrollTop = 0;
}"""

JS_SCROLL_DOWN = """() => {
    const grid = document.querySelector('#pane-side div[role="grid"]');
    if (!grid) return false;
    const before = grid.scrollTop;
    grid.scrollTop += grid.clientHeight - 100;
    return grid.scrollTop > before;
}"""

JS_EXTRACT_ROWS = """() => {
    const grid = document.querySelector('#pane-side div[role="grid"]');
    if (!grid) return [];
    const rows = grid.querySelectorAll('div[role="row"], div[role="listitem"]');
    return Array.from(rows).map(row => {
        const nameEl = row.querySelector('span[title]');
        if (!nameEl) return null;
        const name = nameEl.getAttribute('title') || '';
        if (!name) return null;
        const text = (row.innerText || '').trim();
        // Detect attachment placeholder in preview
        const attachmentPatterns = [
            ['image', /\\[?(Photo|Image|Foto|Obrázek)\\]?/i],
            ['video', /\\[?Video\\]?/i],
            ['voice', /\\[?(Voice message|Hlasová zpráva)\\]?/i],
            ['document', /\\[?(Document|Dokument)\\]?/i],
            ['sticker', /\\[?(Sticker|Samolepka)\\]?/i],
            ['location', /\\[?(Location|Poloha)\\]?/i],
            ['contact', /\\[?(Contact|Kontakt)\\]?/i],
        ];
        let attachmentType = null;
        for (const [t, re] of attachmentPatterns) {
            if (re.test(text)) { attachmentType = t; break; }
        }
        return { name, text, attachmentType };
    }).filter(Boolean);
}"""


def _hash_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


class WhatsAppScraper:
    """State-aware DOM scraper for WhatsApp Web.

    No autonomous loop — scraping is triggered by the Kotlin server during
    its polling cycle (CentralPoller → WhatsAppPollingHandler → POST
    /scrape/{client_id}/trigger).

    Concurrency: only one scrape runs at a time per scraper instance.
    """

    def __init__(
        self,
        browser_manager: BrowserManager,
        scrape_storage: ScrapeStorage,
    ) -> None:
        self._bm = browser_manager
        self._storage = scrape_storage
        self._connection_id: str | None = None
        self._scraping = False  # lock: only one scrape at a time

    @property
    def is_scraping(self) -> bool:
        return self._scraping

    async def start(self) -> None:
        logger.debug("WhatsAppScraper.start() — on-demand mode")

    async def stop(self) -> None:
        logger.debug("WhatsAppScraper.stop() — on-demand mode")

    def set_connection_id(self, connection_id: str) -> None:
        self._connection_id = connection_id

    # ── Login state (DOM-based, no VLM) ──────────────────────────────────

    async def check_login_state(self, client_id: str) -> str:
        """Detect WhatsApp Web login state via DOM selectors.

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

            sidebar = await page.query_selector("#pane-side, [data-testid='chat-list']")
            if sidebar:
                logger.info("WhatsApp login state: logged_in (sidebar detected)")
                return "logged_in"

            qr = await page.query_selector("canvas[aria-label], [data-testid='qrcode']")
            if qr:
                logger.info("WhatsApp login state: qr_visible (QR canvas detected)")
                return "qr_visible"

            disconnected = await page.query_selector("[data-testid='alert-phone']")
            if disconnected:
                logger.info("WhatsApp login state: phone_disconnected")
                return "phone_disconnected"

            loading = await page.query_selector("progress, [data-testid='startup']")
            if loading:
                logger.info("WhatsApp login state: loading")
                return "loading"

            title = await page.title()
            if "WhatsApp" in (title or ""):
                logger.info("WhatsApp login state: loading (title match, no sidebar yet)")
                return "loading"

            logger.info("WhatsApp login state: error (no known elements)")
            return "error"
        except Exception as e:
            logger.warning("WhatsApp login state check failed: %s", e)
            return "error"

    # ── Scrape entry point ───────────────────────────────────────────────

    async def scrape_now(
        self,
        client_id: str,
        *,
        max_tier: str,
        processing_mode: str,
    ) -> dict:
        """Run one scrape cycle.

        Args:
            client_id: WhatsApp connection client ID.
            max_tier: REQUIRED tier for VLM routing (NONE/FREE/PAID/PREMIUM).
                Resolved by Kotlin server from client/project policy.
            processing_mode: REQUIRED ("FOREGROUND" or "BACKGROUND").
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
            return await self._do_scrape(
                client_id,
                context,
                max_tier=max_tier,
                processing_mode=processing_mode,
            )
        finally:
            self._scraping = False

    # ── Internal: state-aware DOM scrape ─────────────────────────────────

    async def _do_scrape(
        self,
        client_id: str,
        context,
        *,
        max_tier: str,
        processing_mode: str,
    ) -> dict:
        page = context.pages[0]
        conn_id = self._connection_id or client_id

        # 1. Verify login still valid (DOM check, no VLM)
        login_state = await self.check_login_state(client_id)
        if login_state in ("qr_visible", "phone_disconnected"):
            logger.warning("WhatsApp session expired during scrape: %s", login_state)
            self._bm.set_state(SessionState.EXPIRED)
            await notify_session_state(client_id, conn_id, "EXPIRED")
            return {"status": "session_expired", "reason": login_state}
        if login_state != "logged_in":
            return {"status": "not_logged_in", "login_state": login_state}

        # 2. Enumerate sidebar chat rows via DOM scroll discovery
        try:
            current_chats = await self._enumerate_sidebar_dom(page)
        except Exception as e:
            logger.warning("Sidebar enumeration failed: %s", e)
            return {"status": "enumeration_failed", "error": str(e)}

        if not current_chats:
            logger.info("Sidebar enumerated: 0 chats found")
            return {"status": "ok", "chats_total": 0, "scraped": 0}

        # 3. Diff against persisted state
        prev_state = await self._storage.get_sidebar_state(client_id) or {"chats": {}}
        prev_chats = prev_state.get("chats", {})

        to_process: list[dict] = []
        for chat in current_chats:
            chat["hash"] = _hash_text(chat["text"])
            prev_hash = prev_chats.get(chat["name"], {}).get("hash")
            if prev_hash != chat["hash"]:
                to_process.append(chat)

        logger.info(
            "Sidebar enumerated: total=%d, changed=%d (vs prev state)",
            len(current_chats),
            len(to_process),
        )

        # 4. Build and store messages from changed rows
        new_messages = []
        for chat in to_process:
            attachment_type = chat.get("attachmentType")
            attachment_description: str | None = None

            if attachment_type:
                # Screenshot only this row, ask VLM to describe the attachment
                try:
                    row_screenshot = await self._screenshot_chat_row(page, chat["name"])
                    if row_screenshot:
                        described = await self._vlm_describe_attachment(
                            row_screenshot,
                            max_tier=max_tier,
                            processing_mode=processing_mode,
                        )
                        if described:
                            attachment_type = described.get("attachment_type", attachment_type)
                            attachment_description = described.get("attachment_description")
                except Exception as e:
                    logger.warning(
                        "VLM attachment analysis failed for chat '%s': %s",
                        chat["name"], e,
                    )

            content = chat["text"]
            new_messages.append({
                "sender": chat["name"],  # sidebar preview shows chat name only
                "time": "",  # not separately parsed yet — included in text
                "content": content,
                "chat_name": chat["name"],
                "is_group": False,  # not detectable from sidebar alone
                "attachment_type": attachment_type,
                "attachment_description": attachment_description,
            })

        if new_messages:
            stored = await self._storage.store_messages(
                client_id, conn_id, new_messages,
            )
            logger.info("Stored %d new messages from %d changed chats", stored, len(to_process))

        # 5. Update sidebar state in DB
        new_state_chats = {
            c["name"]: {"hash": c["hash"], "text": c["text"][:500]}
            for c in current_chats
        }
        await self._storage.save_sidebar_state(client_id, new_state_chats)

        return {
            "status": "ok",
            "chats_total": len(current_chats),
            "changed": len(to_process),
            "scraped": len(new_messages),
        }

    # ── DOM enumeration with scroll discovery ────────────────────────────

    async def _enumerate_sidebar_dom(self, page) -> list[dict]:
        """Scroll the sidebar grid and collect all chat rows via JS evaluate.

        Stops when stable_iterations consecutive scrolls add no new chats,
        or when max_scroll_iterations is hit.
        """
        scroll_delay_s = settings.sidebar_scroll_delay_ms / 1000.0
        stable_target = settings.sidebar_stable_iterations
        max_iters = settings.sidebar_max_scroll_iterations

        # Reset to top
        try:
            await page.evaluate(JS_RESET_SCROLL)
        except Exception as e:
            logger.debug("Reset scroll failed: %s", e)
        await asyncio.sleep(scroll_delay_s)

        seen: dict[str, dict] = {}
        stable = 0
        for i in range(max_iters):
            try:
                rows = await page.evaluate(JS_EXTRACT_ROWS)
            except Exception as e:
                logger.warning("Sidebar JS extract failed at iteration %d: %s", i, e)
                break

            before = len(seen)
            for r in rows or []:
                name = r.get("name")
                if name and name not in seen:
                    seen[name] = r

            try:
                scrolled = await page.evaluate(JS_SCROLL_DOWN)
            except Exception:
                scrolled = False
            await asyncio.sleep(scroll_delay_s)

            if len(seen) == before:
                stable += 1
                if stable >= stable_target:
                    break
            else:
                stable = 0

            if not scrolled:
                break

        # Reset scroll back so the user sees a normal view
        try:
            await page.evaluate(JS_RESET_SCROLL)
        except Exception:
            pass

        return list(seen.values())

    # ── Per-row screenshot for attachment VLM ────────────────────────────

    async def _screenshot_chat_row(self, page, chat_name: str) -> bytes | None:
        """Locate the row by chat name and take a clip-rect screenshot."""
        try:
            handle = await page.query_selector(f'#pane-side span[title="{chat_name}"]')
            if not handle:
                return None
            # Walk up to the row container (a few parents up)
            row = await handle.evaluate_handle(
                'el => el.closest("div[role=\\"row\\"]") || el.closest("div[role=\\"listitem\\"]") || el.parentElement'
            )
            bbox = await row.bounding_box()
            if not bbox:
                return None
            return await page.screenshot(
                type="jpeg",
                quality=80,
                clip={
                    "x": bbox["x"],
                    "y": bbox["y"],
                    "width": bbox["width"],
                    "height": bbox["height"],
                },
            )
        except Exception as e:
            logger.debug("Row screenshot failed for '%s': %s", chat_name, e)
            return None

    async def _vlm_describe_attachment(
        self,
        screenshot: bytes,
        *,
        max_tier: str,
        processing_mode: str,
    ) -> dict | None:
        """Ask the VLM to describe the attachment in a chat row screenshot."""
        try:
            text = await analyze_screenshot(
                screenshot,
                ATTACHMENT_PROMPT,
                processing_mode=processing_mode,
                max_tier=max_tier,
            )
        except Exception as e:
            logger.warning("VLM attachment call failed: %s", e)
            return None
        return _parse_vlm_response(text)


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
