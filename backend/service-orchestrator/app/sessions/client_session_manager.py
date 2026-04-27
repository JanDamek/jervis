"""Per-client Claude session manager — in-process ClaudeSDKClient.

Fáze A pilot (revised): chat requests that carry an active_client_id skip
the LangGraph chat handler and land here. The manager keeps one
persistent `ClaudeSDKClient` per active client, inside a dedicated
asyncio task so the SDK's context-manager-owned internal tasks keep
running between turns.

Pattern:
    _session_task(session) runs `async with ClaudeSDKClient(...) as sdk`
    for the entire session lifetime. It reads prompts from an asyncio
    Queue (`in_queue`) and pushes SDK messages (+ sentinels) onto
    another (`out_queue`). `_one_turn` is the consumer side — it hands
    a prompt off and streams responses back to the chat output.

Why a task and not a plain held-open context manager:
- `ClaudeSDKClient.__aenter__` registers background tasks that need to
  stay anchored under the same running coroutine. If we `__aenter__`
  inside `_start_new_session` and return, those tasks lose their
  semantic owner and the SDK's receive loop silently stalls (Claude
  subprocess stays in ep_poll waiting for a control frame).

Session lifecycle:
- Sessions stay alive as long as the orchestrator pod runs. MAX plan
  means idle sessions don't tick tokens; subprocess RAM (~100 MB) is
  the only cost, and that is tolerable for a handful of clients.
- `shutdown()` compacts everyone on pod stop (deploy / crash recovery).
- Nightly maintenance (Fáze B) will batch-compact + stop all sessions.
- Explicit `stop_session(client_id)` is available for UI "reset".
"""

from __future__ import annotations

import asyncio
import datetime
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import AsyncIterator

from bson import ObjectId as BsonObjectId

from app.chat.context import chat_context_assembler
from app.config import settings
from app.sessions.client_brief_builder import build_brief
from app.sessions.compact_store import save_compact, scope_for_client

logger = logging.getLogger(__name__)

# No hard total-turn cap — project rule "NEVER hard timeouts, stream +
# heartbeat" (memory/feedback rule). Claude can iterate through tool
# calls as long as the SDK keeps producing messages; the keepalive below
# keeps the UI stream alive during silent tool-use loops. Runaway
# protection is the SDK's own `max_turns` limit (set on the session).

# If no SDK message arrives for this long, emit a 'thinking' keepalive
# so the chat stream stays responsive during silent tool-use loops.
IDLE_KEEPALIVE_SECONDS = 20.0

# Periodic compact cadence — Claude emits a mid-session snapshot every
# this many seconds so a SIGKILL shortly before shutdown still leaves a
# reasonably fresh narrative on disk. The loop only fires when the
# session has been idle for at least PERIODIC_COMPACT_IDLE_SECONDS,
# matching human pauses so we don't interrupt an active turn.
PERIODIC_COMPACT_INTERVAL = 420.0       # 7 min
PERIODIC_COMPACT_IDLE_SECONDS = 180.0   # at least 3 min of quiet

# Sentinel objects used on the per-session queues.
_STOP = object()
_TURN_DONE = object()
_READY = object()


def _utc_now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


@dataclass
class ClientSession:
    client_id: str
    project_id: str | None
    session_id: str
    started_at: datetime.datetime
    last_activity_monotonic: float
    # Queues wired to the dedicated session task.
    in_queue: asyncio.Queue = field(default_factory=asyncio.Queue)
    out_queue: asyncio.Queue = field(default_factory=asyncio.Queue)
    ready_event: asyncio.Event = field(default_factory=asyncio.Event)
    task: asyncio.Task | None = None
    turn_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    stop_flag: asyncio.Event = field(default_factory=asyncio.Event)
    # Periodic-compact background loop — owned by ClientSessionManager,
    # cancelled at teardown.
    compact_task: asyncio.Task | None = None
    # Long-lived gRPC subscriber for AgentJobStateChanged push events.
    # Injects [agent-update] system messages into out_queue so the LLM
    # session sees state transitions in the next turn without polling.
    # Cancelled at teardown together with the SDK task.
    agent_event_subscription: asyncio.Task | None = None


class ClientSessionManager:
    """Process-local registry for per-client in-process Claude sessions."""

    def __init__(self) -> None:
        self._sessions: dict[str, ClientSession] = {}
        self._registry_lock = asyncio.Lock()
        self._stopping = False

    # ── public API ─────────────────────────────────────────────────────

    async def shutdown(self) -> None:
        """Compact + stop every running session. Called on pod shutdown."""
        self._stopping = True
        async with self._registry_lock:
            client_ids = list(self._sessions.keys())
        for cid in client_ids:
            try:
                await self._stop_session_internal(cid, reason="orchestrator shutdown")
            except Exception:
                logger.exception("Failed to stop client session for %s", cid)

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

        session = await self._get_or_create(client_id, project_id)
        scope_meta = {
            "clientId": client_id,
            "projectId": project_id or "",
            "sessionId": session.session_id,
        }

        def _tag(evt: dict) -> dict:
            """Stamp the scope on every emitted event so the UI can filter
            responses that belong to a client the user is not currently
            looking at. Without this tag the shared chat event stream
            mixes concurrent sessions in the same UI view."""
            meta = dict(evt.get("metadata") or {})
            for k, v in scope_meta.items():
                meta.setdefault(k, v)
            evt["metadata"] = meta
            return evt

        async with session.turn_lock:
            session.last_activity_monotonic = time.monotonic()
            try:
                async for evt in self._one_turn(session, message):
                    yield _tag(evt)
                    if evt.get("type") in ("done", "error"):
                        session.last_activity_monotonic = time.monotonic()
                        return
            except Exception as e:
                logger.exception("chat turn failed | client=%s", client_id)
                await self._teardown(session)
                async with self._registry_lock:
                    if self._sessions.get(client_id) is session:
                        self._sessions.pop(client_id, None)
                yield _tag({"type": "error", "content": f"claude session error: {e}"})

    async def stop_session(self, client_id: str) -> bool:
        return await self._stop_session_internal(client_id, reason="manual stop")

    def snapshot(self) -> list[dict]:
        now = time.monotonic()
        return [
            {
                "client_id": s.client_id,
                "project_id": s.project_id,
                "session_id": s.session_id,
                "started_at": s.started_at.isoformat(),
                "idle_seconds": int(now - s.last_activity_monotonic),
            }
            for s in self._sessions.values()
        ]

    # ── internals ──────────────────────────────────────────────────────

    async def _get_or_create(self, client_id: str, project_id: str | None) -> ClientSession:
        async with self._registry_lock:
            existing = self._sessions.get(client_id)
            if existing and not existing.stop_flag.is_set() and existing.task and not existing.task.done():
                return existing
            if existing:
                await self._teardown(existing)
                self._sessions.pop(client_id, None)
            session = await self._start_new_session(client_id, project_id)
            self._sessions[client_id] = session
            return session

    async def _start_new_session(self, client_id: str, project_id: str | None) -> ClientSession:
        # uuid4 hex prefixed with a role marker — 33 chars, alphanumeric.
        session_id = f"c{uuid.uuid4().hex}"
        brief = await build_brief(
            client_id=client_id,
            project_id=project_id,
            session_id=session_id,
        )
        now = datetime.datetime.now(datetime.timezone.utc)
        session = ClientSession(
            client_id=client_id,
            project_id=project_id,
            session_id=session_id,
            started_at=now,
            last_activity_monotonic=time.monotonic(),
        )
        system_prompt = brief.claude_md + "\n\n" + brief.brief_md
        mcp_servers = self._build_mcp_servers()
        session.task = asyncio.create_task(
            self._session_task(session, system_prompt, mcp_servers),
            name=f"claude-session-{session_id}",
        )
        # Wait for the SDK context to enter and emit the ready sentinel.
        # No hard timeout — if the SDK subprocess is slow to boot (cold
        # container, big system prompt, MCP server discovery), we wait.
        # If it truly fails, the session task itself will exit and we
        # detect it via task.done().
        while not session.ready_event.is_set():
            if session.task.done():
                exc = session.task.exception()
                logger.error(
                    "session task died before ready | session=%s exc=%s",
                    session_id, exc,
                )
                await self._teardown(session)
                raise RuntimeError(f"claude session task exited before ready: {exc}")
            try:
                await asyncio.wait_for(session.ready_event.wait(), timeout=5.0)
            except asyncio.TimeoutError:
                continue

        # Kick off the periodic-compact loop so a mid-session SIGKILL
        # still leaves a fairly fresh narrative on disk (reduces delta
        # loss between shutdowns — Claude SDK cannot resume a killed
        # subprocess, so frequent snapshots are the cheapest safety net).
        session.compact_task = asyncio.create_task(
            self._periodic_compact_loop(session),
            name=f"periodic-compact-{session_id}",
        )

        # Subscribe to AgentJobStateChanged push events so the LLM sees
        # background coding agents progress without polling
        # get_agent_job_status. Each push lands as a system message in
        # out_queue and surfaces on the next LLM turn boundary.
        session.agent_event_subscription = asyncio.create_task(
            self._consume_agent_job_events(session),
            name=f"agent-events-{session_id}",
        )

        logger.info(
            "started in-process client session | client=%s project=%s session=%s mcp=%s",
            client_id, project_id, session_id, list(mcp_servers.keys()),
        )
        return session

    def _build_mcp_servers(self) -> dict:
        url = f"{settings.mcp_url}/mcp"
        server: dict = {"type": "http", "url": url}
        token = settings.mcp_api_token
        if token:
            server["headers"] = {"Authorization": f"Bearer {token}"}
        return {"jervis": server}

    async def _session_task(self, session: ClientSession, system_prompt: str, mcp_servers: dict) -> None:
        """Dedicated task holding the SDK context for the whole session.

        Critical: the SDK's internal receive loop is anchored to whichever
        coroutine opened the async context. Holding it inside a task (and
        driving prompts through an asyncio.Queue from anywhere) keeps the
        loop alive until the task itself exits.
        """
        from claude_agent_sdk import ClaudeSDKClient, ClaudeAgentOptions

        sid = session.session_id
        options = ClaudeAgentOptions(
            allowed_tools=["Read", "Glob", "Grep", "WebSearch", "WebFetch"],
            permission_mode="bypassPermissions",
            mcp_servers=mcp_servers,
            system_prompt=system_prompt,
            max_turns=1000,
            max_buffer_size=64 * 1024 * 1024,
        )

        try:
            async with ClaudeSDKClient(options=options) as sdk:
                logger.info("session task: SDK ready | session=%s", sid)
                session.ready_event.set()

                while not session.stop_flag.is_set():
                    prompt = await session.in_queue.get()
                    if prompt is _STOP:
                        break

                    turn_start = time.monotonic()
                    msg_count = 0
                    try:
                        logger.info("session task: sending query | session=%s len=%d", sid, len(prompt))
                        await sdk.query(prompt)
                        logger.info("session task: query dispatched, awaiting SDK messages | session=%s", sid)
                        async for sdk_msg in sdk.receive_response():
                            msg_count += 1
                            cls_name = type(sdk_msg).__name__
                            msg_type = getattr(sdk_msg, "type", None) or cls_name
                            logger.info("session task: sdk msg #%d | session=%s cls=%s type=%s",
                                        msg_count, sid, cls_name, msg_type)
                            await session.out_queue.put(sdk_msg)
                            if cls_name == "ResultMessage" or msg_type in ("result", "ResultMessage"):
                                break
                    except Exception as e:
                        logger.exception("session task: turn crashed | session=%s", sid)
                        await session.out_queue.put(("__error__", str(e)))
                    finally:
                        dur = time.monotonic() - turn_start
                        logger.info("session task: turn done | session=%s dur=%.1fs msgs=%d",
                                    sid, dur, msg_count)
                        await session.out_queue.put(_TURN_DONE)
        except Exception:
            logger.exception("session task: fatal | session=%s", sid)
            session.ready_event.set()
            try:
                session.out_queue.put_nowait(("__error__", "session task crashed during init"))
            except asyncio.QueueFull:
                pass
            try:
                session.out_queue.put_nowait(_TURN_DONE)
            except asyncio.QueueFull:
                pass
        finally:
            logger.info("session task: exiting | session=%s", sid)
            session.stop_flag.set()

    async def _one_turn(self, session: ClientSession, message: str) -> AsyncIterator[dict]:
        """Push one prompt to the session task and stream responses back.

        Persistence model (Fáze B — immutable bubbles per natural break):
        every assistant text block is accumulated into `bubble_buffer`.
        On a natural break — sentence end (`. ! ? \\n\\n`), a tool_use
        boundary, a ResultMessage / `_TURN_DONE`, or the buffer reaching
        500 chars — the buffer is flushed as a fresh `chat_messages`
        INSERT (never UPDATE). This way long generations create multiple
        immutable bubbles instead of a single rolling document.
        """
        sid = session.session_id
        # Drain anything left in out_queue from a previous turn that was
        # cancelled upstream (e.g. UI disconnected). Without this we'd
        # hand the next turn the tail end of the previous SDK response.
        drained = 0
        while True:
            try:
                session.out_queue.get_nowait()
                drained += 1
            except asyncio.QueueEmpty:
                break
        if drained:
            logger.info("one_turn: drained %d stale items | session=%s", drained, sid)
        logger.info("one_turn: dispatching query | session=%s msg_len=%d", sid, len(message))

        # Persist the USER turn first so reload sees the prompt before
        # any assistant chunk lands. Forces a sequence number bump.
        try:
            user_seq = await chat_context_assembler.get_next_sequence(sid)
            await chat_context_assembler.save_message(
                conversation_id=sid,
                role="USER",
                content=message,
                correlation_id=str(BsonObjectId()),
                sequence=user_seq,
                metadata={},
                client_id=session.client_id,
                project_id=session.project_id,
            )
        except Exception:
            logger.exception("one_turn: USER persist failed | session=%s", sid)

        await session.in_queue.put(message)
        yield {"type": "thinking", "content": "Zpracovávám dotaz...", "metadata": {}}

        # Per-natural-break bubble buffer.
        bubble_buffer: list[str] = []

        async def _flush_bubble(reason: str) -> None:
            if not bubble_buffer:
                return
            content = "".join(bubble_buffer).strip()
            bubble_buffer.clear()
            if not content:
                return
            try:
                seq = await chat_context_assembler.get_next_sequence(sid)
                await chat_context_assembler.save_message(
                    conversation_id=sid,
                    role="ASSISTANT",
                    content=content,
                    correlation_id=str(BsonObjectId()),
                    sequence=seq,
                    metadata={"chunkBoundary": reason},
                    client_id=session.client_id,
                    project_id=session.project_id,
                )
            except Exception:
                logger.exception("one_turn: ASSISTANT persist failed | session=%s reason=%s", sid, reason)

        # No hard cap on total turn duration — project rule "NEVER hard
        # timeouts, stream + heartbeat". Loop until _TURN_DONE, __error__,
        # or the session task dies. Keepalive tick every
        # IDLE_KEEPALIVE_SECONDS keeps the UI stream alive during silent
        # tool-use loops.
        while True:
            try:
                item = await asyncio.wait_for(
                    session.out_queue.get(),
                    timeout=IDLE_KEEPALIVE_SECONDS,
                )
            except asyncio.TimeoutError:
                if session.stop_flag.is_set() or (session.task and session.task.done()):
                    yield {"type": "error", "content": "claude session task exited unexpectedly"}
                    return
                yield {"type": "thinking", "content": "Stále pracuji…", "metadata": {}}
                continue

            if item is _TURN_DONE:
                # Flush any unpersisted text before signalling done.
                await _flush_bubble("turn_done")
                yield {"type": "done", "content": "", "metadata": {}}
                return
            if isinstance(item, tuple) and item and item[0] == "__error__":
                await _flush_bubble("error")
                yield {"type": "error", "content": f"claude: {item[1]}"[:500]}
                return

            sdk_msg = item
            cls_name = type(sdk_msg).__name__
            msg_type = getattr(sdk_msg, "type", None) or cls_name
            logger.debug("one_turn: sdk msg | session=%s cls=%s type=%s", sid, cls_name, msg_type)

            if cls_name == "AssistantMessage" or msg_type in ("assistant", "AssistantMessage"):
                for block in getattr(sdk_msg, "content", []) or []:
                    text = getattr(block, "text", None)
                    if text:
                        bubble_buffer.append(text)
                        # Natural break — sentence end punctuation OR
                        # accumulated chars over 500. Lets the UI see a
                        # new immutable bubble at sane boundaries.
                        joined = "".join(bubble_buffer)
                        if joined.rstrip().endswith((".", "!", "?")) or "\n\n" in text:
                            await _flush_bubble("sentence")
                        elif len(joined) >= 500:
                            await _flush_bubble("max_chars")
                        yield {"type": "token", "content": text, "metadata": {}}
                        continue
                    tool_name = getattr(block, "name", None)
                    if tool_name:
                        # Tool call boundary: flush whatever text the
                        # assistant emitted up to this point as its own
                        # bubble before the tool call interrupts.
                        await _flush_bubble("tool_use")
                        yield {
                            "type": "thinking",
                            "content": f"Volám nástroj: {tool_name}",
                            "metadata": {"tool": str(tool_name)},
                        }
            elif cls_name == "ResultMessage" or msg_type in ("result", "ResultMessage"):
                subtype = getattr(sdk_msg, "subtype", "success")
                is_err = getattr(sdk_msg, "is_error", False)
                if is_err or subtype != "success":
                    result_text = getattr(sdk_msg, "result", "") or f"subtype={subtype}"
                    await _flush_bubble("error_result")
                    yield {"type": "error", "content": str(result_text)[:500]}
                    # fall through to the pending _TURN_DONE sentinel
                # ResultMessage is observed but the session task still
                # emits _TURN_DONE right after, which is what we return on.

    async def _stop_session_internal(self, client_id: str, *, reason: str) -> bool:
        async with self._registry_lock:
            session = self._sessions.pop(client_id, None)
        if not session:
            return False
        logger.info("stopping client session | client=%s reason=%s", client_id, reason)
        compact_content = await self._request_compact(session)
        if compact_content:
            try:
                await save_compact(
                    scope=scope_for_client(client_id),
                    content=compact_content,
                    client_id=client_id,
                    project_id=session.project_id,
                    session_id=session.session_id,
                )
            except Exception:
                logger.exception("saving compact failed | client=%s", client_id)
        await self._teardown(session)
        return True

    async def _request_compact(self, session: ClientSession) -> str | None:
        """Emit COMPACT_AND_EXIT and collect Claude's final markdown.

        Used by the shutdown path — sends the exit sentinel so Claude's
        own shutdown protocol kicks in. The periodic version
        (:_request_periodic_compact:) keeps the session running afterwards.
        """
        exit_prompt = (
            "[system] COMPACT_AND_EXIT — emit exactly one final markdown "
            "summary following the shutdown protocol described in your "
            "brief. Do NOT append anything after it."
        )
        return await self._run_compact_turn(session, exit_prompt)

    async def _request_periodic_compact(self, session: ClientSession) -> str | None:
        """Emit PERIODIC_COMPACT and collect an interim summary.

        Claude writes the same markdown shape as the exit compact, but the
        session stays alive afterwards — Claude is expected to treat this
        as "save a mid-flight snapshot, then keep going". Called by the
        background loop on idle sessions so a SIGKILL a few seconds before
        shutdown still leaves at most PERIODIC_COMPACT_INTERVAL of delta
        loss on disk.
        """
        periodic_prompt = (
            "[system] PERIODIC_COMPACT — emit exactly one concise markdown "
            "snapshot (state / pending / next / key facts) for this session "
            "so far. Do NOT stop; the session continues after this snapshot. "
            "Do NOT append anything after the markdown."
        )
        return await self._run_compact_turn(session, periodic_prompt)

    async def _run_compact_turn(self, session: ClientSession, prompt: str) -> str | None:
        """Shared body of exit and periodic compact — single turn, collect
        AssistantMessage text blocks, return joined markdown or None."""
        if session.stop_flag.is_set() or not session.task or session.task.done():
            return None
        async with session.turn_lock:
            try:
                await session.in_queue.put(prompt)
            except Exception as e:
                logger.warning("compact dispatch failed | session=%s: %s", session.session_id, e)
                return None

            # No hard cap — compact turn runs as long as Claude needs
            # (project rule: no timeouts). Loop exits on _TURN_DONE,
            # __error__, or the session task dying.
            collected: list[str] = []
            while True:
                if session.stop_flag.is_set() or (session.task and session.task.done()):
                    break
                try:
                    item = await asyncio.wait_for(
                        session.out_queue.get(),
                        timeout=IDLE_KEEPALIVE_SECONDS,
                    )
                except asyncio.TimeoutError:
                    continue
                if item is _TURN_DONE:
                    break
                if isinstance(item, tuple) and item and item[0] == "__error__":
                    break
                if type(item).__name__ == "AssistantMessage":
                    for block in getattr(item, "content", []) or []:
                        text = getattr(block, "text", None)
                        if text:
                            collected.append(text)
            body = "\n".join(collected).strip()
            return body or None

    async def _periodic_compact_loop(self, session: ClientSession) -> None:
        """Background task firing PERIODIC_COMPACT every
        PERIODIC_COMPACT_INTERVAL seconds, but only when the session has
        been idle for PERIODIC_COMPACT_IDLE_SECONDS (no interruption of
        an active turn).
        """
        sid = session.session_id
        cid = session.client_id
        logger.info(
            "periodic compact loop started | session=%s interval=%.0fs idle_gate=%.0fs",
            sid, PERIODIC_COMPACT_INTERVAL, PERIODIC_COMPACT_IDLE_SECONDS,
        )
        try:
            while not session.stop_flag.is_set():
                try:
                    await asyncio.sleep(PERIODIC_COMPACT_INTERVAL)
                except asyncio.CancelledError:
                    return
                if session.stop_flag.is_set():
                    return
                idle_for = time.monotonic() - session.last_activity_monotonic
                if idle_for < PERIODIC_COMPACT_IDLE_SECONDS:
                    logger.debug(
                        "periodic compact skipped (active) | session=%s idle=%.0fs",
                        sid, idle_for,
                    )
                    continue

                logger.info("periodic compact tick | session=%s", sid)
                try:
                    snapshot = await self._request_periodic_compact(session)
                except Exception:
                    logger.exception("periodic compact turn failed | session=%s", sid)
                    continue
                if not snapshot:
                    logger.debug("periodic compact produced no content | session=%s", sid)
                    continue
                try:
                    await save_compact(
                        scope=scope_for_client(cid),
                        content=snapshot,
                        client_id=cid,
                        project_id=session.project_id,
                        session_id=sid,
                    )
                    logger.info(
                        "periodic compact saved | session=%s chars=%d",
                        sid, len(snapshot),
                    )
                except Exception:
                    logger.exception("periodic compact save failed | session=%s", sid)
        finally:
            logger.info("periodic compact loop exiting | session=%s", sid)

    async def _consume_agent_job_events(self, session: ClientSession) -> None:
        """Long-lived gRPC subscriber to AgentJobStateChanged push events.

        Filters server-side by client_id (per scope) so cross-client
        traffic doesn't reach this session. Per arrived event, formats a
        compact `[agent-update] ...` system message and enqueues it on
        out_queue — the chat handler picks it up between SDK messages
        and surfaces it to the next LLM turn.

        Reconnects with exponential backoff (1s → 30s cap) on transport
        drops. No polling fallback — push channel is the only source of
        truth (per Fáze H acceptance + brief).
        """
        from jervis.common import types_pb2
        from jervis.server import agent_job_events_pb2
        from jervis_contracts.interceptors import prepare_context

        from app.grpc_server_client import (
            _reset_channel as _reset_server_channel,
            server_agent_job_events_stub,
        )

        sid = session.session_id
        cid = session.client_id
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
                    logger.debug(
                        "agent_update injected | session=%s job=%s state=%s",
                        sid, evt.agent_job_id, evt.state,
                    )
                # Stream completed cleanly (server closed) — reset backoff
                # and reconnect.
                backoff = 1.0
            except asyncio.CancelledError:
                logger.info("agent events subscription cancelled | session=%s", sid)
                return
            except Exception as e:
                logger.warning(
                    "agent events subscription error | session=%s err=%s retry=%.1fs",
                    sid, e, backoff,
                )
                # Server pod restart leaves the cached gRPC channel pinned
                # to the dead pod IP. Reset it before sleeping so the next
                # subscribe() picks up a fresh DNS resolution + TCP
                # connection. Without this the loop reconnects forever.
                # Long-term fix: shared ResilientGrpcChannel helper —
                # see project-resilient-grpc-channels.md.
                try:
                    await _reset_server_channel()
                except Exception as ce:
                    logger.warning(
                        "channel reset failed | session=%s err=%s", sid, ce,
                    )
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
            # Truncate noisy stack traces — agent only needs the first line
            # to react. Full message stays in agent_job_records.errorMessage.
            err = evt.error_message[:300]
            return f"{base}: ERROR: {err}"
        return base

    async def _teardown(self, session: ClientSession) -> None:
        session.stop_flag.set()
        # Stop the periodic-compact loop before the SDK task so the loop
        # doesn't try to queue a prompt against a session that's tearing
        # down. Cancel is fire-and-forget: the loop is idempotent and
        # handles CancelledError internally.
        compact_task = session.compact_task
        if compact_task and not compact_task.done():
            compact_task.cancel()
            try:
                await asyncio.wait_for(compact_task, timeout=5.0)
            except (asyncio.CancelledError, asyncio.TimeoutError):
                pass
            except Exception:
                logger.exception(
                    "periodic compact task exit raised | session=%s",
                    session.session_id,
                )
        session.compact_task = None

        # Stop the AgentJobStateChanged gRPC subscriber. The reconnect
        # loop respects stop_flag and CancelledError, so cancel() is
        # enough; we still await briefly to give the underlying gRPC
        # call a chance to close cleanly.
        agent_sub = session.agent_event_subscription
        if agent_sub and not agent_sub.done():
            agent_sub.cancel()
            try:
                await asyncio.wait_for(agent_sub, timeout=5.0)
            except (asyncio.CancelledError, asyncio.TimeoutError):
                pass
            except Exception:
                logger.exception(
                    "agent events subscription exit raised | session=%s",
                    session.session_id,
                )
        session.agent_event_subscription = None

        try:
            session.in_queue.put_nowait(_STOP)
        except asyncio.QueueFull:
            pass
        task = session.task
        if task and not task.done():
            try:
                await asyncio.wait_for(task, timeout=10.0)
            except asyncio.TimeoutError:
                task.cancel()
                try:
                    await task
                except (asyncio.CancelledError, Exception):
                    pass
            except Exception:
                logger.exception("session task exit raised | session=%s", session.session_id)
        session.task = None


client_session_manager = ClientSessionManager()
