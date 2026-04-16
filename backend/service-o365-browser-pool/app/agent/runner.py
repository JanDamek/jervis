"""Outer runner for the LangGraph pod agent.

Wraps the compiled graph in a long-running task:
  - owns ToolContext (set via ContextVar before each invocation)
  - drives the observe-decide-act loop via `graph.astream`
  - adaptive sleep between iterations based on PodState
  - handles push_instruction (appended as HumanMessage)
  - handles submit_mfa_code (credentials.pending_mfa_code + nudge message)
  - survives LLM failures with exponential backoff
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


class PodAgent:
    """Public API for the pod agent — matches the old interface.

    Methods:
        start() / stop() — lifecycle
        push_instruction(text) — enqueue a HumanMessage for next turn
        submit_mfa_code(code) — inject MFA code + nudge agent
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
    ) -> None:
        self.client_id = client_id
        self.connection_id = connection_id
        self.login_url = login_url
        self.capabilities = capabilities
        self.ctx = ToolContext(
            client_id=client_id,
            connection_id=connection_id,
            browser_context=browser_context,
            state_manager=state_manager,
            tab_registry=tab_registry,
            storage=storage,
            credentials=credentials or {},
            meeting_recorder=meeting_recorder,
        )
        self._stop = asyncio.Event()
        self._task: asyncio.Task | None = None
        # Queue of HumanMessage bodies fed into the next invocation
        self._pending_inputs: asyncio.Queue[str] = asyncio.Queue()
        self._backoff_s = LLM_BACKOFF_S
        self._graph = None

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
                await asyncio.wait_for(self._task, timeout=10)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                self._task.cancel()

    # ---- Public API ------------------------------------------------------

    def push_instruction(self, instruction: str) -> None:
        self._pending_inputs.put_nowait(
            f"INSTRUCTION from JERVIS (top priority): {instruction}"
        )
        logger.info("Agent: queued instruction (%d chars)", len(instruction))

    async def submit_mfa_code(self, code: str) -> bool:
        self.ctx.credentials["pending_mfa_code"] = code
        self._pending_inputs.put_nowait(
            "User supplied MFA code — call fill_credentials(selector=<code input>, field='mfa') "
            "then press 'Enter'."
        )
        return True

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

    def _initial_state(self) -> dict:
        return {
            "messages": [
                SystemMessage(content=SYSTEM_PROMPT),
                HumanMessage(content=(
                    f"Pod started for connection_id={self.connection_id}. "
                    f"login_url={self.login_url}. capabilities={self.capabilities}. "
                    "Cold start — first action: look_at_screen(reason='cold_start')."
                )),
            ],
            "client_id": self.client_id,
            "connection_id": self.connection_id,
            "login_url": self.login_url,
            "capabilities": self.capabilities,
            "pod_state": self.ctx.state_manager.state.value,
            "pending_mfa_code": None,
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

    def _log_graph_event(self, event: dict) -> None:
        """Log every LangGraph `updates` event — each turn of the react loop.

        `agent` node emits an AIMessage: we log its tool_calls (or final reply
        when there are none). `tools` node emits one or more ToolMessages: we
        log name + tool_call_id + full content. No truncation — debuggability
        wins over log size; log rotation handles volume.
        """
        for node_name, update in event.items():
            if node_name not in ("agent", "tools") or not isinstance(update, dict):
                continue
            for msg in update.get("messages") or []:
                if isinstance(msg, AIMessage):
                    if msg.tool_calls:
                        for tc in msg.tool_calls:
                            logger.info(
                                "agent → tool_call name=%s id=%s args=%r",
                                tc.get("name"), tc.get("id"), tc.get("args"),
                            )
                    else:
                        logger.info("agent → final content=%r", msg.content or "")
                elif isinstance(msg, ToolMessage):
                    logger.info(
                        "tools ← result name=%s tool_call_id=%s content=%r",
                        getattr(msg, "name", "?"),
                        msg.tool_call_id,
                        msg.content,
                    )

    def _next_input(self) -> HumanMessage:
        """Return the next user message for the agent. Drains pending_inputs
        first (instructions/MFA nudge), otherwise a simple 're-observe' prompt."""
        if not self._pending_inputs.empty():
            text = self._pending_inputs.get_nowait()
        else:
            text = "Re-observe the current DOM and decide the next step."
        return HumanMessage(content=text)

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
            "recursion_limit": 40,
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
                        invoke_input = {"messages": [self._next_input()]}

                    async for event in self._graph.astream(
                        invoke_input, config=config, stream_mode="updates",
                    ):
                        self._log_graph_event(event)
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
