"""Per-(client, project) Claude session manager.

Mirrors `client_session_manager.py` but with scope ``project:<cid>:<pid>``
and a tighter token ceiling (soft=100k / hard=300k vs Klient soft=150k /
hard=400k). The Project session is the planning brain for one specific
project under one specific client; it is the canonical source of new
proposals (proposal lifecycle), and direct `dispatch_agent_job` is
forbidden unless the user gave explicit in-chat consent.

PR1 cleanup: registry, queue, _one_turn, periodic compact and teardown
all live on `BaseClaudeSessionManager`. Hooks overridden here:

- `_resolve_brief()` — builds the project-scoped brief
- `_session_id_prefix()` — ``"p"`` so session ids are visibly project sessions
- `_extra_subscriptions()` — per-(client, project) AgentJobStateChanged
  filter so a Project session only sees agent updates for its own jobs
- `chat()` — public shim wrapping `BaseClaudeSessionManager.chat` with
  required client_id+project_id validation
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any, AsyncIterator

from app.sessions.base_session_manager import (
    BaseClaudeSession,
    BaseClaudeSessionManager,
    TokenLimits,
)
from app.sessions.compact_store import scope_for_project
from app.sessions.project_brief_builder import build_project_brief

logger = logging.getLogger(__name__)


class ProjectSessionManager(BaseClaudeSessionManager):
    """Process-local registry for per-project in-process Claude sessions."""

    # Project session — per plan §"Token thresholds per vrstva". Tighter
    # than Klient because Project's context (one project) is narrower so
    # it shouldn't accumulate the same volume of cross-cutting state.
    TOKEN_LIMITS = TokenLimits(soft=100_000, hard=300_000)

    def _session_id_prefix(self) -> str:
        return "p"

    async def _resolve_brief(
        self,
        *,
        scope: str,
        client_id: str | None,
        project_id: str | None,
        session_id: str,
        brief_kwargs: dict[str, Any],
    ) -> tuple[str, str]:
        if not client_id or not project_id:
            raise RuntimeError(
                "project session requires both client_id and project_id"
            )
        brief = await build_project_brief(
            client_id=client_id,
            project_id=project_id,
            session_id=session_id,
        )
        return brief.claude_md, brief.brief_md

    async def _extra_subscriptions(
        self, session: BaseClaudeSession
    ) -> list[asyncio.Task]:
        if not session.client_id or not session.project_id:
            return []
        sub = asyncio.create_task(
            self._consume_agent_job_events(session),
            name=f"project-agent-events-{session.session_id}",
        )
        return [sub]

    # ── public API (chat shim) ─────────────────────────────────────────

    async def chat(
        self,
        *,
        client_id: str,
        project_id: str,
        message: str,
    ) -> AsyncIterator[dict]:
        if not client_id or not project_id:
            yield {
                "type": "error",
                "content": "project session requires both active_client_id and active_project_id",
                "metadata": {"clientId": client_id or "", "projectId": project_id or ""},
            }
            return
        scope = scope_for_project(client_id, project_id)
        async for evt in super().chat(
            scope=scope,
            client_id=client_id,
            project_id=project_id,
            message=message,
        ):
            yield evt

    async def stop_session(self, client_id: str, project_id: str) -> bool:
        return await super().stop_session(scope_for_project(client_id, project_id))

    # ── AgentJobStateChanged push subscriber (per-project filter) ─────

    async def _consume_agent_job_events(self, session: BaseClaudeSession) -> None:
        """Long-lived gRPC subscriber filtered by client_id AND project_id.

        The server-side stream filters on `client_id` already; we apply a
        further `project_id` filter on receive so a Project session only
        narrates updates for jobs scoped to its own project. Reconnects
        with exponential backoff (1s → 30s cap) on transport drops; no
        polling fallback.
        """
        from jervis.common import types_pb2
        from jervis.server import agent_job_events_pb2
        from jervis_contracts.interceptors import prepare_context

        from app.grpc_server_client import (
            _reset_channel as _reset_server_channel,
            server_agent_job_events_stub,
        )

        sid = session.session_id
        cid = session.client_id or ""
        pid = session.project_id or ""
        backoff = 1.0
        while not session.stop_flag.is_set():
            try:
                ctx = types_pb2.RequestContext()
                prepare_context(ctx)
                stub = server_agent_job_events_stub()
                req = agent_job_events_pb2.AgentJobEventsSubscribeRequest(
                    ctx=ctx, client_id=cid,
                )
                logger.info(
                    "project agent events subscription open | session=%s client=%s project=%s",
                    sid, cid, pid,
                )
                async for evt in stub.Subscribe(req):
                    if session.stop_flag.is_set():
                        break
                    # Project-scope filter: drop events that aren't for this
                    # project. The push schema includes project_id; if the
                    # field is unset/blank we keep the event (backward
                    # compatibility — better noisy than missing).
                    evt_pid = getattr(evt, "project_id", "") or ""
                    if evt_pid and evt_pid != pid:
                        continue
                    msg = self._format_agent_update(evt)
                    await session.out_queue.put({
                        "type": "system",
                        "content": msg,
                        "metadata": {
                            "kind": "agent_update",
                            "agentJobId": evt.agent_job_id,
                            "state": evt.state,
                        },
                    })
                backoff = 1.0
            except asyncio.CancelledError:
                logger.info("project agent events subscription cancelled | session=%s", sid)
                return
            except Exception as e:
                logger.warning(
                    "project agent events subscription error | session=%s err=%s retry=%.1fs",
                    sid, e, backoff,
                )
                try:
                    await _reset_server_channel()
                except Exception as ce:
                    logger.warning("channel reset failed | session=%s err=%s", sid, ce)
                try:
                    await asyncio.sleep(backoff)
                except asyncio.CancelledError:
                    return
                backoff = min(backoff * 2, 30.0)

    @staticmethod
    def _format_agent_update(evt) -> str:
        title = evt.title or "(no title)"
        base = f"[agent-update] Job {evt.agent_job_id} \"{title}\" → {evt.state}"
        if evt.state == "DONE" and evt.result_summary:
            return f"{base}: {evt.result_summary}"
        if evt.state == "ERROR" and evt.error_message:
            err = evt.error_message[:300]
            return f"{base}: ERROR: {err}"
        return base


project_session_manager = ProjectSessionManager()
