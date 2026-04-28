"""Base in-process Claude session abstractions.

Houses the per-session state and the long-running session task that drives
a `ClaudeSDKClient` for the lifetime of a scope (e.g. ``client:<cid>`` for
Klient session, ``project:<cid>:<pid>`` for Project session).

Concrete managers extend `BaseClaudeSessionManager` and override the
hooks:

- `_resolve_brief(...)` — build the system prompt from a brief builder
- `_extra_subscriptions(session)` — long-lived gRPC subscribers (e.g.
  ``AgentJobStateChanged`` for Klient sessions, future qualifier hint
  stream for Project sessions)
- `_compact_save_kwargs(session)` — extra fields persisted alongside the
  compact snapshot (client_id / project_id propagation)

PR-C1 (token counter) and PR-C2 (compact trigger lock) live here too —
the base session carries `cumulative_tokens` and a per-session
`_compact_lock`. The compact path goes through a separate Claude API
call (``_call_compaction_agent_with_retry``) and only truncates the SDK
conversation **after** the snapshot lands in Mongo (idempotent recovery).
"""

from __future__ import annotations

import abc
import asyncio
import collections
import datetime
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, AsyncIterator, Awaitable, Callable

from bson import ObjectId as BsonObjectId

from app.chat.context import chat_context_assembler
from app.config import settings
from app.sessions.compact_store import save_compact

logger = logging.getLogger(__name__)


# Idle keepalive — silent tool-use loops emit a "thinking" sentinel so
# the chat stream stays open. Project rule "NEVER hard timeouts, stream
# + heartbeat" — this is the heartbeat.
IDLE_KEEPALIVE_SECONDS = 20.0

# Periodic compact cadence — emit a mid-session snapshot so a SIGKILL
# shortly before shutdown still leaves a fresh narrative on disk.
PERIODIC_COMPACT_INTERVAL = 420.0       # 7 min
PERIODIC_COMPACT_IDLE_SECONDS = 180.0   # at least 3 min of quiet

# Hard ceiling on the in-memory inbox per session — sanity guard against
# memory leak when something stalls. Single-user reality won't come close
# to this normally; over-cap → log warning + drop oldest non-system items.
SESSION_QUEUE_CAP = 1000

# Sentinel objects used on the per-session queues.
_STOP = object()
_TURN_DONE = object()
_READY = object()


def _utc_now() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


@dataclass
class TokenLimits:
    """Soft trigger compact post-response, hard trigger blocks next turn
    until compact lands. See plan §"Token thresholds per vrstva"."""

    soft: int
    hard: int


@dataclass
class BaseClaudeSession:
    """Per-scope session state. Owned by `BaseClaudeSessionManager`."""

    scope: str
    client_id: str | None
    project_id: str | None
    session_id: str
    started_at: datetime.datetime
    last_activity_monotonic: float
    in_queue: asyncio.Queue = field(default_factory=asyncio.Queue)
    out_queue: asyncio.Queue = field(default_factory=asyncio.Queue)
    ready_event: asyncio.Event = field(default_factory=asyncio.Event)
    task: asyncio.Task | None = None
    turn_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    stop_flag: asyncio.Event = field(default_factory=asyncio.Event)
    compact_task: asyncio.Task | None = None
    extra_tasks: list[asyncio.Task] = field(default_factory=list)

    # PR-C1 — token counter (soft/hard threshold trigger).
    cumulative_tokens: int = 0
    # PR-C2 — per-session compact lock + UX flag for in-progress force.
    _compact_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    _force_compact_in_progress: bool = False
    # PR-C2 — out_queue UI state events (compact_started/compact_finished)
    # the chat handler relays into the kRPC stream.
    _compact_state_listeners: list[asyncio.Queue] = field(default_factory=list)


class BaseClaudeSessionManager(abc.ABC):
    """Process-local registry for in-process Claude sessions, scope-keyed."""

    # Override per concrete manager. See plan §"Token thresholds per vrstva".
    TOKEN_LIMITS: TokenLimits = TokenLimits(soft=150_000, hard=400_000)

    def __init__(self) -> None:
        self._sessions: dict[str, BaseClaudeSession] = {}
        self._registry_lock = asyncio.Lock()
        self._stopping = False

    # ── public API ─────────────────────────────────────────────────────

    async def shutdown(self) -> None:
        """Compact + stop every running session. Called on pod shutdown."""
        self._stopping = True
        async with self._registry_lock:
            scopes = list(self._sessions.keys())
        for scope in scopes:
            try:
                await self._stop_session_internal(scope, reason="orchestrator shutdown")
            except Exception:
                logger.exception("Failed to stop session for scope=%s", scope)

    async def stop_session(self, scope: str) -> bool:
        return await self._stop_session_internal(scope, reason="manual stop")

    def snapshot(self) -> list[dict]:
        now = time.monotonic()
        return [
            {
                "scope": s.scope,
                "client_id": s.client_id,
                "project_id": s.project_id,
                "session_id": s.session_id,
                "started_at": s.started_at.isoformat(),
                "idle_seconds": int(now - s.last_activity_monotonic),
                "cumulative_tokens": s.cumulative_tokens,
            }
            for s in self._sessions.values()
        ]

    async def chat(
        self,
        *,
        scope: str,
        client_id: str | None,
        project_id: str | None,
        message: str,
        brief_kwargs: dict[str, Any] | None = None,
    ) -> AsyncIterator[dict]:
        session = await self._get_or_create(
            scope=scope,
            client_id=client_id,
            project_id=project_id,
            brief_kwargs=brief_kwargs or {},
        )
        scope_meta = {
            "scope": scope,
            "clientId": client_id or "",
            "projectId": project_id or "",
            "sessionId": session.session_id,
        }

        def _tag(evt: dict) -> dict:
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
                logger.exception("chat turn failed | scope=%s", scope)
                await self._teardown(session)
                async with self._registry_lock:
                    if self._sessions.get(scope) is session:
                        self._sessions.pop(scope, None)
                yield _tag({"type": "error", "content": f"claude session error: {e}"})

    # ── abstract hooks ─────────────────────────────────────────────────

    @abc.abstractmethod
    async def _resolve_brief(
        self,
        *,
        scope: str,
        client_id: str | None,
        project_id: str | None,
        session_id: str,
        brief_kwargs: dict[str, Any],
    ) -> tuple[str, str]:
        """Return (claude_md, brief_md) — concatenated as system prompt."""

    @abc.abstractmethod
    def _session_id_prefix(self) -> str:
        """Single character prefix marking the session role (``c`` / ``p``)."""

    async def _extra_subscriptions(self, session: BaseClaudeSession) -> list[asyncio.Task]:
        """Spawn long-lived gRPC subscribers for this session.

        Default implementation = no extras. Overridden by Klient
        (AgentJobStateChanged subscribe) and later by Project (qualifier
        hints subscribe). The returned tasks are tracked in
        ``session.extra_tasks`` and cancelled in ``_teardown``.
        """
        return []

    # ── internals ──────────────────────────────────────────────────────

    async def _get_or_create(
        self,
        *,
        scope: str,
        client_id: str | None,
        project_id: str | None,
        brief_kwargs: dict[str, Any],
    ) -> BaseClaudeSession:
        # Delegate spawn / LRU eviction to the broker; the broker calls
        # back into ``_start_new_session`` via the factory. We still
        # mirror the registry into ``_sessions`` so the existing
        # ``snapshot``/``stop_session`` surface works without going
        # through the broker every time.
        from app.sessions.session_broker import session_broker

        async def _factory() -> BaseClaudeSession:
            return await self._start_new_session(
                scope=scope,
                client_id=client_id,
                project_id=project_id,
                brief_kwargs=brief_kwargs,
            )

        session = await session_broker.acquire_session(
            scope=scope, owner=self, factory=_factory,
        )
        async with self._registry_lock:
            self._sessions[scope] = session
        return session

    async def _start_new_session(
        self,
        *,
        scope: str,
        client_id: str | None,
        project_id: str | None,
        brief_kwargs: dict[str, Any],
    ) -> BaseClaudeSession:
        session_id = f"{self._session_id_prefix()}{uuid.uuid4().hex}"
        claude_md, brief_md = await self._resolve_brief(
            scope=scope,
            client_id=client_id,
            project_id=project_id,
            session_id=session_id,
            brief_kwargs=brief_kwargs,
        )
        now = _utc_now()
        session = BaseClaudeSession(
            scope=scope,
            client_id=client_id,
            project_id=project_id,
            session_id=session_id,
            started_at=now,
            last_activity_monotonic=time.monotonic(),
        )
        system_prompt = claude_md + "\n\n" + brief_md
        mcp_servers = self._build_mcp_servers()
        session.task = asyncio.create_task(
            self._session_task(session, system_prompt, mcp_servers),
            name=f"claude-session-{session_id}",
        )
        # Wait for the SDK to enter and emit READY. No hard timeout — slow
        # cold start is legitimate. If the task dies before ready_event,
        # surface the exception.
        while not session.ready_event.is_set():
            if session.task.done():
                exc = session.task.exception()
                logger.error(
                    "session task died before ready | scope=%s session=%s exc=%s",
                    scope, session_id, exc,
                )
                await self._teardown(session)
                raise RuntimeError(f"claude session task exited before ready: {exc}")
            try:
                await asyncio.wait_for(session.ready_event.wait(), timeout=5.0)
            except asyncio.TimeoutError:
                continue

        session.compact_task = asyncio.create_task(
            self._periodic_compact_loop(session),
            name=f"periodic-compact-{session_id}",
        )

        # Subclass-specific extra subscriptions (agent events, qualifier
        # hints, …). All tracked in extra_tasks for unified teardown.
        try:
            extras = await self._extra_subscriptions(session)
        except Exception:
            logger.exception("extra subscriptions spawn failed | scope=%s", scope)
            extras = []
        session.extra_tasks = list(extras)

        logger.info(
            "started claude session | scope=%s session=%s extras=%d mcp=%s",
            scope, session_id, len(session.extra_tasks), list(mcp_servers.keys()),
        )
        return session

    def _build_mcp_servers(self) -> dict:
        url = f"{settings.mcp_url}/mcp"
        server: dict = {"type": "http", "url": url}
        token = settings.mcp_api_token
        if token:
            server["headers"] = {"Authorization": f"Bearer {token}"}
        return {"jervis": server}

    async def _session_task(
        self,
        session: BaseClaudeSession,
        system_prompt: str,
        mcp_servers: dict,
    ) -> None:
        """Dedicated task holding the SDK context for the whole session.

        The SDK's internal receive loop is anchored to whichever coroutine
        opened the async context — holding it inside a task keeps the
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
                logger.info("session task: SDK ready | scope=%s session=%s", session.scope, sid)
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
                        async for sdk_msg in sdk.receive_response():
                            msg_count += 1
                            cls_name = type(sdk_msg).__name__
                            msg_type = getattr(sdk_msg, "type", None) or cls_name
                            await session.out_queue.put(sdk_msg)
                            if cls_name == "ResultMessage" or msg_type in ("result", "ResultMessage"):
                                break
                    except Exception as e:
                        logger.exception("session task: turn crashed | session=%s", sid)
                        await session.out_queue.put(("__error__", str(e)))
                    finally:
                        dur = time.monotonic() - turn_start
                        logger.info(
                            "session task: turn done | session=%s dur=%.1fs msgs=%d",
                            sid, dur, msg_count,
                        )
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

    async def _one_turn(
        self,
        session: BaseClaudeSession,
        message: str,
    ) -> AsyncIterator[dict]:
        """Push one prompt to the session task and stream responses back.

        Persistence model (immutable bubbles per natural break): every
        assistant text block is accumulated into ``bubble_buffer`` and
        flushed as a fresh ``chat_messages`` INSERT at sentence ends, tool
        boundaries, ResultMessage, _TURN_DONE, or 500 char accumulation.

        PR-C1: tracks ``cumulative_tokens`` from ``ResultMessage.usage``
        and triggers compact on soft/hard threshold. PR-C2: compact runs
        as separate API call under ``_compact_lock``; soft trigger is
        scheduled post-response (non-blocking), hard trigger blocks the
        next turn until compact lands.
        """
        sid = session.session_id

        # Drain stale items from a previous cancelled turn before we hand
        # the next one to the SDK.
        drained = 0
        while True:
            try:
                session.out_queue.get_nowait()
                drained += 1
            except asyncio.QueueEmpty:
                break
        if drained:
            logger.info("one_turn: drained %d stale items | session=%s", drained, sid)

        # Persist USER turn first so reload sees the prompt before any
        # assistant chunk lands.
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
                logger.exception(
                    "one_turn: ASSISTANT persist failed | session=%s reason=%s",
                    sid, reason,
                )

        # No hard cap — keepalive every IDLE_KEEPALIVE_SECONDS, loop until
        # _TURN_DONE / __error__ / session task death.
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
                await _flush_bubble("turn_done")
                yield {"type": "done", "content": "", "metadata": {}}
                # PR-C1/C2 — post-turn token check (after we've yielded
                # done so the user reply is not blocked by compact).
                await self._maybe_trigger_compact(session)
                return
            if isinstance(item, tuple) and item and item[0] == "__error__":
                await _flush_bubble("error")
                yield {"type": "error", "content": f"claude: {item[1]}"[:500]}
                return

            sdk_msg = item
            cls_name = type(sdk_msg).__name__
            msg_type = getattr(sdk_msg, "type", None) or cls_name

            if cls_name == "AssistantMessage" or msg_type in ("assistant", "AssistantMessage"):
                for block in getattr(sdk_msg, "content", []) or []:
                    text = getattr(block, "text", None)
                    if text:
                        bubble_buffer.append(text)
                        joined = "".join(bubble_buffer)
                        if joined.rstrip().endswith((".", "!", "?")) or "\n\n" in text:
                            await _flush_bubble("sentence")
                        elif len(joined) >= 500:
                            await _flush_bubble("max_chars")
                        yield {"type": "token", "content": text, "metadata": {}}
                        continue
                    tool_name = getattr(block, "name", None)
                    if tool_name:
                        await _flush_bubble("tool_use")
                        yield {
                            "type": "thinking",
                            "content": f"Volám nástroj: {tool_name}",
                            "metadata": {"tool": str(tool_name)},
                        }
            elif cls_name == "ResultMessage" or msg_type in ("result", "ResultMessage"):
                # PR-C1 — accumulate token usage from the ResultMessage
                # so the next iteration's threshold check sees the new total.
                self._track_token_usage(session, sdk_msg)
                subtype = getattr(sdk_msg, "subtype", "success")
                is_err = getattr(sdk_msg, "is_error", False)
                if is_err or subtype != "success":
                    result_text = getattr(sdk_msg, "result", "") or f"subtype={subtype}"
                    await _flush_bubble("error_result")
                    yield {"type": "error", "content": str(result_text)[:500]}

    # ── PR-C1 token tracking ───────────────────────────────────────────

    @staticmethod
    def _track_token_usage(session: BaseClaudeSession, result_msg: Any) -> None:
        usage = getattr(result_msg, "usage", None)
        if usage is None:
            return
        tokens = 0
        for attr in ("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"):
            val = getattr(usage, attr, None)
            if isinstance(val, (int, float)):
                tokens += int(val)
        if tokens > 0:
            session.cumulative_tokens += tokens

    async def _maybe_trigger_compact(self, session: BaseClaudeSession) -> None:
        """Soft trigger schedules compact post-response (non-blocking).
        Hard trigger awaits compact inline so the next turn sees clean
        history. Lock-guard prevents double-trigger."""
        limits = self.TOKEN_LIMITS
        if session.cumulative_tokens >= limits.hard:
            await self._request_compact(session, force=True, reason="hard_threshold")
        elif session.cumulative_tokens >= limits.soft and not session._compact_lock.locked():
            asyncio.create_task(
                self._request_compact(session, force=False, reason="soft_threshold"),
                name=f"compact-soft-{session.session_id}",
            )

    # ── PR-C2 compact (separate API call under per-session lock) ──────

    async def _request_compact(
        self,
        session: BaseClaudeSession,
        *,
        force: bool,
        reason: str = "manual",
    ) -> str | None:
        """Run compact under per-session lock. Separate Claude API call
        with the *compaction agent* system prompt (NOT another turn in
        the host SDK conversation). Truncate only after Mongo save lands.
        """
        if session._compact_lock.locked() and not force:
            return None
        async with session._compact_lock:
            if force:
                session._force_compact_in_progress = True
            await self._emit_compact_state(session, "compact_started", reason=reason)
            compact_md: str | None = None
            try:
                history_dump = await self._extract_history_dump(session)
                compact_md = await self._call_compaction_agent_with_retry(history_dump)
                if compact_md:
                    try:
                        await save_compact(
                            scope=session.scope,
                            content=compact_md,
                            client_id=session.client_id,
                            project_id=session.project_id,
                            session_id=session.session_id,
                        )
                    except Exception:
                        logger.exception("compact save failed | session=%s", session.session_id)
                        compact_md = None
                if compact_md:
                    await self._sdk_compact_in_place(session)
                    session.cumulative_tokens = 0
            finally:
                session._force_compact_in_progress = False
                await self._emit_compact_state(session, "compact_finished", reason=reason)
            return compact_md

    async def _extract_history_dump(self, session: BaseClaudeSession) -> str:
        """Pull the conversation transcript for the compaction agent.

        Reads the persisted ``chat_messages`` for this session id (the
        same path the chat history surfaces) — avoids reaching into the
        SDK's private state. Returns "" if persistence isn't available
        (e.g. session id isn't a valid ObjectId in the legacy code path);
        the compaction agent is then a no-op for this turn.
        """
        try:
            messages = await chat_context_assembler._load_all_messages(session.session_id)
        except Exception:
            logger.exception("history dump failed | session=%s", session.session_id)
            return ""
        lines: list[str] = []
        for msg in messages:
            role = getattr(msg, "role", "").upper()
            content = (getattr(msg, "content", "") or "").strip()
            if not content:
                continue
            lines.append(f"### {role}\n{content}")
        return "\n\n".join(lines)

    async def _call_compaction_agent_with_retry(self, history: str) -> str | None:
        """Separate Claude API call with the ``compaction agent`` system
        prompt. Retry-forever per Core Principles (rate limit / connection
        errors are transient, never fatal). Soft 60s budget logs a
        warning to surface stalls but doesn't kill the retry loop.
        """
        if not history.strip():
            return None
        from anthropic import AsyncAnthropic, APIConnectionError, APIStatusError, RateLimitError

        try:
            client = AsyncAnthropic()
        except Exception:
            logger.exception("anthropic client init failed")
            return None

        system_prompt = (
            "You are the compaction agent. Your role is to summarise the "
            "provided conversation transcript into a structured markdown "
            "narrative covering: Recent decisions (top 10), Pending todos "
            "with dependencies, Current state of work, Active relationships "
            "(people / projects / deadlines currently relevant), Knowledge "
            "updates worth preserving long-term. Be concise — no raw chat "
            "turns, no tool call dumps. Output only the markdown."
        )
        user_prompt = (
            "Here is the session transcript. Produce the compact narrative.\n\n"
            f"{history}"
        )

        started_at = time.monotonic()
        delay = 1.0
        warned = False
        model = getattr(settings, "compaction_model", None) or "claude-sonnet-4-6"
        while True:
            try:
                resp = await client.messages.create(
                    model=model,
                    max_tokens=8000,
                    system=system_prompt,
                    messages=[{"role": "user", "content": user_prompt}],
                )
                blocks = getattr(resp, "content", []) or []
                parts: list[str] = []
                for b in blocks:
                    text = getattr(b, "text", None)
                    if text:
                        parts.append(text)
                content = "\n".join(parts).strip()
                return content or None
            except (RateLimitError, APIConnectionError, APIStatusError) as e:
                elapsed = time.monotonic() - started_at
                if elapsed > 60 and not warned:
                    logger.warning("compact retry > 60s, err=%s", e)
                    warned = True
                await asyncio.sleep(min(delay, 30.0))
                delay = min(delay * 2, 30.0)
            except Exception:
                logger.exception("compact agent call failed (non-retryable)")
                return None

    async def _sdk_compact_in_place(self, session: BaseClaudeSession) -> None:
        """Truncate the in-flight SDK conversation. Idempotent retry — if
        all attempts fail, the saved snapshot still bootstraps the next
        spawn, so we kill the session as a recovery path.
        """
        for attempt in range(3):
            try:
                # The SDK doesn't expose a direct truncate API; we send
                # the synthetic /compact directive into the input queue
                # and trust the session task to honour it. If a future
                # SDK version exposes a richer hook this is where to wire
                # it in.
                await session.in_queue.put(
                    "[system] COMPACT_IN_PLACE — drop the prior history "
                    "from your active context; the orchestrator has saved "
                    "a structured narrative snapshot for restart. Acknowledge "
                    "with a single 'ok' and return immediately."
                )
                return
            except Exception:
                logger.exception(
                    "sdk compact_in_place attempt %d failed | session=%s",
                    attempt + 1, session.session_id,
                )
                await asyncio.sleep(2 ** attempt)
        # All attempts failed — the saved compact will rebuild the
        # session at the next spawn.
        await self._teardown(session)

    # ── PR-C2 UI state events (kRPC stream) ────────────────────────────

    def subscribe_compact_state(self, scope: str) -> asyncio.Queue:
        """Register a listener for compact_started/compact_finished
        events on a session. Push-only per UI rule #9."""
        q: asyncio.Queue = asyncio.Queue(maxsize=64)
        session = self._sessions.get(scope)
        if session is not None:
            session._compact_state_listeners.append(q)
        return q

    def unsubscribe_compact_state(self, scope: str, q: asyncio.Queue) -> None:
        session = self._sessions.get(scope)
        if session is None:
            return
        try:
            session._compact_state_listeners.remove(q)
        except ValueError:
            pass

    async def _emit_compact_state(
        self,
        session: BaseClaudeSession,
        event: str,
        *,
        reason: str = "",
    ) -> None:
        payload = {
            "scope": session.scope,
            "session_id": session.session_id,
            "event": event,
            "reason": reason,
            "ts": _utc_now().isoformat(),
            "cumulative_tokens": session.cumulative_tokens,
        }
        for q in list(session._compact_state_listeners):
            try:
                q.put_nowait(payload)
            except asyncio.QueueFull:
                pass

    # ── periodic compact loop ──────────────────────────────────────────

    async def _periodic_compact_loop(self, session: BaseClaudeSession) -> None:
        sid = session.session_id
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
                    continue
                logger.info("periodic compact tick | session=%s", sid)
                try:
                    await self._request_compact(session, force=False, reason="periodic")
                except Exception:
                    logger.exception("periodic compact failed | session=%s", sid)
        finally:
            logger.info("periodic compact loop exiting | session=%s", sid)

    # ── stop / teardown ────────────────────────────────────────────────

    async def _stop_session_internal(self, scope: str, *, reason: str) -> bool:
        from app.sessions.session_broker import session_broker

        async with self._registry_lock:
            self._sessions.pop(scope, None)
        # Broker compacts + tears down + removes itself.
        ok = await session_broker.shutdown_session(scope=scope, reason=reason)
        if ok:
            logger.info("stopped session | scope=%s reason=%s", scope, reason)
        return ok

    async def _teardown(self, session: BaseClaudeSession) -> None:
        session.stop_flag.set()

        # Stop periodic-compact first so it doesn't try to schedule work
        # against a tearing-down SDK.
        compact_task = session.compact_task
        if compact_task and not compact_task.done():
            compact_task.cancel()
            try:
                await asyncio.wait_for(compact_task, timeout=5.0)
            except (asyncio.CancelledError, asyncio.TimeoutError):
                pass
            except Exception:
                logger.exception("periodic compact exit raised | session=%s", session.session_id)
        session.compact_task = None

        for sub in session.extra_tasks:
            if not sub.done():
                sub.cancel()
                try:
                    await asyncio.wait_for(sub, timeout=5.0)
                except (asyncio.CancelledError, asyncio.TimeoutError):
                    pass
                except Exception:
                    logger.exception(
                        "extra subscription exit raised | session=%s",
                        session.session_id,
                    )
        session.extra_tasks = []

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

        # Tell the broker the entry can be forgotten. Idempotent — the
        # broker is the source of truth for the live registry; this is
        # the back-channel for crash paths.
        try:
            from app.sessions.session_broker import session_broker
            await session_broker.release_session(session.scope)
        except Exception:
            logger.debug("broker release_session noop")
