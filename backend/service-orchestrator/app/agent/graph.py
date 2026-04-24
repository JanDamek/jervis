"""Agent Graph — removed in agent-job migration.

The Memory Graph / Paměťový graf abstraction is gone; Claude CLI owns
session narrative via compact_store and strategic anchors via the
KB-backed Thought Map (`thought_*` MCP tools). This file is kept only
so the remaining inline imports scattered across the orchestrator keep
resolving — each imported symbol is a no-op stub that returns an empty
value. When the last caller is gone, this file disappears with it.
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


def _sync_stub(*args, **kwargs):
    logger.debug("agent.graph sync stub called — Memory Graph removed")
    return None


async def _async_stub(*args, **kwargs):
    logger.debug("agent.graph async stub called — Memory Graph removed")
    return None


# Synchronous helpers that used to return collections/stats. Callers
# branch on the returned shape, so give them the empty analogue instead
# of None to avoid AttributeError on downstream .get() / iteration.
def get_stats(*args, **kwargs) -> dict:
    return {}


def find_blocked_vertices(*args, **kwargs) -> list:
    return []


def memory_graph_summary(*args, **kwargs) -> str:
    return ""


def create_task_graph(*args, **kwargs):
    return None


# Edge/vertex traversal helpers — validators + langgraph_runner still
# import these; return empty collections so `len(...)` / iteration works.
def get_incoming_edges(*args, **kwargs) -> list:
    return []


def get_outgoing_edges(*args, **kwargs) -> list:
    return []


def get_fan_in_count(*args, **kwargs) -> int:
    return 0


# Legacy public API — every other symbol the orchestrator still tries
# to import resolves here. Async variants are awaited by callers, so
# module-level __getattr__ picks between sync / async based on common
# naming prefixes (add_*, create_*, update_*, ... are async; everything
# else returns the sync stub that yields None).
_ASYNC_PREFIXES = (
    "add_", "create_", "update_", "remove_", "link_",
    "upsert_", "dispatch_", "run_",
)


def __getattr__(name: str):  # PEP 562 module-level __getattr__
    if any(name.startswith(p) for p in _ASYNC_PREFIXES):
        return _async_stub
    return _sync_stub


__all__: list[str] = []
