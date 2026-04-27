"""Outer runner for the LangGraph pod agent.

Wraps the compiled graph in a long-running task:
  - owns ToolContext (set via ContextVar before each invocation)
  - drives the observe-decide-act loop via `graph.astream`
  - adaptive sleep between iterations based on PodState
  - handles push_instruction (appended as HumanMessage)
  - survives LLM failures with exponential backoff

MFA is Authenticator-only (product §17): the agent reads the 2–3 digit
number from the screen and pushes it via `notify_user(kind='mfa',
mfa_code=N)`. The user approves on their phone. Nothing is typed back
into the browser — there is no `submit_mfa_code` path.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from playwright.async_api import BrowserContext

from app.agent.context import ToolContext, reset_pod_context, set_pod_context
from app.agent.graph import build_pod_graph
from app.agent.persistence import get_checkpointer
from app.agent.prompts import SYSTEM_PROMPT
from app.agent.state import PodAgentState
from app.agent.watcher import BrowserWatcher
from app.context_store import ContextStore, compose_cold_start_preamble
from app.pod_state import PodState, PodStateManager
from app.scrape_storage import ScrapeStorage
from app.tab_manager import TabRegistry

logger = logging.getLogger("o365-browser-pool.agent")

TICK_AFTER_ACTION_S = 2.0
TICK_LOADING_S = 4.0
TICK_AWAITING_MFA_S = 5.0
TICK_AUTHENTICATING_S = 4.0
TICK_ACTIVE_IDLE_S = 30.0
TICK_ACTIVE_LONG_IDLE_S = 120.0
LLM_BACKOFF_S = 15.0
MAX_BACKOFF_S = 120.0

# Stuck-loop detection: if the agent emits the same (tool_name, args_repr)
# this many times in a row, we cut the current invocation, notify the
# user / orchestrator, and transition the pod to ERROR until an
# `/instruction/` arrives. The pod runs nonstop so we can't rely on a
# LangGraph recursion_limit — we need to detect hand-spinning.
STUCK_REPEAT_THRESHOLD = 5


class PodAgent:
    """Public API for the pod agent.

    Methods:
        start() / stop() — lifecycle
        push_instruction(text) — enqueue a HumanMessage for next turn
    """

    def __init__(
        self,
        *,
        client_id: str,
        connection_id: str,
        browser_context: BrowserContext,
        state_manager: PodStateManager,
        tab_registry: TabRegistry,
        storage: ScrapeStorage,
        credentials: dict | None,
        login_url: str,
        capabilities: list[str],
        meeting_recorder=None,
        context_store: ContextStore | None = None,
    ) -> None:
        self.client_id = client_id
        self.connection_id = connection_id
        self.login_url = login_url
        self.capabilities = capabilities
        self.context_store = context_store
        self.ctx = ToolContext(
            client_id=client_id,
            connection_id=connection_id,
            browser_context=browser_context,
            state_manager=state_manager,
            tab_registry=tab_registry,
            storage=storage,
            credentials=credentials or {},
            meeting_recorder=meeting_recorder,
            capabilities=list(capabilities or []),
        )
        self._stop = asyncio.Event()
        self._task: asyncio.Task | None = None
        # Queue of HumanMessage bodies fed into the next invocation
        self._pending_inputs: asyncio.Queue[str] = asyncio.Queue()
        self._backoff_s = LLM_BACKOFF_S
        self._graph = None
        # Sliding window of recent (tool_name, args_repr) tuples for
        # stuck-loop detection. When the last N entries all match, we
        # break out of the graph invocation and notify.
        self._recent_tool_calls: list[tuple[str, str]] = []
        # Background DOM watcher (product §10a) — sensor only, pushes
        # priority HumanMessages via our push_instruction queue.
        self.watcher = BrowserWatcher(
            get_tabs=lambda: [
                (name, self.ctx.tab_registry.get(self.ctx.client_id, name))
                for entry in self.ctx.tab_registry.list(self.ctx.client_id)
                for name in [entry["name"]]
            ],
            push_instruction=self.push_instruction,
        )
        self.ctx.watcher = self.watcher

    # ---- Lifecycle -------------------------------------------------------

    async def start(self) -> None:
        if self._task and not self._task.done():
            return
        self._stop.clear()
        await self.watcher.start()
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        self._stop.set()
        try:
            await self.watcher.stop()
        except Exception:
            pass
        if self._task:
            try:
                await asyncio.wait_for(self._task, timeout=10)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                self._task.cancel()

    # ---- Public API ------------------------------------------------------

    def push_instruction(self, instruction: str) -> None:
        self._pending_inputs.put_nowait(
            f"INSTRUCTION from JERVIS (top priority): {instruction}"
        )
        logger.info("Agent: queued instruction (%d chars)", len(instruction))

    # ---- Internal --------------------------------------------------------

    def _adaptive_sleep(self) -> float:
        state = self.ctx.state_manager.state
        if state == PodState.AUTHENTICATING:
            return TICK_AUTHENTICATING_S
        if state == PodState.AWAITING_MFA:
            return TICK_AWAITING_MFA_S
        if state == PodState.ACTIVE:
            idle = (time.time() - self.ctx.last_dom_delta_ts) if self.ctx.last_dom_delta_ts else 0
            return TICK_ACTIVE_LONG_IDLE_S if idle > 600 else TICK_ACTIVE_IDLE_S
        if state == PodState.ERROR:
            return 60.0
        return TICK_AUTHENTICATING_S

    async def _compose_system_prompt(self) -> str:
        """Rebuild the SystemMessage every outer-loop entry (product §3b).
        Prompt improvements + pattern promotions roll out without rewriting
        the checkpoint. Falls back to static SYSTEM_PROMPT when no memory
        store is wired."""
        if self.context_store is None:
            return SYSTEM_PROMPT
        try:
            preamble = await compose_cold_start_preamble(
                self.context_store, connection_id=self.connection_id,
            )
        except Exception:
            preamble = ""
        if not preamble:
            return SYSTEM_PROMPT
        return f"{SYSTEM_PROMPT}\n\n{preamble}"

    def _initial_state(self) -> dict:
        return {
            "messages": [
                SystemMessage(content=SYSTEM_PROMPT),
                HumanMessage(content=(
                    f"Pod started for connection_id={self.connection_id}. "
                    f"login_url={self.login_url}. capabilities={self.capabilities}. "
                    "Cold start — follow the COLD START procedure from the system prompt: "
                    "read the state block below FIRST. "
                    "If every tab shows about:blank, open Teams directly via open_tab(). "
                    "If any tab has a known product or login URL, call "
                    "look_at_screen(reason='cold_start') on that tab. "
                    "Only call look_at_screen if the URL alone is not decisive."
                )),
            ],
            "client_id": self.client_id,
            "connection_id": self.connection_id,
            "login_url": self.login_url,
            "capabilities": self.capabilities,
            "pod_state": self.ctx.state_manager.state.value,
            "last_auth_request_at": None,
            "last_url": "",
            "last_app_state": "unknown",
            "last_observation_at": "",
            "last_observation_kind": "",
            "notified_contexts": [],
            "active_meeting": None,
            "pending_instructions": [],
            "last_dom_signature": None,
            "stuck_count": 0,
        }

    def _log_graph_event(self, event: dict) -> bool:
        """Log every LangGraph `updates` event — each turn of the react loop.

        `agent` node emits an AIMessage: we log its tool_calls (or final reply
        when there are none). `tools` node emits one or more ToolMessages: we
        log name + tool_call_id + full content. No truncation — debuggability
        wins over log size; log rotation handles volume.

        Returns True when a stuck-loop is detected (same tool_call name +
        args the last N times in a row). Caller should break out of the
        current astream() and transition to ERROR + notify_user.
        """
        import json as _json

        stuck = False
        for node_name, update in event.items():
            if node_name not in ("agent", "tools") or not isinstance(update, dict):
                continue
            for msg in update.get("messages") or []:
                if isinstance(msg, AIMessage):
                    if msg.tool_calls:
                        for tc in msg.tool_calls:
                            name = tc.get("name") or ""
                            args = tc.get("args") or {}
                            try:
                                args_repr = _json.dumps(
                                    args, sort_keys=True, default=str,
                                )
                            except Exception:
                                args_repr = repr(args)
                            logger.info(
                                "agent → tool_call name=%s id=%s args=%s",
                                name, tc.get("id"), args_repr,
                            )
                            self._recent_tool_calls.append((name, args_repr))
                            if len(self._recent_tool_calls) > STUCK_REPEAT_THRESHOLD:
                                self._recent_tool_calls.pop(0)
                            if (
                                len(self._recent_tool_calls) == STUCK_REPEAT_THRESHOLD
                                and len(set(self._recent_tool_calls)) == 1
                            ):
                                stuck = True
                    else:
                        logger.info("agent → final content=%r", msg.content or "")
                elif isinstance(msg, ToolMessage):
                    logger.info(
                        "tools ← result name=%s tool_call_id=%s content=%r",
                        getattr(msg, "name", "?"),
                        msg.tool_call_id,
                        msg.content,
                    )
        return stuck

    async def _on_stuck(self) -> None:
        """The agent repeated the same tool_call STUCK_REPEAT_THRESHOLD
        times in a row. This is an internal self-recovery signal — the
        user does NOT want a USER_TASK to appear in the chat asking
        them to "help the agent". Just log it, transition to ERROR
        (server sees the state via report_state), clear the loop
        memory, and inject a synthetic HumanMessage so the next agent
        turn knows to try a completely different approach instead of
        repeating the same tool call.

        Restart-from-ERROR is handled by the existing watcher /
        report_state(STARTING) recovery path — no notify, no task.

        AWAITING_MFA is exempt: the agent intentionally loops while
        polling for the user to approve the Authenticator push. The
        stuck detector must not escalate to ERROR in that state.
        """
        if self.ctx.state_manager.state == PodState.AWAITING_MFA:
            logger.info(
                "STUCK: ignoring in AWAITING_MFA state — agent is waiting for MFA approval"
            )
            self._recent_tool_calls.clear()
            return

        name, args_repr = self._recent_tool_calls[-1]
        logger.warning(
            "STUCK: agent repeated %s(%s) %d× — breaking out (no user task)",
            name, args_repr, STUCK_REPEAT_THRESHOLD,
        )
        self._recent_tool_calls.clear()
        try:
            await self.ctx.state_manager.transition(
                PodState.ERROR,
                reason=f"stuck: repeat {name} {STUCK_REPEAT_THRESHOLD}×",
            )
        except Exception:
            pass
        # Nudge the next iteration with concrete advice so the LLM
        # doesn't immediately re-emit the same call. The HumanMessage
        # gets prepended via `_pending_inputs` and read by `_next_input`.
        try:
            self._pending_inputs.put_nowait(
                f"Internal recovery hint: you just repeated `{name}` "
                f"{STUCK_REPEAT_THRESHOLD} times in a row with the same "
                f"arguments and it kept failing. Stop using that tool/"
                f"selector for this objective. Try a different approach "
                f"— inspect_dom with a broader selector, look_at_screen "
                f"to re-read the current view, or `press` a keyboard "
                f"shortcut. Do NOT call `{name}` again in the next "
                f"three turns."
            )
        except Exception:
            pass

    async def _next_input(self) -> HumanMessage:
        """Return the next user message for the agent.

        Drains `pending_inputs` first (instructions / MFA nudge / watcher
        alerts). Otherwise composes a fresh state + memory summary block
        so the agent has current context every tick — mirrors product §3b
        SystemMessage regeneration, but delivered as a HumanMessage since
        MessagesState does not support in-place system replacement.
        """
        if not self._pending_inputs.empty():
            return HumanMessage(content=self._pending_inputs.get_nowait())

        preamble = ""
        if self.context_store is not None:
            try:
                preamble = await compose_cold_start_preamble(
                    self.context_store, connection_id=self.connection_id,
                )
            except Exception:
                preamble = ""
        state_block = self._current_state_block()
        body_parts = [
            "Re-observe the current DOM and decide the next step.",
            state_block,
        ]
        if preamble:
            body_parts.append(preamble)
        return HumanMessage(content="\n\n".join(b for b in body_parts if b))

    def _current_state_block(self) -> str:
        """Compact current-state snapshot for the agent (product §3b)."""
        sm = self.ctx.state_manager
        tabs = self.ctx.tab_registry.list(self.ctx.client_id)
        tab_lines = [
            f"- {t['name']}: {t['url']}{' (closed)' if t.get('closed') else ''}"
            for t in tabs
        ]
        tab_block = "\n".join(tab_lines) if tab_lines else "- (no tabs registered)"
        login_email = (self.ctx.credentials or {}).get("email", "") or "(none)"
        caps = self.ctx.capabilities or []
        caps_str = ", ".join(caps) if caps else "(none — pod has no enabled features, idle)"
        return (
            "CURRENT STATE:\n"
            f"  pod_state: {sm.state.value}\n"
            f"  login_email: {login_email}  # the account this pod owns — if "
            f"an account-picker page asks which account to use, select the "
            f"tile matching this email.\n"
            f"  enabled_features: [{caps_str}]  # ONLY open tabs / scrape "
            f"products listed here. Subset of CHAT_READ, EMAIL_READ, "
            f"CALENDAR_READ. User-configured per connection — never "
            f"override based on what you observe in the browser.\n"
            f"  last_app_state: {self.ctx.last_app_state}\n"
            f"  last_observation_kind: {self.ctx.last_observation_kind or 'none'}\n"
            f"  last_observation_at: {self.ctx.last_observation_at or 'never'}\n"
            f"  tabs:\n{tab_block}"
        )

    async def _run(self) -> None:
        logger.info("LangGraph PodAgent starting for %s", self.client_id)

        try:
            checkpointer = get_checkpointer()
        except Exception:
            logger.exception("Failed to init checkpointer — bailing out")
            await self.ctx.state_manager.transition(
                PodState.ERROR, reason="Checkpointer init failed",
            )
            return

        sg = build_pod_graph(self.connection_id)
        self._graph = sg.compile(checkpointer=checkpointer)

        config: dict[str, Any] = {
            "configurable": {"thread_id": self.connection_id},
            # The pod agent runs nonstop — a single invocation can contain
            # an arbitrarily long tool-call chain (login, scrape cycles,
            # meeting joins, watcher alerts). We guard against true
            # hand-spinning via `_recent_tool_calls` / `_on_stuck` (see
            # above): STUCK_REPEAT_THRESHOLD identical tool_calls in a
            # row → break + notify_user(kind='error') + ERROR state.
            # LangGraph's recursion_limit is raised very high so it does
            # not fire before our semantic check.
            "recursion_limit": 10_000,
        }

        # Seed only if the thread has no previous state (first start for this
        # connection). Checkpointer picks up existing threads automatically.
        seed: dict | None = None
        try:
            existing = await self._graph.aget_state(config)
            if existing is None or not existing.values or not existing.values.get("messages"):
                seed = self._initial_state()
        except Exception:
            seed = self._initial_state()

        token = set_pod_context(self.ctx)
        try:
            while not self._stop.is_set():
                try:
                    if seed is not None:
                        invoke_input = seed
                        seed = None
                    else:
                        invoke_input = {"messages": [await self._next_input()]}

                    async for event in self._graph.astream(
                        invoke_input, config=config, stream_mode="updates",
                    ):
                        logger.info("graph event keys=%r", list(event.keys()) if isinstance(event, dict) else type(event).__name__)
                        stuck = self._log_graph_event(event)
                        if stuck:
                            await self._on_stuck()
                            break
                        if self._stop.is_set():
                            break

                    self._backoff_s = LLM_BACKOFF_S  # reset on success
                except asyncio.CancelledError:
                    raise
                except Exception:
                    logger.exception(
                        "Agent iteration crashed — backing off %.1fs",
                        self._backoff_s,
                    )
                    await asyncio.sleep(self._backoff_s)
                    self._backoff_s = min(self._backoff_s * 2, MAX_BACKOFF_S)
                    continue

                await asyncio.sleep(self._adaptive_sleep())
        except asyncio.CancelledError:
            pass
        finally:
            reset_pod_context(token)
            logger.info("LangGraph PodAgent stopped for %s", self.client_id)
