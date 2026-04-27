"""Core browser context management – one Playwright context per client.

Uses `launch_persistent_context()` so the Chromium user-data-dir
itself (cookies, localStorage, IndexedDB, history, extensions) lives on
the PVC. Open-tab URLs are NOT persisted by Chromium between launches
when Playwright drives it (no session-restore on the headless side), so
a small `tabs.json` is written periodically alongside the profile and
replayed on next launch.

Net effect: pod restart = browser comes back with the same cookies AND
the same tabs at the same URLs, no `about:blank` fresh start.
"""

from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path

from playwright.async_api import BrowserContext, Playwright, async_playwright

from app.config import settings

logger = logging.getLogger("o365-browser-pool")

# How often to flush the tab URL list to disk while the pod runs.
# Cookie / localStorage flush is handled by Chromium itself.
TAB_AUTOSAVE_INTERVAL_S = 30


class BrowserManager:
    """Manages one persistent Chromium profile per client.

    Each client gets `{profiles_dir}/{client_id}/chromium-profile/` as
    its Chromium `user_data_dir`. Plus `{profiles_dir}/{client_id}/
    tabs.json` with the URLs that were open at the last autosave —
    replayed on next start so tabs survive pod restarts even after a
    crash. Pod state lives separately in PodStateManager.
    """

    def __init__(self) -> None:
        self._contexts: dict[str, BrowserContext] = {}
        self._playwright: Playwright | None = None
        self._autosave_tasks: dict[str, asyncio.Task] = {}

    # -- lifecycle -------------------------------------------------------------

    async def startup(self) -> None:
        self._playwright = await async_playwright().start()
        logger.info("Playwright started — contexts will launch lazily")

    async def shutdown(self) -> None:
        for client_id in list(self._contexts):
            await self._close_context(client_id)
        if self._playwright:
            await self._playwright.stop()
        logger.info("BrowserManager shut down")

    # -- context management ----------------------------------------------------

    async def get_or_create_context(
        self,
        client_id: str,
        user_agent: str | None = None,
    ) -> BrowserContext:
        if client_id in self._contexts:
            return self._contexts[client_id]

        if len(self._contexts) >= settings.max_contexts:
            raise RuntimeError(
                f"Max browser contexts reached ({settings.max_contexts})"
            )

        if self._playwright is None:
            raise RuntimeError("BrowserManager.startup() not called")

        profile_dir = self._profile_dir(client_id)
        profile_dir.mkdir(parents=True, exist_ok=True)

        context = await self._playwright.chromium.launch_persistent_context(
            user_data_dir=str(profile_dir),
            headless=settings.headless,
            args=[
                "--disable-blink-features=AutomationControlled",
                "--disable-dev-shm-usage",
                "--no-sandbox",
                # Restore the last session if Chromium recorded one
                # (mainly relevant after a crash). Tab restore is
                # backed up by `tabs.json` below for the clean-shutdown
                # case where Chromium drops the session anyway.
                "--restore-last-session",
            ],
            user_agent=user_agent
            or (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/131.0.0.0 Safari/537.36"
            ),
            viewport={"width": 1920, "height": 1080},
        )

        self._contexts[client_id] = context
        await self._restore_tabs(client_id, context)
        self._autosave_tasks[client_id] = asyncio.create_task(
            self._tabs_autosave_loop(client_id),
        )
        logger.info(
            "Created persistent browser context for client %s (profile=%s, pages=%d)",
            client_id, profile_dir, len(context.pages),
        )
        return context

    async def save_tabs(self, client_id: str) -> None:
        """Write the list of currently-open tab URLs to tabs.json so the
        next start of this client can re-open them. Called periodically
        and on shutdown."""
        ctx = self._contexts.get(client_id)
        if ctx is None:
            return
        urls: list[str] = []
        for page in ctx.pages:
            if page.is_closed():
                continue
            url = page.url or ""
            if url in ("", "about:blank"):
                continue
            urls.append(url)
        path = self._tabs_path(client_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"urls": urls}))
        logger.debug("Saved %d tab URL(s) for client %s", len(urls), client_id)

    async def close_context(self, client_id: str) -> None:
        await self._close_context(client_id)

    def get_context(self, client_id: str) -> BrowserContext | None:
        return self._contexts.get(client_id)

    @property
    def active_count(self) -> int:
        return len(self._contexts)

    @property
    def client_ids(self) -> list[str]:
        return list(self._contexts.keys())

    # -- private ---------------------------------------------------------------

    def _profile_dir(self, client_id: str) -> Path:
        return Path(settings.profiles_dir) / client_id / "chromium-profile"

    def _tabs_path(self, client_id: str) -> Path:
        return Path(settings.profiles_dir) / client_id / "tabs.json"

    async def _restore_tabs(self, client_id: str, context: BrowserContext) -> None:
        """Re-open the URLs from the previous session's tabs.json on top
        of whatever Chromium auto-restored. New page per URL, navigated
        in parallel — no waiting for full load (the agent will observe
        each tab on its own cycle)."""
        path = self._tabs_path(client_id)
        if not path.exists():
            return
        try:
            data = json.loads(path.read_text())
        except Exception:
            logger.warning("tabs.json corrupt for %s, ignoring", client_id)
            return
        urls = [u for u in data.get("urls", []) if isinstance(u, str) and u]
        if not urls:
            return

        # Map already-open page URLs (Chromium may have restored some)
        existing = {p.url for p in context.pages if not p.is_closed()}
        # Use the first auto-restored about:blank as the first slot if present.
        async def _open(url: str) -> None:
            try:
                page = await context.new_page()
                await page.goto(url, wait_until="commit", timeout=30_000)
            except Exception as e:
                logger.warning("Failed to restore tab %s: %s", url, e)

        targets = [u for u in urls if u not in existing]
        if not targets:
            return

        await asyncio.gather(*(_open(u) for u in targets))
        logger.info(
            "Restored %d tab(s) for client %s from tabs.json",
            len(targets), client_id,
        )

        # Drop any leftover about:blank if real tabs were restored.
        for page in list(context.pages):
            if page.url == "about:blank" and len(context.pages) > 1:
                try:
                    await page.close()
                except Exception:
                    pass

    async def _tabs_autosave_loop(self, client_id: str) -> None:
        try:
            while client_id in self._contexts:
                await asyncio.sleep(TAB_AUTOSAVE_INTERVAL_S)
                try:
                    await self.save_tabs(client_id)
                except Exception:
                    logger.exception("tabs autosave failed for %s", client_id)
        except asyncio.CancelledError:
            pass

    async def _close_context(self, client_id: str) -> None:
        task = self._autosave_tasks.pop(client_id, None)
        if task:
            task.cancel()
            try:
                await task
            except (asyncio.CancelledError, Exception):
                pass
        ctx = self._contexts.pop(client_id, None)
        if ctx:
            try:
                await self.save_tabs(client_id)
                await ctx.close()
            except Exception:
                logger.exception("Error closing context for %s", client_id)
