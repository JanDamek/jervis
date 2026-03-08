"""Unified vertex executor — ONE agentic loop for foreground + background.

This module provides the VertexEvent abstraction and the execute_vertex()
entry point that is consumed by:
- sse_handler.py (foreground chat): VertexEvent → ChatStreamEvent (SSE)
- langgraph_runner.py (background): VertexEvent → result/summary tuple

Phase 4 implementation: delegates to existing agentic loops while
establishing the unified interface. Full merge happens incrementally.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import AsyncIterator

from app.agent.models import GraphVertex, VertexType

logger = logging.getLogger(__name__)


@dataclass
class VertexEvent:
    """Event emitted during vertex execution.

    Consumed by foreground (SSE) and background (callback) adapters.
    """

    type: str  # "thinking" | "tool_call" | "tool_result" | "token" | "done" | "error" | "approval_request" | "scope_change"
    content: str = ""
    metadata: dict = field(default_factory=dict)


async def execute_vertex(
    vertex: GraphVertex,
    context: str,
    tools: list[dict],
    client_id: str,
    project_id: str | None,
    group_id: str | None = None,
    processing_mode: str = "BACKGROUND",
    max_tier: str = "NONE",
    session_id: str | None = None,
    disconnect_event: asyncio.Event | None = None,
) -> AsyncIterator[VertexEvent]:
    """Execute a vertex with the unified agentic loop.

    Yields VertexEvent objects. The caller (SSE handler or LangGraph runner)
    adapts these events to its transport format.

    For now, delegates to the background _agentic_vertex() which returns
    (result, summary). Foreground chat uses run_agentic_loop() directly
    via sse_handler.py until full merge.
    """
    from app.agent.langgraph_runner import _agentic_vertex

    yield VertexEvent(type="thinking", content=f"Zpracovávám: {vertex.title}")

    try:
        state = {
            "client_id": client_id,
            "project_id": project_id,
            "group_id": group_id,
            "max_openrouter_tier": max_tier,
        }

        result, summary = await _agentic_vertex(vertex, context, state)

        # Save per-vertex state
        vertex.agent_iteration += 1

        yield VertexEvent(
            type="done",
            content=result,
            metadata={"summary": summary, "tools_used": vertex.tools_used},
        )

    except Exception as e:
        logger.error("Vertex execution failed: %s — %s", vertex.id, e, exc_info=True)
        vertex.error = str(e)
        yield VertexEvent(type="error", content=str(e))
