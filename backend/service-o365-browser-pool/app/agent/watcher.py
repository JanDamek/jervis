"""Background watcher — a sensor, not a controller (product §10a).

Runs every `O365_POOL_WATCHER_INTERVAL_S` (default 2 s) and performs one
pure `page.evaluate()` per registered tab to detect:

  - meeting_stage rising/falling edge
  - incoming_call toast rising edge
  - participant_count (when an active meeting is tracked)
  - alone_banner / meeting_ended_banner

It **never** clicks, navigates, or calls a tool. When it observes a
relevant edge, it enqueues a priority HumanMessage via
`PodAgent.push_instruction(...)` — the agent consumes it on the next
outer-loop entry and decides what to do.

Audio silence detection (ffmpeg `silencedetect`) is not implemented here
(out of scope for a DOM watcher); meeting_recorder could surface it via
a shared last_speech_at bookkeeping in the future.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field

from playwright.async_api import Page

from app.config import settings

logger = logging.getLogger("o365-browser-pool.watcher")


_PROBE_JS = r"""
() => ({
  meeting_stage: !!document.querySelector('[data-tid="meeting-stage"], [data-tid="calling-screen"]'),
  incoming_call: !!document.querySelector('[data-tid="call-toast"], [role="dialog"][aria-label*="incoming" i]'),
  participant_count: (() => {
    const el = document.querySelector('[data-tid="roster-button"] .count, [data-tid="roster-button"] span');
    if (!el) return null;
    const n = parseInt((el.textContent || '').trim(), 10);
    return Number.isFinite(n) ? n : null;
  })(),
  alone_banner: !!document.querySelector('[data-tid="alone-in-meeting-banner"]'),
  meeting_ended_banner: !!document.querySelector('[data-tid="meeting-ended"]'),
  incoming_caller: (() => {
    const el = document.querySelector('[data-tid="call-toast"] [class*="caller"], [data-tid="call-toast"] span');
    return el ? (el.getAttribute('aria-label') || el.textContent || '').trim() : null;
  })(),
  url: location.href,
})
"""


@dataclass
class TabState:
    """Per-tab rising/falling edge memory."""
    meeting_stage: bool = False
    incoming_call: bool = False
    participant_count: int | None = None
    alone_banner: bool = False
    meeting_ended_banner: bool = False
    last_participant_gt_1_at: float = 0.0
    alone_since: float | None = None


@dataclass
class WatcherState:
    """Cross-tab bookkeeping — what's currently active from the watcher's POV."""
    active_meeting_id: str | None = None
    active_meeting_tab: str | None = None
    joined_by: str = "user"
    scheduled_start_at: float | None = None
    max_participants_seen: int = 0
    prestart_wait_min: int = 15
    late_arrival_alone_min: int = 1
    alone_after_activity_min: int = 2
    user_alone_notify_wait_min: int = 5
    user_notify_sent_at: float | None = None
    user_notify_user_replied: bool = False
    per_tab: dict[str, TabState] = field(default_factory=dict)


class BrowserWatcher:
    """Lightweight pure-DOM watcher. One per pod.

    Usage:
        watcher = BrowserWatcher(get_tabs=lambda: [(name, page), ...],
                                 push_instruction=agent.push_instruction)
        await watcher.start()
        ...
        await watcher.stop()

    The `get_tabs` callback returns a fresh list on each tick — the
    watcher does not care which pages exist when; new tabs are picked up
    transparently.
    """

    def __init__(
        self,
        *,
        get_tabs,
        push_instruction,
    ) -> None:
        self._get_tabs = get_tabs
        self._push = push_instruction
        self._state = WatcherState(
            prestart_wait_min=settings.meeting_prestart_wait_min,
            late_arrival_alone_min=settings.meeting_late_arrival_alone_min,
            alone_after_activity_min=settings.meeting_alone_after_activity_min,
            user_alone_notify_wait_min=settings.meeting_user_alone_notify_wait_min,
        )
        self._stop = asyncio.Event()
        self._task: asyncio.Task | None = None

    # ---- Lifecycle -------------------------------------------------------

    async def start(self) -> None:
        if self._task and not self._task.done():
            return
        self._stop.clear()
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        self._stop.set()
        if self._task:
            try:
                await asyncio.wait_for(self._task, timeout=5)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                self._task.cancel()

    # ---- Active meeting bookkeeping (called by the agent via tools) ------

    def set_active_meeting(
        self,
        *,
        meeting_id: str,
        tab_name: str,
        joined_by: str = "user",
        scheduled_start_at_epoch: float | None = None,
    ) -> None:
        self._state.active_meeting_id = meeting_id
        self._state.active_meeting_tab = tab_name
        self._state.joined_by = joined_by
        self._state.scheduled_start_at = scheduled_start_at_epoch
        self._state.max_participants_seen = 0
        self._state.user_notify_sent_at = None
        self._state.user_notify_user_replied = False

    def clear_active_meeting(self) -> None:
        self._state.active_meeting_id = None
        self._state.active_meeting_tab = None
        self._state.scheduled_start_at = None
        self._state.max_participants_seen = 0
        self._state.user_notify_sent_at = None
        self._state.user_notify_user_replied = False

    def mark_user_replied(self) -> None:
        self._state.user_notify_user_replied = True

    # ---- Main loop -------------------------------------------------------

    async def _run(self) -> None:
        interval = max(1, int(settings.watcher_interval_seconds))
        logger.info("Watcher started — interval=%ds", interval)
        try:
            while not self._stop.is_set():
                try:
                    await self._tick()
                except Exception:
                    logger.exception("Watcher tick failed")
                await asyncio.sleep(interval)
        except asyncio.CancelledError:
            pass
        logger.info("Watcher stopped")

    async def _tick(self) -> None:
        now = time.time()
        tabs = self._get_tabs() or []
        for name, page in tabs:
            if page is None or page.is_closed():
                self._state.per_tab.pop(name, None)
                continue
            snap = await self._probe(page)
            if snap is None:
                continue
            tab = self._state.per_tab.setdefault(name, TabState())
            self._handle_snapshot(name, tab, snap, now)

    async def _probe(self, page: Page) -> dict | None:
        try:
            return await page.evaluate(_PROBE_JS)
        except Exception as e:
            logger.debug("probe failed: %s", e)
            return None

    def _handle_snapshot(
        self, tab_name: str, tab: TabState, snap: dict, now: float,
    ) -> None:
        # meeting_stage edges
        prev_stage = tab.meeting_stage
        tab.meeting_stage = bool(snap.get("meeting_stage"))
        if tab.meeting_stage and not prev_stage:
            self._emit_stage_rising(tab_name, snap)
        elif prev_stage and not tab.meeting_stage:
            self._emit_stage_falling(tab_name)

        # incoming_call rising edge
        prev_call = tab.incoming_call
        tab.incoming_call = bool(snap.get("incoming_call"))
        if tab.incoming_call and not prev_call:
            caller = snap.get("incoming_caller") or "?"
            self._push(
                f"WATCHER_ALERT: incoming call toast on tab '{tab_name}' "
                f"from '{caller}'. NEVER click accept. Call "
                f"notify_user(kind='urgent_message', "
                f"sender='{caller}', preview='Příchozí hovor od {caller}')."
            )

        # Active meeting signals
        if self._state.active_meeting_id and tab_name == self._state.active_meeting_tab:
            self._handle_meeting_signals(tab, snap, now)

    def _emit_stage_rising(self, tab_name: str, snap: dict) -> None:
        if self._state.active_meeting_id is None:
            self._push(
                f"WATCHER_ALERT: meeting_stage appeared on tab '{tab_name}'. "
                f"Likely a user-joined meeting. Call "
                f"start_meeting_recording(meeting_id='', title='', "
                f"joined_by='user', tab_name='{tab_name}') and then "
                f"meeting_presence_report(present=true, "
                f"meeting_stage_visible=true)."
            )
        else:
            # Pod was expecting this (scheduled join) — mark stage visible.
            logger.info("meeting_stage rising confirmed for %s",
                        self._state.active_meeting_id)

    def _emit_stage_falling(self, tab_name: str) -> None:
        if self._state.active_meeting_id is None:
            return
        mid = self._state.active_meeting_id
        self._push(
            f"WATCHER_ALERT: meeting_stage disappeared on tab '{tab_name}'. "
            f"Active meeting '{mid}' ended. Call "
            f"stop_meeting_recording(meeting_id='{mid}') and "
            f"meeting_presence_report(present=false, "
            f"meeting_stage_visible=false)."
        )
        self.clear_active_meeting()

    def _handle_meeting_signals(
        self, tab: TabState, snap: dict, now: float,
    ) -> None:
        st = self._state
        mid = st.active_meeting_id
        if mid is None:
            return

        # Participant tracking
        pc = snap.get("participant_count")
        if isinstance(pc, int):
            tab.participant_count = pc
            if pc > st.max_participants_seen:
                st.max_participants_seen = pc
            if pc > 1:
                tab.last_participant_gt_1_at = now
                tab.alone_since = None
            elif pc <= 1 and tab.alone_since is None:
                tab.alone_since = now
        # Banners
        if snap.get("meeting_ended_banner"):
            self._push(
                f"WATCHER_ALERT: meeting_ended_banner visible. Call "
                f"leave_meeting(meeting_id='{mid}', "
                f"reason='meeting_ended_banner') immediately."
            )
            return
        if snap.get("alone_banner") and tab.alone_since is None:
            tab.alone_since = now

        if tab.alone_since is None:
            return

        alone_for = now - tab.alone_since
        alone_min = alone_for / 60.0

        if st.joined_by == "user":
            self._handle_user_alone(mid, alone_min, now)
        else:
            self._handle_agent_alone(mid, alone_min, now)

    def _handle_user_alone(self, mid: str, alone_min: float, now: float) -> None:
        st = self._state
        if alone_min < 1:
            return
        if st.user_notify_sent_at is None:
            st.user_notify_sent_at = now
            self._push(
                f"WATCHER_ALERT: you have been alone in meeting '{mid}' "
                f"for over 1 minute. Call notify_user("
                f"kind='meeting_alone_check', meeting_id='{mid}', "
                f"preview='Pořád jsi v meetingu. Ještě ho potřebuješ?'). "
                f"Wait for user response or "
                f"{st.user_alone_notify_wait_min} min."
            )
            return
        waited_min = (now - st.user_notify_sent_at) / 60.0
        if (
            not st.user_notify_user_replied
            and waited_min >= st.user_alone_notify_wait_min
        ):
            st.user_notify_user_replied = True  # don't re-emit
            self._push(
                f"WATCHER_ALERT: no user response in "
                f"{st.user_alone_notify_wait_min} min to alone-check for "
                f"meeting '{mid}'. Call leave_meeting("
                f"meeting_id='{mid}', reason='no_user_response') then "
                f"notify_user(kind='info', message='Odešel jsem "
                f"z prázdného meetingu, {st.user_alone_notify_wait_min} "
                f"min bez reakce.')."
            )

    def _handle_agent_alone(self, mid: str, alone_min: float, now: float) -> None:
        st = self._state
        if st.max_participants_seen <= 1:
            # Nobody ever joined
            if st.scheduled_start_at is None:
                # No schedule info — treat like generic no-show after prestart window
                if alone_min >= st.prestart_wait_min:
                    self._push(
                        f"WATCHER_ALERT: {st.prestart_wait_min} min past "
                        f"start in meeting '{mid}' with nobody here. "
                        f"Call leave_meeting(meeting_id='{mid}', "
                        f"reason='no_show')."
                    )
                return
            elapsed_since_start = now - st.scheduled_start_at
            elapsed_min = elapsed_since_start / 60.0
            if elapsed_min < 0:
                return  # scheduled in future; ignore
            if elapsed_min < st.prestart_wait_min:
                # Within pre-start wait; stay quiet unless late arrival
                if elapsed_min > 5 and alone_min >= st.late_arrival_alone_min:
                    self._push(
                        f"WATCHER_ALERT: late arrival ({elapsed_min:.0f} "
                        f"min past start) into meeting '{mid}' and nobody "
                        f"is here. Call leave_meeting(meeting_id='{mid}', "
                        f"reason='late_arrival_empty')."
                    )
                return
            # Past prestart window
            self._push(
                f"WATCHER_ALERT: {st.prestart_wait_min} min past start in "
                f"meeting '{mid}' with nobody here. Call "
                f"leave_meeting(meeting_id='{mid}', reason='no_show')."
            )
            return
        # max_participants_seen > 1: people were here, now gone
        if alone_min >= st.alone_after_activity_min:
            self._push(
                f"WATCHER_ALERT: everyone left meeting '{mid}' "
                f"({alone_min:.0f} min alone after activity). Call "
                f"leave_meeting(meeting_id='{mid}', "
                f"reason='post_activity_alone')."
            )
