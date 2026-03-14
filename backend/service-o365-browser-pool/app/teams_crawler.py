"""Teams chat crawler — systematically navigates through all chats and extracts messages.

After login, the crawler:
1. Opens the Chat section in Teams
2. Reads all visible chat items from the sidebar via DOM
3. Clicks each chat to open it
4. Extracts messages from the conversation (DOM-based + VLM fallback)
5. Scrolls up for history
6. Stores everything in MongoDB via ScrapeStorage

Works with both Teams v2 (new Teams) and Teams web.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone

from playwright.async_api import Page

logger = logging.getLogger("o365-browser-pool.crawler")


@dataclass
class ChatInfo:
    """A chat/conversation found in the sidebar."""
    name: str
    preview: str = ""
    unread: bool = False
    index: int = 0  # Position in the list for clicking


@dataclass
class ChatMessage:
    """A message extracted from a conversation."""
    sender: str
    content: str
    timestamp: str = ""
    chat_name: str = ""


@dataclass
class CrawlResult:
    """Result of crawling a session."""
    chats_found: int = 0
    chats_crawled: int = 0
    messages_extracted: int = 0
    errors: list[str] = field(default_factory=list)


class TeamsCrawler:
    """Systematically crawls Teams chats to extract all messages."""

    def __init__(self) -> None:
        self._crawled_chats: dict[str, set[str]] = {}  # client_id -> set of crawled chat names

    async def crawl_all_chats(
        self,
        page: Page,
        client_id: str,
        connection_id: str,
        storage,  # ScrapeStorage
        *,
        max_chats: int = 50,
        max_scrolls_per_chat: int = 5,
        use_vlm: bool = True,
    ) -> CrawlResult:
        """Crawl all visible chats in Teams sidebar.

        Args:
            page: Playwright page on Teams
            client_id: Session/client identifier
            connection_id: Connection ID for MongoDB storage
            storage: ScrapeStorage instance
            max_chats: Maximum number of chats to crawl
            max_scrolls_per_chat: How many scroll-ups per chat for history
            use_vlm: Use VLM for message extraction (fallback if DOM fails)
        """
        result = CrawlResult()

        try:
            # Step 1: Navigate to Chat section
            await self._navigate_to_chat(page)
            await asyncio.sleep(3)

            # Step 2: Get all chat items from sidebar
            chats = await self._get_sidebar_chats(page)
            result.chats_found = len(chats)
            logger.info("Crawler: found %d chats in sidebar for %s", len(chats), client_id)

            if not chats:
                # Fallback: try VLM to discover chats
                if use_vlm:
                    chats = await self._discover_chats_vlm(page)
                    result.chats_found = len(chats)
                    logger.info("Crawler: VLM discovered %d chats for %s", len(chats), client_id)

            # Step 3: Crawl each chat
            crawled_set = self._crawled_chats.setdefault(client_id, set())
            for i, chat in enumerate(chats[:max_chats]):
                if chat.name in crawled_set:
                    logger.debug("Crawler: skipping already crawled chat '%s'", chat.name)
                    continue

                try:
                    messages = await self._crawl_single_chat(
                        page, chat, max_scrolls_per_chat, use_vlm,
                    )
                    result.chats_crawled += 1
                    result.messages_extracted += len(messages)

                    # Store messages
                    if messages and storage:
                        msg_dicts = [
                            {
                                "sender": m.sender,
                                "content": m.content,
                                "time": m.timestamp,
                                "chat_name": m.chat_name or chat.name,
                            }
                            for m in messages
                        ]
                        await storage.store_messages(
                            client_id, connection_id, msg_dicts, "chat",
                        )

                    # Store as discovered resource
                    if storage:
                        await storage.store_discovered_resources(
                            connection_id, client_id,
                            [{
                                "id": f"chat_{_slugify(chat.name)}",
                                "name": chat.name,
                                "description": chat.preview,
                                "type": "chat",
                            }],
                        )

                    crawled_set.add(chat.name)
                    logger.info(
                        "Crawler: chat %d/%d '%s' — %d messages",
                        i + 1, len(chats), chat.name, len(messages),
                    )

                except Exception as e:
                    error_msg = f"Error crawling chat '{chat.name}': {e}"
                    logger.warning("Crawler: %s", error_msg)
                    result.errors.append(error_msg)

                # Brief pause between chats to avoid detection
                await asyncio.sleep(2)

        except Exception as e:
            error_msg = f"Crawl failed: {e}"
            logger.error("Crawler: %s", error_msg)
            result.errors.append(error_msg)

        logger.info(
            "Crawler: complete for %s — %d/%d chats, %d messages, %d errors",
            client_id, result.chats_crawled, result.chats_found,
            result.messages_extracted, len(result.errors),
        )
        return result

    async def _navigate_to_chat(self, page: Page) -> None:
        """Navigate to the Chat section in Teams.

        IMPORTANT: Teams v2 is a SPA. NEVER use page.goto() on an already
        logged-in Teams page — it destroys the session and triggers re-login.
        Only use DOM clicks to navigate within the app.
        """
        url = page.url or ""

        # If already on Teams, just try to click the Chat nav button
        if "teams.microsoft.com" not in url and "teams.live.com" not in url:
            logger.warning("Crawler: page is not on Teams (%s), cannot navigate", url[:60])
            return

        logger.info("Crawler: on Teams, looking for Chat nav button...")

        # Try clicking the Chat navigation button (multiple selectors for v2/legacy)
        chat_btn = await self._find_element(page, [
            'button[data-tid="app-bar-Chat"]',
            'button[data-tid="app-bar-86fcd49b-61a2-4701-b771-54728cd291fb"]',
            'button[aria-label="Chat"]',
            'button[aria-label="Chaty"]',
            'a[aria-label="Chat"]',
            'a[aria-label="Chaty"]',
            # Try generic nav rail approach
            'nav[role="navigation"] button',
        ])
        if chat_btn:
            await chat_btn.click()
            logger.info("Crawler: clicked Chat nav button")
            await asyncio.sleep(3)
            return

        # No Chat button found — Teams v2 might already be showing chats
        # (the default view after login is often Chat or Activity)
        logger.info("Crawler: no Chat button found, assuming current view has chats")

    async def _get_sidebar_chats(self, page: Page) -> list[ChatInfo]:
        """Extract chat list from Teams sidebar using DOM."""
        chats: list[ChatInfo] = []

        # Teams v2 chat list selectors
        chat_list_selectors = [
            # New Teams v2
            '[data-tid="chat-list"] [role="listitem"]',
            '[data-tid="chat-list"] [role="treeitem"]',
            'div[data-tid="chat-list-item"]',
            # General
            '[role="list"] [role="listitem"]',
            # Legacy
            '.chat-list-item',
        ]

        for selector in chat_list_selectors:
            try:
                items = page.locator(selector)
                count = await items.count()
                if count == 0:
                    continue

                logger.info("Crawler: found %d chat items with selector '%s'", count, selector)

                for i in range(min(count, 100)):
                    try:
                        item = items.nth(i)
                        if not await item.is_visible(timeout=500):
                            continue

                        # Extract chat name and preview
                        name = ""
                        preview = ""

                        # Try different text extraction approaches
                        # 1. aria-label often has the full info
                        aria = await item.get_attribute("aria-label")
                        if aria:
                            # Parse aria-label like "Chat with John Doe, last message: Hello"
                            name = aria.split(",")[0].strip()
                            name = name.replace("Chat with ", "").replace("Chat s ", "")

                        # 2. Look for specific child elements
                        if not name:
                            name_el = item.locator(
                                '[data-tid="chat-list-item-title"], '
                                '.chat-title, '
                                'span[class*="title"], '
                                'span[class*="name"]'
                            ).first
                            try:
                                if await name_el.is_visible(timeout=300):
                                    name = (await name_el.text_content() or "").strip()
                            except Exception:
                                pass

                        # 3. Fallback: get all text
                        if not name:
                            all_text = (await item.text_content() or "").strip()
                            # Take first line as name
                            lines = [l.strip() for l in all_text.split("\n") if l.strip()]
                            if lines:
                                name = lines[0][:60]
                                if len(lines) > 1:
                                    preview = lines[1][:100]

                        if name:
                            # Check for unread indicator
                            unread = False
                            try:
                                badge = item.locator(
                                    '[data-tid="unread-badge"], '
                                    '.unread-badge, '
                                    'span[class*="unread"]'
                                ).first
                                unread = await badge.is_visible(timeout=200)
                            except Exception:
                                pass

                            chats.append(ChatInfo(
                                name=name,
                                preview=preview,
                                unread=unread,
                                index=i,
                            ))
                    except Exception:
                        continue

                if chats:
                    break  # Found chats, stop trying selectors

            except Exception as e:
                logger.debug("Crawler: selector '%s' failed: %s", selector, e)
                continue

        return chats

    async def _discover_chats_vlm(self, page: Page) -> list[ChatInfo]:
        """Use VLM to discover chats from screenshot (fallback)."""
        try:
            from app.vlm_client import analyze_screenshot

            screenshot = await page.screenshot(type="jpeg", quality=80)
            if not screenshot or len(screenshot) < 1000:
                return []

            prompt = """Analyze this Microsoft Teams screenshot. List ALL chat conversations visible in the left sidebar.
For each chat, provide:
- name: the chat/person/group name
- preview: the last message preview (if visible)
- unread: true if there's an unread indicator

Return JSON:
{"chats": [{"name": "...", "preview": "...", "unread": false}]}

List EVERY visible chat. Be precise with names."""

            result_text = await analyze_screenshot(screenshot, prompt)

            # Parse response
            import json
            cleaned = result_text.strip()
            if cleaned.startswith("```"):
                lines = cleaned.split("\n")
                lines = [l for l in lines if not l.strip().startswith("```")]
                cleaned = "\n".join(lines)

            start = cleaned.find("{")
            end = cleaned.rfind("}")
            if start >= 0 and end > start:
                data = json.loads(cleaned[start:end + 1])
                return [
                    ChatInfo(
                        name=c.get("name", ""),
                        preview=c.get("preview", ""),
                        unread=c.get("unread", False),
                        index=i,
                    )
                    for i, c in enumerate(data.get("chats", []))
                    if c.get("name")
                ]
        except Exception as e:
            logger.warning("Crawler: VLM chat discovery failed: %s", e)

        return []

    async def _crawl_single_chat(
        self,
        page: Page,
        chat: ChatInfo,
        max_scrolls: int,
        use_vlm: bool,
    ) -> list[ChatMessage]:
        """Click on a chat and extract its messages."""
        messages: list[ChatMessage] = []

        # Click on the chat in sidebar
        clicked = await self._click_chat(page, chat)
        if not clicked:
            logger.warning("Crawler: could not click chat '%s'", chat.name)
            return messages

        # Wait for conversation to load
        await asyncio.sleep(3)

        # Extract messages from current view
        dom_messages = await self._extract_messages_dom(page, chat.name)
        if dom_messages:
            messages.extend(dom_messages)
        elif use_vlm:
            vlm_messages = await self._extract_messages_vlm(page, chat.name)
            messages.extend(vlm_messages)

        # Scroll up for history
        for scroll in range(max_scrolls):
            # Scroll up in the message area
            scrolled = await self._scroll_up_messages(page)
            if not scrolled:
                break
            await asyncio.sleep(2)

            # Extract new messages
            new_messages = await self._extract_messages_dom(page, chat.name)
            if not new_messages and use_vlm:
                new_messages = await self._extract_messages_vlm(page, chat.name)

            if not new_messages:
                break

            # Deduplicate
            existing_hashes = {_msg_hash(m) for m in messages}
            for m in new_messages:
                if _msg_hash(m) not in existing_hashes:
                    messages.append(m)

        return messages

    async def _click_chat(self, page: Page, chat: ChatInfo) -> bool:
        """Click on a specific chat in the sidebar."""
        # Strategy 1: Click by aria-label containing the chat name
        try:
            # Escape special regex chars in name
            escaped = re.escape(chat.name)
            item = page.locator(f'[role="listitem"]:has-text("{chat.name}")').first
            if await item.is_visible(timeout=1000):
                await item.click()
                return True
        except Exception:
            pass

        # Strategy 2: Try treeitem
        try:
            item = page.locator(f'[role="treeitem"]:has-text("{chat.name}")').first
            if await item.is_visible(timeout=1000):
                await item.click()
                return True
        except Exception:
            pass

        # Strategy 3: Click by index in the chat list
        try:
            list_items = page.locator(
                '[data-tid="chat-list"] [role="listitem"], '
                '[data-tid="chat-list"] [role="treeitem"], '
                '[role="list"] [role="listitem"]'
            )
            count = await list_items.count()
            if chat.index < count:
                await list_items.nth(chat.index).click()
                return True
        except Exception:
            pass

        # Strategy 4: Search for the chat
        try:
            await page.keyboard.press("Control+e")
            await asyncio.sleep(1)
            await page.keyboard.type(chat.name[:30], delay=50)
            await asyncio.sleep(2)
            await page.keyboard.press("Enter")
            await asyncio.sleep(2)
            # Clear search
            await page.keyboard.press("Escape")
            return True
        except Exception:
            pass

        return False

    async def _extract_messages_dom(
        self, page: Page, chat_name: str,
    ) -> list[ChatMessage]:
        """Extract messages from conversation using DOM selectors."""
        messages: list[ChatMessage] = []

        # Teams v2 message selectors
        msg_selectors = [
            '[data-tid="chat-pane-message"]',
            '[data-tid="message-pane-message"]',
            'div[class*="message-body"]',
            'div[role="listitem"][data-tid*="message"]',
        ]

        for selector in msg_selectors:
            try:
                items = page.locator(selector)
                count = await items.count()
                if count == 0:
                    continue

                for i in range(count):
                    try:
                        item = items.nth(i)
                        if not await item.is_visible(timeout=300):
                            continue

                        sender = ""
                        content = ""
                        timestamp = ""

                        # Extract sender
                        sender_el = item.locator(
                            '[data-tid="message-author-name"], '
                            'span[class*="author"], '
                            'span[class*="sender"]'
                        ).first
                        try:
                            if await sender_el.is_visible(timeout=200):
                                sender = (await sender_el.text_content() or "").strip()
                        except Exception:
                            pass

                        # Extract timestamp
                        time_el = item.locator(
                            '[data-tid="message-timestamp"], '
                            'time, '
                            'span[class*="timestamp"]'
                        ).first
                        try:
                            if await time_el.is_visible(timeout=200):
                                timestamp = (
                                    await time_el.get_attribute("datetime")
                                    or await time_el.text_content()
                                    or ""
                                ).strip()
                        except Exception:
                            pass

                        # Extract content
                        content_el = item.locator(
                            '[data-tid="message-body-content"], '
                            'div[class*="message-body"], '
                            'div[class*="content"]'
                        ).first
                        try:
                            if await content_el.is_visible(timeout=200):
                                content = (await content_el.text_content() or "").strip()
                        except Exception:
                            pass

                        # Fallback: get all text from the message
                        if not content:
                            all_text = (await item.text_content() or "").strip()
                            if all_text:
                                content = all_text

                        if content:
                            messages.append(ChatMessage(
                                sender=sender,
                                content=content,
                                timestamp=timestamp,
                                chat_name=chat_name,
                            ))
                    except Exception:
                        continue

                if messages:
                    break

            except Exception:
                continue

        return messages

    async def _extract_messages_vlm(
        self, page: Page, chat_name: str,
    ) -> list[ChatMessage]:
        """Extract messages using VLM screenshot analysis (fallback)."""
        try:
            from app.vlm_client import analyze_screenshot
            import json

            screenshot = await page.screenshot(type="jpeg", quality=80)
            if not screenshot or len(screenshot) < 1000:
                return []

            prompt = f"""Analyze this Microsoft Teams chat screenshot.
The open conversation is: "{chat_name}"

Extract ALL visible messages in the conversation:
- sender: who sent the message
- content: the message text
- time: timestamp if visible

Return JSON:
{{"messages": [{{"sender": "...", "content": "...", "time": "..."}}]}}

List EVERY visible message in chronological order."""

            result_text = await analyze_screenshot(screenshot, prompt)

            cleaned = result_text.strip()
            if cleaned.startswith("```"):
                lines = cleaned.split("\n")
                lines = [l for l in lines if not l.strip().startswith("```")]
                cleaned = "\n".join(lines)

            start = cleaned.find("{")
            end = cleaned.rfind("}")
            if start >= 0 and end > start:
                data = json.loads(cleaned[start:end + 1])
                return [
                    ChatMessage(
                        sender=m.get("sender", ""),
                        content=m.get("content", ""),
                        timestamp=m.get("time", ""),
                        chat_name=chat_name,
                    )
                    for m in data.get("messages", [])
                    if m.get("content")
                ]
        except Exception as e:
            logger.warning("Crawler: VLM message extraction failed: %s", e)

        return []

    async def _scroll_up_messages(self, page: Page) -> bool:
        """Scroll up in the message area to load older messages."""
        # Find the message container and scroll it
        container_selectors = [
            '[data-tid="chat-pane-list"]',
            '[data-tid="message-pane-list"]',
            'div[class*="message-list"]',
            'div[role="main"] [role="list"]',
        ]

        for selector in container_selectors:
            try:
                container = page.locator(selector).first
                if await container.is_visible(timeout=500):
                    # Scroll up
                    await container.evaluate(
                        "el => el.scrollTop = Math.max(0, el.scrollTop - 1000)"
                    )
                    return True
            except Exception:
                continue

        # Fallback: use keyboard
        try:
            await page.keyboard.press("PageUp")
            return True
        except Exception:
            return False

    async def _find_element(self, page: Page, selectors: list[str]):
        """Try multiple selectors, return first visible match or None."""
        for selector in selectors:
            try:
                el = page.locator(selector).first
                if await el.is_visible(timeout=500):
                    return el
            except Exception:
                continue
        return None


def _slugify(name: str) -> str:
    """Convert name to a URL-safe slug."""
    slug = name.lower().strip()
    slug = re.sub(r'[^a-z0-9]+', '_', slug)
    return slug[:50]


def _msg_hash(msg: ChatMessage) -> str:
    """Create a hash for message deduplication."""
    raw = f"{msg.sender}|{msg.timestamp}|{msg.content}"
    return hashlib.sha256(raw.encode()).hexdigest()[:16]
