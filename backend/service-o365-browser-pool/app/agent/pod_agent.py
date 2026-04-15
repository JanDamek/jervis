"""PodAgent — single autonomous loop driving the entire pod lifecycle.

Replaces the old ai_login + HealthLoop + ChatMonitor + per-component logic.
One agent observes the page, picks the highest-priority pending goal, asks
the Reasoner for an action, runs it via Executor, reports state changes,
sleeps adaptively, repeats. Stuck observations escalate to ask_user → ERROR.
"""

from __future__ import annotations

import asyncio
import logging
import re
import time
from pathlib import Path
from typing import Optional

from playwright.async_api import BrowserContext, Page

from app.agent.executor import Action, ActionResult, execute
from app.agent.goals import (
    AuthGoal, Goal, InstructionGoal, NewMessageGoal, PeriodicScrapeGoal,
)
from app.agent.observer import Observation, observe
from app.agent.reasoner import decide
from app.config import settings
from app.pod_state import PodState, PodStateManager

logger = logging.getLogger("o365-browser-pool.agent")

# Adaptive sleep ranges
TICK_AFTER_ACTION_S = 2.0     # immediately after taking an action
TICK_LOADING_S = 4.0          # page is loading
TICK_AWAITING_MFA_S = 5.0     # while waiting for user to approve in Authenticator
TICK_AUTHENTICATING_S = 4.0   # actively driving login
TICK_ACTIVE_IDLE_S = 30.0     # ACTIVE and nothing to do
TICK_ACTIVE_LONG_IDLE_S = 120.0  # ACTIVE for a long time, no events

# Stuck-detection: same observation signature N times in a row → ask_user
STUCK_THRESHOLD = 4

# Default scrape intervals per tab (seconds)
DEFAULT_SCRAPE_INTERVALS = {
    "chat": 300,
    "email": 900,
    "calendar": 1800,
}


class PodAgent:
    def __init__(
        self,
        client_id: str,
        connection_id: str,
        browser_context: BrowserContext,
        state_manager: PodStateManager,
        credentials: Optional[dict],
        login_url: str = "https://teams.microsoft.com",
        capabilities: Optional[list[str]] = None,
        scraper=None,  # ScreenScraper instance for periodic scrape
        tab_manager=None,
    ) -> None:
        self.client_id = client_id
        self.connection_id = connection_id
        self.context = browser_context
        self.sm = state_manager
        self.credentials = credentials
        self.login_url = login_url
        self.capabilities = capabilities or []
        self.scraper = scraper
        self.tab_manager = tab_manager

        self._task: Optional[asyncio.Task] = None
        self._stop = asyncio.Event()
        self._goals: list[Goal] = []
        self._history: list[dict] = []
        self._stuck_count = 0
        self._last_signature: str = ""
        self._idle_since: float = time.time()

        # Bootstrap goals
        self._goals.append(AuthGoal(target_url=self.login_url))
        for cap in self.capabilities:
            tab = _capability_to_tab(cap)
            if tab:
                self._goals.append(PeriodicScrapeGoal(
                    tab=tab,
                    interval_s=DEFAULT_SCRAPE_INTERVALS.get(tab, 300),
                ))
        # NewMessage goal — sits idle until ChatMonitor-style detection triggers it.
        # (Detection wired in periodic_after_active_check below.)
        self._goals.append(NewMessageGoal())

    # ---- Public API ------------------------------------------------------

    async def start(self) -> None:
        if self._task and not self._task.done():
            return
        self._stop.clear()
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        self._stop.set()
        if self._task:
            try:
                await asyncio.wait_for(self._task, timeout=10)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                self._task.cancel()

    def push_instruction(self, instruction: str) -> None:
        """Queue a JERVIS instruction as a top-priority goal."""
        self._goals.append(InstructionGoal(instruction=instruction))
        logger.info("Agent: queued instruction (%d chars)", len(instruction))

    async def submit_mfa_code(self, page: Page, code: str) -> bool:
        """Fill MFA code input and submit. Called by routes/session.py."""
        # Use Executor's fill mechanism with submit
        action = Action(type="fill", target="Code", value=code, submit=True,
                        reason="user-supplied MFA code")
        result = await execute(page, action)
        if result.success:
            # Force re-evaluation soon
            self._idle_since = time.time()
        return result.success

    # ---- Internal loop ---------------------------------------------------

    def _page(self) -> Optional[Page]:
        pages = [p for p in self.context.pages if not p.is_closed()]
        return pages[0] if pages else None

    def _pick_goal(self, obs: Observation) -> Optional[Goal]:
        # Drop completed instructions
        self._goals = [g for g in self._goals if not (
            isinstance(g, InstructionGoal) and g.completed
        )]
        # Sort by priority; pick first not-satisfied + due
        now = time.time()
        for g in sorted(self._goals, key=lambda x: x.priority):
            if not g.is_satisfied(obs) and g.is_due(now):
                return g
        return None

    def _adaptive_sleep(self) -> float:
        state = self.sm.state
        if state == PodState.AUTHENTICATING:
            return TICK_AUTHENTICATING_S
        if state == PodState.AWAITING_MFA:
            return TICK_AWAITING_MFA_S
        if state == PodState.ACTIVE:
            idle = time.time() - self._idle_since
            return TICK_ACTIVE_LONG_IDLE_S if idle > 600 else TICK_ACTIVE_IDLE_S
        if state == PodState.ERROR:
            # Wait for JERVIS instruction; don't burn LLM/VLM on stuck screens
            return 60.0
        return TICK_AUTHENTICATING_S  # STARTING/RECOVERING etc.

    async def _run(self) -> None:
        logger.info("Agent: starting (client=%s)", self.client_id)
        # On STARTING, may need a kick to navigate
        if self.sm.state == PodState.STARTING:
            page = self._page()
            if page:
                url = page.url or ""
                if not url or url.startswith("about:") or "data:" in url:
                    try:
                        await page.goto(self.login_url, wait_until="domcontentloaded", timeout=30000)
                        await asyncio.sleep(3)
                    except Exception:
                        pass

        try:
            while not self._stop.is_set():
                page = self._page()
                if page is None:
                    await asyncio.sleep(5)
                    continue

                obs = await observe(page)
                logger.info(
                    "Agent: observation app_shells=%s loading=%s headings=%d "
                    "buttons=%d inputs=%d error=%s",
                    obs.app_shells, obs.is_loading, len(obs.headings),
                    len(obs.buttons), len(obs.inputs), bool(obs.error_text),
                )

                # State sync — if app shell is visible and we're not already ACTIVE, go ACTIVE
                if obs.app_shells and self.sm.state != PodState.ACTIVE:
                    await self.sm.transition(
                        PodState.ACTIVE,
                        reason=f"App shell visible: {obs.app_shells}",
                    )
                    self._idle_since = time.time()
                    # Setup scraping tabs after first reaching ACTIVE
                    await self._on_become_active(page)

                # Stuck detection — but never escalate while AWAITING_MFA: the
                # screen IS supposed to stay the same while the user approves.
                sig = obs.signature()
                if sig == self._last_signature:
                    self._stuck_count += 1
                else:
                    self._stuck_count = 0
                    self._last_signature = sig

                if (self._stuck_count >= STUCK_THRESHOLD
                        and self.sm.state != PodState.AWAITING_MFA):
                    await self._handle_stuck(page, obs)
                    self._stuck_count = 0
                    await asyncio.sleep(self._adaptive_sleep())
                    continue

                # Pick goal
                goal = self._pick_goal(obs)
                if goal is None:
                    # Nothing to do — adaptive sleep
                    await asyncio.sleep(self._adaptive_sleep())
                    continue

                # PeriodicScrape: bypass Reasoner — just call scraper directly
                if isinstance(goal, PeriodicScrapeGoal):
                    await self._run_periodic_scrape(goal)
                    continue

                # Otherwise reason + execute
                ctx = goal.context(obs)
                action = await decide(
                    obs, ctx.description, self._history,
                    credentials=self.credentials,
                    extra_context=ctx.extra_context,
                )
                value_info = ""
                if action.value is not None:
                    val = action.value
                    is_email = "@" in val and "." in val
                    value_info = f" value=({'email-like' if is_email else 'text'}, len={len(val)})"
                logger.info(
                    "Agent: goal=%s action=%s target=%r%s reason=%s",
                    goal.name, action.type, action.target, value_info, action.reason,
                )

                # Special handling: MFA waits transition state explicitly
                if "wait_mfa" in (action.reason or "").lower() or _action_implies_mfa(action):
                    await self._enter_awaiting_mfa(obs, action)

                # Special handling: ask_user → ERROR + screenshot, agent waits for instruction
                if action.type == "ask_user":
                    await self._enter_error_for_user_help(page, obs, action)
                    goal.on_action_done(action.type, False)
                    await asyncio.sleep(self._adaptive_sleep())
                    continue

                if action.type == "error":
                    await self._enter_error_hard(page, obs, action)
                    goal.on_action_done(action.type, False)
                    await asyncio.sleep(self._adaptive_sleep())
                    continue

                if action.type == "navigate" and not action.target:
                    # Default navigate target to login_url
                    action.target = self.login_url

                if action.type == "done":
                    goal.on_action_done(action.type, True)
                    await asyncio.sleep(TICK_AFTER_ACTION_S)
                    continue

                # Inject credentials for password/email fills — LLM is not
                # given the password and only sees the email for tile-picking.
                self._inject_credential_value(action)

                # Execute
                result = await execute(page, action)
                self._record_history(action, result)
                goal.on_action_done(action.type, result.success)

                if not result.success:
                    logger.warning("Agent: action failed: %s", result.error)

                # After action, brief settle pause then loop
                await asyncio.sleep(
                    TICK_LOADING_S if obs.is_loading else TICK_AFTER_ACTION_S
                )
        except asyncio.CancelledError:
            pass
        except Exception:
            logger.exception("Agent: loop crashed")
            await self.sm.transition(PodState.ERROR, reason="Agent loop crashed")

    # ---- State transitions / side effects --------------------------------

    async def _enter_awaiting_mfa(self, obs: Observation, action: Action) -> None:
        if self.sm.state == PodState.AWAITING_MFA:
            return
        # Try to extract a 2-3 digit number from headings (Authenticator number-match)
        mfa_number = None
        for h in obs.headings:
            m = re.search(r"\b(\d{2,3})\b", h)
            if m:
                mfa_number = m.group(1)
                break
        # Distinguish push-approval vs code entry — if visible inputs include code-like field
        is_code_entry = any(
            i.type in ("text", "tel") and ("code" in i.label.lower() or "kód" in i.label.lower())
            for i in obs.inputs
        )
        mfa_type = "authenticator_code" if is_code_entry else (
            "authenticator_number" if mfa_number else "authenticator_push"
        )
        msg = "Potvrďte přihlášení v Microsoft Authenticator"
        if mfa_number:
            msg += f". Zadejte číslo: {mfa_number}"
        elif is_code_entry:
            msg = "Zadejte kód z Microsoft Authenticator"

        # Transition AUTHENTICATING first if needed
        if self.sm.state not in (PodState.AUTHENTICATING, PodState.RECOVERING):
            await self.sm.transition(
                PodState.AUTHENTICATING, reason="Driving login flow",
            )
        await self.sm.transition(
            PodState.AWAITING_MFA,
            mfa_type=mfa_type, mfa_message=msg, mfa_number=mfa_number,
        )

    async def _enter_error_for_user_help(
        self, page: Page, obs: Observation, action: Action,
    ) -> None:
        screenshot = await self._save_screenshot(page, "ask-user")
        await self.sm.transition(
            PodState.ERROR,
            reason=f"Agent needs user help: {action.reason}",
            screenshot_path=screenshot,
            vlm_description=(obs.vlm_description or "")[:500],
        )

    async def _enter_error_hard(
        self, page: Page, obs: Observation, action: Action,
    ) -> None:
        screenshot = await self._save_screenshot(page, "hard-error")
        await self.sm.transition(
            PodState.ERROR,
            reason=action.reason or "Agent reported error",
            screenshot_path=screenshot,
            vlm_description=obs.error_text or obs.vlm_description or "",
        )

    async def _handle_stuck(self, page: Page, obs: Observation) -> None:
        logger.warning(
            "Agent: stuck (%d ticks on same screen) — escalating to user",
            STUCK_THRESHOLD,
        )
        screenshot = await self._save_screenshot(page, "stuck")
        await self.sm.transition(
            PodState.ERROR,
            reason=f"Agent stuck on the same screen for {STUCK_THRESHOLD} attempts",
            screenshot_path=screenshot,
            vlm_description=(obs.vlm_description or obs.visible_text[:300] or ""),
        )

    async def _save_screenshot(self, page: Page, tag: str) -> str:
        try:
            path = Path(settings.profiles_dir) / self.client_id / f"{tag}.jpg"
            path.parent.mkdir(parents=True, exist_ok=True)
            await page.screenshot(path=str(path), type="jpeg", quality=80)
            return str(path)
        except Exception:
            return ""

    async def _on_become_active(self, page: Page) -> None:
        if not self.tab_manager:
            return
        try:
            await self.tab_manager.setup_tabs(self.client_id, self.context, self.capabilities)
            available = self.tab_manager.get_available_capabilities(self.client_id)
            from app.kotlin_callback import notify_capabilities_discovered
            await notify_capabilities_discovered(self.client_id, self.connection_id, available)
            if self.scraper:
                self.scraper.set_connection_id(self.client_id, self.connection_id)
                await self.scraper.start_scraping(self.client_id)
        except Exception:
            logger.exception("Agent: post-active setup failed")

    async def _run_periodic_scrape(self, goal: PeriodicScrapeGoal) -> None:
        if goal.in_progress or self.scraper is None:
            goal.last_run = time.time()
            return
        goal.in_progress = True
        try:
            from app.tab_manager import TabType
            tab_type = TabType[goal.tab.upper()]
            await self.scraper._scrape_tab(self.client_id, tab_type)
            logger.info("Agent: periodic scrape done for %s", goal.tab)
        except Exception:
            logger.exception("Agent: periodic scrape failed for %s", goal.tab)
        finally:
            goal.mark_done()

    def _inject_credential_value(self, action: Action) -> None:
        """For credential fills, set value from credentials regardless of what
        the LLM provided. Keeps the password out of the LLM prompt entirely."""
        if action.type != "fill" or not self.credentials:
            return
        target_lower = (action.target or "").lower()
        is_password = "password" in target_lower or "heslo" in target_lower
        is_code = (("code" in target_lower or "kód" in target_lower or "kod" in target_lower)
                   and not is_password)
        is_email = (("email" in target_lower or "username" in target_lower
                     or " user " in f" {target_lower} ")
                    and not is_password and not is_code)

        if is_password:
            real = self.credentials.get("password")
            if real:
                action.value = real
        elif is_email:
            real = self.credentials.get("email")
            if real:
                action.value = real
        # MFA code: PodAgent.submit_mfa_code() handles that path; if a Reasoner
        # accidentally tries to fill it, leave whatever value it provided.

    def _record_history(self, action: Action, result: ActionResult) -> None:
        self._history.append({
            "action": action.type,
            "target": action.target,
            "reason": action.reason,
            "result": "ok" if result.success else f"fail: {result.error or '?'}",
        })
        if len(self._history) > 20:
            self._history = self._history[-20:]


# ---- Helpers -----------------------------------------------------------


_CAPABILITY_TAB_MAP = {
    "CHAT_READ": "chat",
    "EMAIL_READ": "email",
    "CALENDAR_READ": "calendar",
}


def _capability_to_tab(cap: str) -> Optional[str]:
    return _CAPABILITY_TAB_MAP.get(cap.upper())


def _action_implies_mfa(action: Action) -> bool:
    reason = (action.reason or "").lower()
    if action.type == "wait" and ("mfa" in reason or "authenticator" in reason
                                   or "approval" in reason or "ověření" in reason):
        return True
    return False
