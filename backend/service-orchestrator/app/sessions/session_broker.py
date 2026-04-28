"""SessionBroker — singleton registry + LRU + parent-TTL + audit.

Single entry point for spawning, evicting and shutting down
``BaseClaudeSession`` instances across the orchestrator pod. Holds:

- ``_registry``: scope → live session (acquire / release path)
- ``_lru``: scope → last-touch monotonic time (LRU eviction)
- ``_parent_extension``: parent_scope → child_scopes (Klient holds Project
  alive while a Project session is running)
- ``_agent_job_holds``: agent_job_id → holder_scope (Klient/Project holds
  itself alive while a coding-agent K8s Job is in flight; reaper migrates
  the hold to the parent if the holder dies before the job completes)
- ``_pause_event``: when set, broker is *unpaused* — clear it to pause all
  sessions on Claude API rate limit (back-pressure)

Audit is written into the existing ``claude_scratchpad`` collection
(scope=``broker``, namespace=``audit``) — no new Mongo collection.

Reaper background task runs every 60s and reconciles:
- parent extensions whose child died without ``release_child``
- agent job holds whose holder scope is no longer registered (migrate to
  parent if alive, otherwise release)

PR-C5 wires ``shutdown_all`` into the FastAPI lifespan SIGTERM hook so
graceful pod stop force-compacts every active session within K8s grace.
"""

from __future__ import annotations

import asyncio
import collections
import datetime
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from bson import ObjectId
from motor.motor_asyncio import AsyncIOMotorClient

from app.config import settings
from app.sessions.base_session_manager import BaseClaudeSession, BaseClaudeSessionManager

logger = logging.getLogger(__name__)


# Reconciliation cadence — handles missed release events (child crash,
# agent job watcher silence, …). 60s is fine-grained enough for typical
# session lifetimes (minutes-to-hours) without polluting logs.
REAPER_INTERVAL_SECONDS = 60.0

# K8s SIGTERM grace is 30s by default — leave a 5s buffer for socket
# close so we beat SIGKILL.
SHUTDOWN_GRACE_SECONDS = 25.0


@dataclass
class _SpawnError(Exception):
    """Raised when a factory fails to produce a session."""

    scope: str
    cause: BaseException


def _utc_now() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


class SessionBroker:
    """Process-wide coordinator for in-process Claude sessions."""

    def __init__(self) -> None:
        self._registry: dict[str, BaseClaudeSession] = {}
        # Each session owns its manager (we need the manager to teardown,
        # save compact, etc. without circular imports).
        self._owners: dict[str, BaseClaudeSessionManager] = {}
        self._registry_lock = asyncio.Lock()
        cap_setting = getattr(settings, "max_active_claude_sessions", 20)
        try:
            self._cap = int(cap_setting)
        except Exception:
            self._cap = 20
        self._lru: collections.OrderedDict[str, float] = collections.OrderedDict()
        # parent_scope -> {child_scope}; child keeps parent alive until released.
        self._parent_extension: dict[str, set[str]] = {}
        # agent_job_id -> holder_scope (typically Project; reaper migrates
        # to Klient if Project dies before the job finishes).
        self._agent_job_holds: dict[str, str] = {}
        # Pause/unpause for rate-limit back-pressure. When clear, no new
        # turns drain from the inbox; when set, sessions resume.
        self._pause_event = asyncio.Event()
        self._pause_event.set()
        # Reaper task — started lazily on first acquire so import is
        # safe outside an event loop.
        self._reaper_task: asyncio.Task | None = None
        self._stopping = False
        # Shutdown coordination.
        self._shutdown_evt = asyncio.Event()

    # ── lifecycle ──────────────────────────────────────────────────────

    def _ensure_reaper(self) -> None:
        if self._reaper_task is not None and not self._reaper_task.done():
            return
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        self._reaper_task = loop.create_task(self._reaper_loop(), name="session-broker-reaper")

    async def _reaper_loop(self) -> None:
        while not self._shutdown_evt.is_set():
            try:
                await asyncio.wait_for(self._shutdown_evt.wait(), timeout=REAPER_INTERVAL_SECONDS)
                return
            except asyncio.TimeoutError:
                pass
            try:
                await self._reconcile()
            except Exception:
                logger.exception("session broker reaper failed")

    async def _reconcile(self) -> None:
        """Reconcile parent-extension & agent_job_holds against registry.

        Internal cleanup task — detects absence of release events. Not a
        UI surface, so push-only rule #9 doesn't apply here.
        """
        async with self._registry_lock:
            live = set(self._registry.keys())

            # Drop parent_extension children that are no longer registered.
            stale_children: list[tuple[str, str]] = []
            for parent, children in list(self._parent_extension.items()):
                for child in list(children):
                    if child not in live:
                        children.discard(child)
                        stale_children.append((parent, child))
                if not children:
                    self._parent_extension.pop(parent, None)
            if stale_children:
                logger.info("reaper released %d stale parent extensions", len(stale_children))

            # Migrate agent job holds whose holder is dead.
            stale_holds: list[tuple[str, str]] = []
            for job_id, holder in list(self._agent_job_holds.items()):
                if holder in live:
                    continue
                parent = self._parent_of_scope(holder)
                if parent and parent in live:
                    self._agent_job_holds[job_id] = parent
                    stale_holds.append((job_id, parent))
                else:
                    self._agent_job_holds.pop(job_id, None)
                    stale_holds.append((job_id, ""))
            if stale_holds:
                logger.info("reaper migrated/released %d agent job holds", len(stale_holds))

    @staticmethod
    def _parent_of_scope(scope: str) -> str | None:
        """``project:<cid>:<pid>`` → ``client:<cid>``; otherwise None."""
        if scope.startswith("project:"):
            parts = scope.split(":", 2)
            if len(parts) == 3:
                return f"client:{parts[1]}"
        return None

    # ── session acquire / release ─────────────────────────────────────

    async def acquire_session(
        self,
        *,
        scope: str,
        owner: BaseClaudeSessionManager,
        factory: Callable[[], Awaitable[BaseClaudeSession]],
    ) -> BaseClaudeSession:
        """Get-or-create with LRU eviction. Single entry point for spawn.

        ``factory`` must return a fully-started ``BaseClaudeSession`` — the
        broker doesn't know how to build one (different brief / scope per
        manager). On factory failure raises ``_SpawnError`` with cause.
        """
        if self._stopping:
            raise RuntimeError("broker is shutting down")
        self._ensure_reaper()
        # Wait if we are paused (rate-limit back-pressure). Pauses are
        # short-lived (until 429 clears), so no timeout — per Core
        # Principles "retry, not FAILED".
        await self._pause_event.wait()

        async with self._registry_lock:
            existing = self._registry.get(scope)
            if existing and not existing.stop_flag.is_set() and existing.task and not existing.task.done():
                self._lru.move_to_end(scope)
                self._lru[scope] = time.monotonic()
                return existing
            if existing:
                # Stale entry — let the prior owner tear it down before
                # we replace.
                prior_owner = self._owners.get(scope)
                if prior_owner is not None:
                    try:
                        await prior_owner._teardown(existing)
                    except Exception:
                        logger.exception("teardown of stale session failed | scope=%s", scope)
                self._registry.pop(scope, None)
                self._owners.pop(scope, None)
                self._lru.pop(scope, None)
            await self._evict_if_full_locked()
            try:
                session = await factory()
            except Exception as exc:
                logger.exception("session factory failed | scope=%s", scope)
                raise _SpawnError(scope=scope, cause=exc) from exc
            self._registry[scope] = session
            self._owners[scope] = owner
            self._lru[scope] = time.monotonic()
            await self._audit("spawn", scope=scope)
            return session

    def get_session(self, scope: str) -> BaseClaudeSession | None:
        """Read-only access to a live session by scope.

        Used by the qualifier inbox push path
        (:func:`app.qualifier.inbox.push_into_live_session`) to inject
        ``[qualifier-hint]`` system messages into running sessions
        without going through ``acquire_session`` (which would lazy-spawn).

        Lock-free intentionally — the registry dict is only mutated under
        the lock, but a stale read here is fine: callers must already
        defend against ``session.stop_flag.is_set()`` before pushing.
        """
        return self._registry.get(scope)

    async def release_session(self, scope: str) -> None:
        """Mark a session as voluntarily released by its owner. Does NOT
        teardown — that's still the owner's job. Used so the broker can
        forget the entry once the manager has finished its own cleanup.
        """
        async with self._registry_lock:
            self._registry.pop(scope, None)
            self._owners.pop(scope, None)
            self._lru.pop(scope, None)
            for parent, children in list(self._parent_extension.items()):
                children.discard(scope)
                if not children:
                    self._parent_extension.pop(parent, None)
        await self._audit("release", scope=scope)

    async def _evict_if_full_locked(self) -> None:
        while len(self._registry) >= self._cap:
            victim = self._pick_eviction_victim()
            if victim is None:
                logger.warning(
                    "broker at cap=%d but no eviction candidate (all parents)",
                    self._cap,
                )
                return
            session = self._registry.pop(victim, None)
            owner = self._owners.pop(victim, None)
            self._lru.pop(victim, None)
            if session is None or owner is None:
                continue
            logger.info("LRU eviction | scope=%s", victim)
            try:
                await owner._request_compact(session, force=True, reason="lru_eviction")
            except Exception:
                logger.exception("compact-on-evict failed | scope=%s", victim)
            try:
                await owner._teardown(session)
            except Exception:
                logger.exception("teardown-on-evict failed | scope=%s", victim)
            await self._audit("evict", scope=victim, reason="lru_cap")

    def _pick_eviction_victim(self) -> str | None:
        """LRU oldest that isn't holding a child / agent job."""
        protected = set(self._parent_extension.keys())
        protected |= {scope for scope in self._agent_job_holds.values() if scope}
        for scope in self._lru.keys():
            if scope not in protected:
                return scope
        return None

    # ── parent / child TTL coordination ───────────────────────────────

    async def extend_ttl_for_parent(self, *, child_scope: str, parent_scope: str) -> None:
        async with self._registry_lock:
            self._parent_extension.setdefault(parent_scope, set()).add(child_scope)
        await self._audit("extend_ttl", scope=parent_scope, child=child_scope)

    async def release_child(self, *, child_scope: str, parent_scope: str) -> None:
        async with self._registry_lock:
            children = self._parent_extension.get(parent_scope)
            if children is not None:
                children.discard(child_scope)
                if not children:
                    self._parent_extension.pop(parent_scope, None)
        await self._audit("release_child", scope=parent_scope, child=child_scope)

    # ── agent job hold coordination ───────────────────────────────────

    async def hold_for_agent_job(self, *, agent_job_id: str, holder_scope: str) -> None:
        async with self._registry_lock:
            self._agent_job_holds[agent_job_id] = holder_scope
        await self._audit("hold_agent_job", scope=holder_scope, agent_job_id=agent_job_id)

    async def release_agent_job(self, *, agent_job_id: str) -> str | None:
        async with self._registry_lock:
            holder = self._agent_job_holds.pop(agent_job_id, None)
        await self._audit("release_agent_job", scope=holder or "", agent_job_id=agent_job_id)
        return holder

    async def resolve_agent_job_target(self, *, agent_job_id: str) -> str | None:
        """Lookup which scope should receive ``[agent-update]`` events for
        a given agent job. Used by the orchestrator's AgentJobEvents push
        consumer to route into the correct session inbox.
        """
        async with self._registry_lock:
            return self._agent_job_holds.get(agent_job_id)

    # ── back-pressure ─────────────────────────────────────────────────

    async def pause_all(self, *, reason: str) -> None:
        if not self._pause_event.is_set():
            return
        self._pause_event.clear()
        logger.warning("broker pause | reason=%s", reason)
        await self._audit("pause", scope="*", reason=reason)

    async def resume_all(self) -> None:
        if self._pause_event.is_set():
            return
        self._pause_event.set()
        logger.info("broker resume")
        await self._audit("resume", scope="*")

    def is_paused(self) -> bool:
        return not self._pause_event.is_set()

    # ── shutdown ──────────────────────────────────────────────────────

    async def shutdown_session(self, *, scope: str, reason: str) -> bool:
        """Compact + teardown a single session. Holds the registry lock
        only briefly to detach the entry, then runs compact/teardown
        outside the lock to avoid blocking other acquires."""
        async with self._registry_lock:
            session = self._registry.pop(scope, None)
            owner = self._owners.pop(scope, None)
            self._lru.pop(scope, None)
        if session is None or owner is None:
            return False
        try:
            await owner._request_compact(session, force=True, reason=f"shutdown:{reason}")
        except Exception:
            logger.exception("shutdown compact failed | scope=%s", scope)
        try:
            await owner._teardown(session)
        except Exception:
            logger.exception("shutdown teardown failed | scope=%s", scope)
        await self._audit("shutdown_session", scope=scope, reason=reason)
        return True

    async def shutdown_all(self) -> None:
        """SIGTERM hook: parallel force-compact + teardown of every active
        session. Bounded by ``SHUTDOWN_GRACE_SECONDS`` (K8s grace - 5s).
        """
        if self._stopping:
            return
        self._stopping = True
        self._shutdown_evt.set()
        if self._reaper_task is not None and not self._reaper_task.done():
            self._reaper_task.cancel()
        async with self._registry_lock:
            scopes = list(self._registry.keys())
        if not scopes:
            return
        logger.info("broker shutdown_all | sessions=%d grace=%.0fs", len(scopes), SHUTDOWN_GRACE_SECONDS)
        coros = [
            self.shutdown_session(scope=s, reason="sigterm")
            for s in scopes
        ]
        try:
            await asyncio.wait_for(
                asyncio.gather(*coros, return_exceptions=True),
                timeout=SHUTDOWN_GRACE_SECONDS,
            )
        except asyncio.TimeoutError:
            logger.warning("broker shutdown_all hit grace timeout — abandoning remaining sessions")

    # ── snapshot for dashboard (PR-D1) ────────────────────────────────

    def snapshot(self) -> dict[str, Any]:
        now = time.monotonic()
        sessions: list[dict[str, Any]] = []
        for scope, session in self._registry.items():
            sessions.append({
                "scope": scope,
                "session_id": session.session_id,
                "client_id": session.client_id,
                "project_id": session.project_id,
                "cumulative_tokens": session.cumulative_tokens,
                "idle_seconds": int(now - session.last_activity_monotonic),
                "compact_in_progress": session._force_compact_in_progress,
            })
        return {
            "active": len(self._registry),
            "cap": self._cap,
            "paused": self.is_paused(),
            "sessions": sessions,
            "agent_job_holds": dict(self._agent_job_holds),
            "parent_extensions": {
                p: list(children) for p, children in self._parent_extension.items()
            },
        }

    # ── audit (claude_scratchpad scope=broker namespace=audit) ────────

    _mongo: AsyncIOMotorClient | None = None

    @classmethod
    def _db(cls):
        if cls._mongo is None:
            cls._mongo = AsyncIOMotorClient(settings.mongodb_url)
        return cls._mongo.get_default_database()

    async def _audit(self, event_type: str, *, scope: str, **extra: Any) -> None:
        try:
            doc = {
                "scope": "broker",
                "namespace": "audit",
                "key": str(uuid.uuid4()),
                "data": {
                    "event": event_type,
                    "session_scope": scope,
                    "ts": _utc_now().isoformat(),
                    **extra,
                },
                "tags": ["broker", event_type],
                "ttl_days": 30,
                "created_at": _utc_now(),
                "updated_at": _utc_now(),
            }
            await self._db()["claude_scratchpad"].insert_one(doc)
        except Exception:
            # Audit failure must never break the operational path.
            logger.debug("broker audit write failed event=%s scope=%s", event_type, scope)


session_broker = SessionBroker()
