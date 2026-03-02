"""Unified agent handler.

Single entry point for all agent modes: chat, background, qualification, idle.
Delegates to a common agentic loop with mode-specific configuration.

This is a TRANSITIONAL implementation: run() currently delegates to existing
handlers (handle_chat for SSE, handle_background for non-SSE). In a later step
we will extract the common agentic loop into agentic_loop.py.

Usage:
    # Chat mode (SSE streaming) -- returns AsyncIterator[ChatStreamEvent]
    async for event in run_stream(CHAT_MODE, client_id="...", query="..."):
        ...

    # Background mode -- returns dict
    result = await run_background(BACKGROUND_MODE, client_id="...", query="...")
"""
from __future__ import annotations

import logging
from typing import AsyncIterator, Any

from .task_mode import TaskMode, CHAT_MODE, BACKGROUND_MODE, QUALIFICATION_MODE, IDLE_MODE
from .tool_sets import ToolTier, get_tools, get_tool_names, TOOL_ESCALATE_TOOLS

logger = logging.getLogger("unified.handler")


def _build_effective_mode(mode: TaskMode, max_openrouter_tier: str | None) -> TaskMode:
    """Build an effective TaskMode by merging runtime overrides into the preset."""
    if not max_openrouter_tier or max_openrouter_tier == mode.max_openrouter_tier:
        return mode
    return TaskMode(
        stream_sse=mode.stream_sse,
        tool_tier=mode.tool_tier,
        max_openrouter_tier=max_openrouter_tier,
        max_iterations=mode.max_iterations,
        allow_approval=mode.allow_approval,
        allow_planning=mode.allow_planning,
        allow_review=mode.allow_review,
        priority=mode.priority,
        prefer_cloud=mode.prefer_cloud,
    )


async def run_stream(
    mode: TaskMode,
    *,
    # Common context
    task_id: str | None = None,
    client_id: str,
    project_id: str | None = None,
    group_id: str | None = None,
    client_name: str | None = None,
    project_name: str | None = None,
    query: str,
    max_openrouter_tier: str = "NONE",
    # Chat-specific
    session_id: str | None = None,
    message_sequence: int = 0,
    disconnect_event: Any | None = None,
    context_task_id: str | None = None,
) -> AsyncIterator:
    """Run unified agent handler in streaming (SSE) mode.

    For stream_sse=True modes (chat). Returns AsyncIterator of ChatStreamEvent.
    Raises ValueError if mode.stream_sse is False.
    """
    if not mode.stream_sse:
        raise ValueError(
            f"run_stream() requires mode.stream_sse=True, got {mode!r}. "
            "Use run_background() for non-streaming modes."
        )

    effective_mode = _build_effective_mode(mode, max_openrouter_tier)

    logger.info(
        "UNIFIED_STREAM: mode=%s task=%s client=%s project=%s tier=%s cloud=%s",
        effective_mode.priority,
        task_id,
        client_id,
        project_id,
        effective_mode.tool_tier.value,
        effective_mode.max_openrouter_tier,
    )

    # Delegate to existing chat handler
    # TODO: Replace with unified agentic loop
    from ..chat.handler import handle_chat
    from ..chat.models import ChatRequest

    request = ChatRequest(
        message=query,
        session_id=session_id or "",
        message_sequence=message_sequence,
        active_client_id=client_id,
        active_project_id=project_id,
        active_group_id=group_id,
        max_openrouter_tier=effective_mode.max_openrouter_tier,
        context_task_id=context_task_id,
    )
    async for event in handle_chat(request, disconnect_event):
        yield event


async def run_background(
    mode: TaskMode,
    *,
    # Common context
    task_id: str | None = None,
    client_id: str,
    project_id: str | None = None,
    group_id: str | None = None,
    client_name: str | None = None,
    project_name: str | None = None,
    query: str,
    max_openrouter_tier: str = "NONE",
    # Background-specific
    workspace_path: str | None = None,
    rules: Any | None = None,
    environment: dict | None = None,
    chat_history: Any | None = None,
) -> dict:
    """Run unified agent handler in background (non-streaming) mode.

    For stream_sse=False modes (background, qualification, idle).
    Returns dict with {success, summary, artifacts, step_results, ...}.
    Raises ValueError if mode.stream_sse is True.
    """
    if mode.stream_sse:
        raise ValueError(
            f"run_background() requires mode.stream_sse=False, got {mode!r}. "
            "Use run_stream() for streaming modes."
        )

    effective_mode = _build_effective_mode(mode, max_openrouter_tier)

    logger.info(
        "UNIFIED_BACKGROUND: mode=%s task=%s client=%s project=%s tier=%s cloud=%s",
        effective_mode.priority,
        task_id,
        client_id,
        project_id,
        effective_mode.tool_tier.value,
        effective_mode.max_openrouter_tier,
    )

    # Delegate to existing background handler
    # TODO: Replace with unified agentic loop
    from ..background.handler import handle_background
    from ..models import OrchestrateRequest, ProjectRules

    request = OrchestrateRequest(
        task_id=task_id or "",
        client_id=client_id,
        project_id=project_id,
        group_id=group_id,
        client_name=client_name,
        project_name=project_name,
        workspace_path=workspace_path or "",
        query=query,
        rules=rules if isinstance(rules, ProjectRules) else ProjectRules(),
        environment=environment,
        chat_history=chat_history,
        processing_mode="BACKGROUND",
    )
    result = await handle_background(request)
    return result
