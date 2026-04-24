"""Memory Graph storage — removed in agent-job migration.

Claude CLI now owns session narrative (compact_store) and strategic
anchors (Thought Map). The Python-side Memory Graph repository that
this module used to house is gone.

What stays: a no-op shim exposing `agent_store` and the legacy
`_THINKING_GRAPH_HIDE_S` constant, so the many inline
`from app.agent.persistence import agent_store` imports scattered
across the orchestrator keep resolving while their call sites are
migrated off (each call now logs a DEBUG line and returns None).

When the last caller is gone, this file disappears with it.
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


# Kept only because chat/agent UI code still reads it for a "hide stale
# thinking-graph entries after N seconds" cooldown. Harmless to keep.
_THINKING_GRAPH_HIDE_S: int = 60


class _NoOpAgentStore:
    """Returns an awaitable no-op for every attribute access.

    Callers did `await agent_store.link_thinking_graph(...)` etc. They
    now receive None; anything that branched on a returned value will
    fall through to the "no memory graph entry yet" path, which is
    correct — the graph really is empty.
    """

    def __getattr__(self, name: str):
        async def _stub(*args, **kwargs):
            logger.debug(
                "agent_store.%s called — Memory Graph removed, returning None",
                name,
            )
            return None

        return _stub


agent_store = _NoOpAgentStore()

__all__ = ["agent_store", "_THINKING_GRAPH_HIDE_S"]
