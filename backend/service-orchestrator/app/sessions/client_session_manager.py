"""Per-client Claude session manager — extends BaseClaudeSessionManager.

Klient session is the per-client overview brain. Brief lives in
``client_brief_builder``; the only Klient-specific extra subscription is
the long-lived gRPC stream of ``AgentJobStateChanged`` events filtered by
``client_id`` (system messages of the form ``[agent-update] …`` injected
into the SDK's next turn boundary so the LLM sees background K8s coding
agent progress without polling).

PR1 cleanup: registry, queue management, _one_turn, periodic compact and
teardown all moved to ``base_session_manager``. The wrapper here only
overrides the brief, scope, and the agent-event subscriber.
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
from app.sessions.client_brief_builder import build_brief
from app.sessions.compact_store import scope_for_client

logger = logging.getLogger(__name__)


class ClientSessionManager(BaseClaudeSessionManager):
    """Process-local registry for per-client in-process Claude sessions."""

    # Klient session — per plan §"Token thresholds per vrstva".
    TOKEN_LIMITS = TokenLimits(soft=150_000, hard=400_000)

    def _session_id_prefix(self) -> str:
        return "c"

    async def _resolve_brief(
        self,
        *,
        scope: str,
        client_id: str | None,
        project_id: str | None,
        session_id: str,
        brief_kwargs: dict[str, Any],
    ) -> tuple[str, str]:
        if not client_id:
            raise RuntimeError("client session requires client_id")
        brief = await build_brief(
            client_id=client_id,
            project_id=project_id,
            session_id=session_id,
        )
        return brief.claude_md, brief.brief_md

    async def _extra_subscriptions(self, session: BaseClaudeSession) -> list[asyncio.Task]:
        if not session.client_id:
            return []
        sub = asyncio.create_task(
            self._consume_agent_job_events(session),
            name=f"agent-events-{session.session_id}",
        )
        return [sub]

    # ── public API (chat shim) ─────────────────────────────────────────

    async def chat(
        self,
        *,
        client_id: str,
        project_id: str | None,
        message: str,
    ) -> AsyncIterator[dict]:
        if not client_id:
            yield {
                "type": "error",
                "content": "client session requires active_client_id",
                "metadata": {"clientId": "", "projectId": ""},
            }
            return
        scope = scope_for_client(client_id)
        async for evt in super().chat(
            scope=scope,
            client_id=client_id,
            project_id=project_id,
            message=message,
        ):
            yield evt

    async def stop_session(self, client_id: str) -> bool:
        return await super().stop_session(scope_for_client(client_id))

    # ── AgentJobStateChanged push subscriber ──────────────────────────

    async def _consume_agent_job_events(self, session: BaseClaudeSession) -> None:
        """Long-lived gRPC subscriber to AgentJobStateChanged push events.

        Filters server-side by client_id (per scope) so cross-client
        traffic doesn't reach this session. Per arrived event, formats a
        compact ``[agent-update] …`` system message and enqueues it on
        ``out_queue`` — the chat handler picks it up between SDK messages
        and surfaces it to the next LLM turn.

        Reconnects with exponential backoff (1s → 30s cap) on transport
        drops. No polling fallback — push channel is the only source of
        truth.
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
        backoff = 1.0
        while not session.stop_flag.is_set():
            try:
                ctx = types_pb2.RequestContext()
                prepare_context(ctx)
                stub = server_agent_job_events_stub()
                req = agent_job_events_pb2.AgentJobEventsSubscribeRequest(
                    ctx=ctx, client_id=cid,
                )
                logger.info("agent events subscription open | session=%s client=%s", sid, cid)
                async for evt in stub.Subscribe(req):
                    if session.stop_flag.is_set():
                        break
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
                logger.info("agent events subscription cancelled | session=%s", sid)
                return
            except Exception as e:
                logger.warning(
                    "agent events subscription error | session=%s err=%s retry=%.1fs",
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


client_session_manager = ClientSessionManager()
