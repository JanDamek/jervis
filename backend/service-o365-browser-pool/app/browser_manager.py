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

        # Migration: pre-existing state.json (cookies + localStorage from
        # the previous BrowserManager) gets injected into the persistent
        # context after first launch so sessions don't break. localStorage
        # injection requires running JS in each origin — best-effort, the
        # important auth payload is in cookies.
        legacy_state = self._legacy_state_path(client_id)
        do_migration = legacy_state.exists() and not (profile_dir / "Default").exists()

        # Clean stale Chromium singleton locks. After a pod kill (k8s
        # restart, OOM, eviction) Chromium leaves SingletonLock /
        # SingletonCookie / SingletonSocket symlinks pointing at the
        # previous pod's hostname. New Chromium refuses to launch with
        # `Target page, context or browser has been closed` because it
        # interprets the lock as another live instance. Safe to remove —
        # we own the profile dir.
        self._unlink_stale_singletons(profile_dir)

        # Suppress "Restore Pages?" prompt on launch. After a pod kill
        # Chromium considers the previous shutdown unclean and shows a
        # yellow infobar / dialog asking the user to restore. The agent
        # has no way to dismiss it without VNC. Tab URLs are owned by
        # `tabs.json` (saved every 30 s); in-Chromium session restore
        # is redundant.
        self._mark_chromium_exit_clean(profile_dir)

        context = await self._playwright.chromium.launch_persistent_context(
            user_data_dir=str(profile_dir),
            headless=settings.headless,
            args=[
                "--disable-blink-features=AutomationControlled",
                "--disable-dev-shm-usage",
                "--no-sandbox",
                # Crash-recovery session restore stays on for the case where
                # tabs.json hasn't been written yet (cold start within first
                # 30 s after pod restart).
                "--restore-last-session",
                # Skip "Are you sure you want to restore?" dialogs in
                # combination with the Preferences hack above.
                "--disable-session-crashed-bubble",
                "--disable-infobars",
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
        if do_migration:
            await self._migrate_legacy_state(client_id, context, legacy_state)
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

    def _legacy_state_path(self, client_id: str) -> Path:
        # Where the previous BrowserManager wrote `storage_state` JSON.
        return Path(settings.profiles_dir) / client_id / "state.json"

    def _unlink_stale_singletons(self, profile_dir: Path) -> None:
        """Remove SingletonLock / SingletonCookie / SingletonSocket from
        the profile dir before relaunching Chromium. They are symlinks
        to the previous pod's hostname; with the new hostname Chromium
        treats them as another live instance and aborts.

        Safe because each pod owns its own profile dir (one client per
        pod) — there cannot be a concurrent Chromium that we would race."""
        for name in ("SingletonLock", "SingletonCookie", "SingletonSocket"):
            path = profile_dir / name
            try:
                if path.is_symlink() or path.exists():
                    path.unlink(missing_ok=True)
            except Exception as e:
                logger.warning("Failed to unlink %s: %s", path, e)

    def _mark_chromium_exit_clean(self, profile_dir: Path) -> None:
        """Edit the Chromium Preferences JSON to declare the last exit as
        clean, so the next launch never shows the 'Restore Pages?' bar.

        Called BEFORE every launch; after Chromium starts it owns the file
        and writes the actual exit state on its own shutdown."""
        prefs_path = profile_dir / "Default" / "Preferences"
        if not prefs_path.exists():
            # Fresh profile — nothing to patch; Chromium will create
            # Preferences with sensible defaults on first run.
            return
        try:
            data = json.loads(prefs_path.read_text())
        except Exception:
            logger.warning("Preferences JSON unreadable, skipping exit-state patch")
            return
        profile = data.setdefault("profile", {})
        if profile.get("exit_type") == "Normal" and profile.get("exited_cleanly") is True:
            return
        profile["exit_type"] = "Normal"
        profile["exited_cleanly"] = True
        try:
            prefs_path.write_text(json.dumps(data))
            logger.info("Marked Chromium exit as clean to skip restore prompt")
        except Exception as e:
            logger.warning("Failed to patch Preferences: %s", e)

    async def _migrate_legacy_state(
        self, client_id: str, context: BrowserContext, legacy_state: Path,
    ) -> None:
        """One-shot migration from the old `state.json` to the persistent
        profile. Cookies are added directly via Playwright API. localStorage
        is left to be re-built by the agent's normal navigation —
        re-login may be needed on origins that depend on it. Cookies
        carry the auth payload for O365, so this preserves sessions in
        practice.

        On success the `state.json` is renamed `state.json.migrated` so
        the migration runs at most once per client."""
        try:
            state = json.loads(legacy_state.read_text())
        except Exception as e:
            logger.warning("Legacy state.json unreadable for %s: %s", client_id, e)
            return
        cookies = state.get("cookies") or []
        if cookies:
            try:
                await context.add_cookies(cookies)
                logger.info(
                    "Migrated %d cookies from legacy state.json for %s",
                    len(cookies), client_id,
                )
            except Exception:
                logger.exception("add_cookies failed for %s", client_id)
        try:
            legacy_state.rename(legacy_state.with_suffix(".json.migrated"))
        except Exception:
            pass

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
