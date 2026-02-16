"""Memory Agent — central facade for all memory operations.

Manages working memory (LQM), affair lifecycle, context composition,
and KB write-through. Instantiated once per orchestration, shares
process-global LQM singleton across orchestrations.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

import httpx

from app.memory.affairs import (
    create_affair,
    load_affairs_from_kb,
    park_affair,
    resume_affair,
)
from app.memory.composer import compose_affair_context
from app.memory.context_switch import detect_context_switch
from app.memory.lqm import LocalQuickMemory
from app.memory.models import (
    Affair,
    AffairStatus,
    ContextSwitchResult,
    ContextSwitchType,
    PendingWrite,
    SessionContext,
    WritePriority,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Process-global LQM singleton
# ---------------------------------------------------------------------------

_lqm_instance: LocalQuickMemory | None = None


def _get_or_create_lqm() -> LocalQuickMemory:
    """Get or create the process-global LQM singleton."""
    global _lqm_instance
    if _lqm_instance is None:
        from app.config import settings
        _lqm_instance = LocalQuickMemory(
            max_warm_entries=settings.lqm_max_warm_entries,
            warm_ttl=settings.lqm_warm_ttl_seconds,
            write_buffer_max=settings.lqm_write_buffer_max,
        )
        logger.info("LQM singleton initialized")
    return _lqm_instance


def reset_lqm() -> None:
    """Reset the LQM singleton. Used on shutdown."""
    global _lqm_instance
    if _lqm_instance is not None:
        _lqm_instance.clear()
        _lqm_instance = None
        logger.info("LQM singleton cleared")


# ---------------------------------------------------------------------------
# Memory Agent
# ---------------------------------------------------------------------------


class MemoryAgent:
    """Orchestrator's memory management facade.

    Coordinates LQM, affairs, context switching, and KB persistence.
    One instance per orchestration run; shares process-global LQM.
    """

    def __init__(self, client_id: str, project_id: str | None) -> None:
        self.client_id = client_id
        self.project_id = project_id
        self.lqm = _get_or_create_lqm()
        self.session = SessionContext()

    # ----- Lifecycle -----

    async def load_session(
        self,
        state: dict,
        chat_history: dict | None = None,
    ) -> SessionContext:
        """Load/restore session context at orchestration start.

        1. Check LQM for existing affairs (hot path)
        2. If cold start, load from KB
        3. Populate session context
        """
        from app.config import settings

        # Try LQM first (hot path)
        active = self.lqm.get_active_affair(self.client_id)
        parked = self.lqm.get_parked_affairs(self.client_id)

        if active or parked:
            self.session.active_affair = active
            self.session.parked_affairs = parked
            logger.info(
                "Session loaded from LQM: active=%s, parked=%d",
                active.title if active else "none",
                len(parked),
            )
        else:
            # Cold start: load from KB
            try:
                kb_affairs = await load_affairs_from_kb(
                    self.client_id,
                    self.project_id,
                    settings.knowledgebase_url,
                )
                for affair in kb_affairs:
                    self.lqm.store_affair(affair)
                    if affair.status == AffairStatus.ACTIVE:
                        self.session.active_affair = affair
                    elif affair.status == AffairStatus.PARKED:
                        self.session.parked_affairs.append(affair)

                if kb_affairs:
                    logger.info(
                        "Session loaded from KB: %d affairs", len(kb_affairs),
                    )
            except Exception as e:
                logger.warning("Failed to load affairs from KB: %s", e)

        # Load user context from state (already prefetched by intake)
        user_context = state.get("user_context")
        if user_context and isinstance(user_context, dict):
            self.session.user_preferences = user_context

        return self.session

    async def detect_context_switch(
        self,
        user_message: str,
        state: dict,
    ) -> ContextSwitchResult:
        """Analyze user message for topic change."""
        from app.config import settings

        return await detect_context_switch(
            state=state,
            user_message=user_message,
            active_affair=self.session.active_affair,
            parked_affairs=self.session.parked_affairs,
            confidence_threshold=settings.context_switch_confidence_threshold,
        )

    async def switch_context(
        self,
        result: ContextSwitchResult,
        state: dict,
    ) -> str:
        """Execute context switch: park current, activate target.

        Returns a brief status message.
        """
        status_parts: list[str] = []

        # Park current affair (if any)
        if self.session.active_affair:
            await park_affair(self.session.active_affair, state, self.lqm)
            self.session.parked_affairs.append(self.session.active_affair)
            status_parts.append(f"Parked: {self.session.active_affair.title}")
            self.session.active_affair = None

        # Activate target
        if result.type == ContextSwitchType.SWITCH and result.target_affair_id:
            from app.config import settings
            affair = await resume_affair(
                result.target_affair_id, self.lqm,
                settings.knowledgebase_url, self.client_id,
            )
            if affair:
                self.session.active_affair = affair
                # Remove from parked list
                self.session.parked_affairs = [
                    a for a in self.session.parked_affairs
                    if a.id != affair.id
                ]
                status_parts.append(f"Resumed: {affair.title}")
            else:
                status_parts.append(f"Target affair not found: {result.target_affair_id}")

        elif result.type == ContextSwitchType.NEW_AFFAIR:
            title = result.new_affair_title or "Nová záležitost"
            affair = await create_affair(
                client_id=self.client_id,
                project_id=self.project_id,
                title=title,
                initial_context=result.reasoning,
                lqm=self.lqm,
            )
            self.session.active_affair = affair
            status_parts.append(f"Created: {affair.title}")

        self.session.last_context_switch_at = datetime.now(timezone.utc).isoformat()
        return "; ".join(status_parts)

    async def handle_ad_hoc(self, query: str) -> str:
        """Handle ad-hoc query without switching active context.

        Searches parked affairs and KB for relevant info.
        """
        results: list[str] = []

        # Search parked affairs
        for affair in self.session.parked_affairs:
            searchable = f"{affair.title} {affair.summary} {' '.join(affair.key_facts.values())}"
            if query.lower() in searchable.lower():
                facts = ", ".join(f"{k}: {v}" for k, v in affair.key_facts.items())
                results.append(
                    f"[Záležitost: {affair.title}]\n{affair.summary}\nFakta: {facts}"
                )

        if results:
            return "\n\n---\n\n".join(results[:3])

        # Fallback: KB search
        kb_results = await self.search(query, scope="kb_only")
        if kb_results:
            return "\n\n---\n\n".join(
                r.get("content", "")[:500] for r in kb_results[:3]
            )

        return f"Nenalezeno nic k dotazu: {query}"

    # ----- KB Operations (with LQM cache) -----

    async def search(self, query: str, scope: str = "all") -> list[dict]:
        """Search with LQM cache + write buffer + KB fallback."""
        results: list[dict] = []

        # Check LQM write buffer
        if scope in ("current", "all"):
            buffer_hits = self.lqm.search_write_buffer(query)
            results.extend(buffer_hits[:3])

        # Check search cache
        if scope in ("all", "kb_only"):
            cached = self.lqm.get_cached_search(query)
            if cached is not None:
                results.extend(cached)
                return results

        # KB search
        if scope in ("all", "kb_only"):
            try:
                from app.config import settings
                async with httpx.AsyncClient(timeout=15.0) as client:
                    resp = await client.post(
                        f"{settings.knowledgebase_url}/api/v1/retrieve",
                        json={
                            "query": query,
                            "clientId": self.client_id,
                            "maxResults": 5,
                        },
                        headers={"X-Ollama-Priority": "1"},
                    )
                    if resp.status_code == 200:
                        kb_results = resp.json().get("chunks", [])
                        # Cache results
                        self.lqm.cache_search(query, kb_results)
                        results.extend(kb_results)
            except Exception as e:
                logger.warning("KB search failed: %s", e)

        return results

    async def store(
        self,
        subject: str,
        content: str,
        category: str = "fact",
        priority: WritePriority = WritePriority.NORMAL,
        affair_id: str | None = None,
    ) -> str:
        """Store fact/decision to LQM + async KB flush."""
        now = datetime.now(timezone.utc).isoformat()

        # Add to active affair key_facts if applicable
        if affair_id and self.session.active_affair and self.session.active_affair.id == affair_id:
            self.session.active_affair.key_facts[subject] = content[:500]
        elif self.session.active_affair:
            self.session.active_affair.key_facts[subject] = content[:500]

        # Buffer KB write
        kind_map = {
            "fact": "user_knowledge_fact",
            "decision": "user_knowledge_preference",
            "order": "user_knowledge_general",
            "deadline": "user_knowledge_general",
            "contact": "user_knowledge_personal",
            "preference": "user_knowledge_preference",
            "procedure": "user_knowledge_domain",
        }

        write = PendingWrite(
            source_urn=f"memory:{self.client_id}:{subject[:50]}",
            content=f"# {subject}\n\n{content}",
            kind=kind_map.get(category, "user_knowledge_fact"),
            metadata={
                "category": category,
                "subject": subject,
                "affair_id": affair_id or "",
                "client_id": self.client_id,
                "project_id": self.project_id or "",
            },
            priority=priority,
            created_at=now,
        )
        await self.lqm.buffer_write(write)

        # Invalidate relevant search cache
        self.lqm.invalidate_search(subject)

        return f"Stored: '{subject}' ({category})"

    # ----- Context -----

    async def compose_context(self, max_tokens: int = 8000) -> str:
        """Compose affair-aware context for LLM prompt."""
        context, _remaining = compose_affair_context(
            self.session, max_tokens=max_tokens,
        )
        return context

    # ----- Flush -----

    async def flush_session(self) -> None:
        """End-of-orchestration: flush write buffer, update affairs in KB."""
        from app.config import settings

        # Update active affair in LQM
        if self.session.active_affair:
            self.session.active_affair.updated_at = datetime.now(timezone.utc).isoformat()
            self.lqm.store_affair(self.session.active_affair)

        # Drain write buffer and POST to KB
        await self._flush_write_buffer(settings.knowledgebase_write_url)

        stats = self.lqm.get_stats()
        logger.info(
            "Memory session flushed: affairs=%d, buffer_writes=%d, cache=%d",
            stats["affairs_count"], stats["buffer_writes"], stats["cache_size"],
        )

    async def _flush_write_buffer(self, kb_write_url: str) -> None:
        """Drain write buffer and POST each entry to KB."""
        writes = await self.lqm.drain_write_buffer()
        if not writes:
            return

        for write in writes:
            try:
                # Select endpoint based on priority
                if write.priority == WritePriority.CRITICAL:
                    endpoint = f"{kb_write_url}/api/v1/ingest-immediate"
                else:
                    endpoint = f"{kb_write_url}/api/v1/ingest"

                priority_header = "1" if write.priority == WritePriority.CRITICAL else "2"

                async with httpx.AsyncClient(timeout=30.0) as client:
                    resp = await client.post(
                        endpoint,
                        json={
                            "sourceUrn": write.source_urn,
                            "clientId": write.metadata.get("client_id", self.client_id),
                            "content": write.content,
                            "kind": write.kind,
                            "metadata": write.metadata,
                        },
                        headers={"X-Ollama-Priority": priority_header},
                    )

                    if resp.status_code == 404 and write.priority == WritePriority.CRITICAL:
                        # Fallback: /ingest-immediate not deployed yet
                        resp = await client.post(
                            f"{kb_write_url}/api/v1/ingest",
                            json={
                                "sourceUrn": write.source_urn,
                                "clientId": write.metadata.get("client_id", self.client_id),
                                "content": write.content,
                                "kind": write.kind,
                                "metadata": write.metadata,
                            },
                            headers={"X-Ollama-Priority": "1"},
                        )

                    if resp.status_code in (200, 201, 202):
                        self.lqm.mark_synced(write.source_urn)
                    else:
                        logger.warning(
                            "KB write failed for %s: %d %s",
                            write.source_urn, resp.status_code, resp.text[:200],
                        )
            except Exception as e:
                logger.warning(
                    "KB write failed for %s (non-blocking): %s",
                    write.source_urn, e,
                )

    # ----- Serialization -----

    def to_state_dict(self) -> dict:
        """Serialize to dict for OrchestratorState storage.

        LQM is NOT serialized — it's a process-global singleton.
        """
        return {
            "client_id": self.client_id,
            "project_id": self.project_id,
            "session": self.session.model_dump(),
        }

    @classmethod
    def from_state_dict(cls, data: dict) -> MemoryAgent:
        """Restore from state dict. Reconnects to process-global LQM."""
        agent = cls(data["client_id"], data.get("project_id"))
        session_data = data.get("session", {})
        if session_data:
            agent.session = SessionContext(**session_data)
        return agent
