"""Background handler — delegates to Graph Agent for all background tasks.

All background tasks run through the Graph Agent (vertex/edge DAG with decomposition).
The graph agent decomposes complex tasks into focused vertices, each processed
with a small context window (48k GPU). Edge payloads carry summaries, not raw data.

No legacy agentic loop — graph agent is the only execution path.
"""

from __future__ import annotations

import logging
import uuid

from app.background.tools import ALL_BACKGROUND_TOOLS
from app.config import settings, estimate_tokens
from app.llm.provider import llm_provider
from app.models import ModelTier, OrchestrateRequest

logger = logging.getLogger(__name__)

_BG_MAX_MODEL_RETRIES = 2


async def _bg_retry_with_next_model(
    original_error: Exception,
    failed_model: str,
    messages: list[dict],
    max_tier: str,
    estimated_tokens: int,
) -> object | None:
    """Try next cloud models after a background OpenRouter failure.

    Returns LLM response on success, None if all fallbacks exhausted.
    """
    from app.llm.router_client import route_request

    skip_models = [failed_model]
    for attempt in range(_BG_MAX_MODEL_RETRIES):
        fallback = await route_request(
            capability="chat",
            max_tier=max_tier,
            estimated_tokens=estimated_tokens,
            processing_mode="BACKGROUND",
            skip_models=skip_models,
        )
        if fallback.target != "openrouter" or not fallback.model or fallback.model in skip_models:
            logger.warning("No more cloud models available after %s failed (skip=%s)",
                           failed_model, skip_models)
            return None

        logger.info("Background: model %s failed, trying fallback %s (attempt %d/%d)",
                     failed_model, fallback.model, attempt + 1, _BG_MAX_MODEL_RETRIES)
        try:
            return await llm_provider.completion(
                messages=messages,
                tier=ModelTier.CLOUD_OPENROUTER,
                max_tokens=settings.default_output_tokens,
                temperature=0.2,
                tools=ALL_BACKGROUND_TOOLS,
                model_override=fallback.model,
                api_key_override=fallback.api_key,
            )
        except Exception as retry_err:
            logger.warning("Background: fallback model %s also failed: %s", fallback.model, retry_err)
            skip_models.append(fallback.model)

    return None


async def _run_graph_agent_background(
    request: OrchestrateRequest,
    thread_id: str | None = None,
) -> dict:
    """Run graph agent for a background task and adapt its return to handle_background format.

    Links the task sub-graph to the master map so all work is visible
    in the global thinking map.

    Args:
        request: Orchestration request from Kotlin.
        thread_id: Thread ID for LangGraph checkpointing. If None, generates one.
                   Must match what's tracked in _active_tasks for interrupt/resume to work.
    """
    from app.graph_agent.langgraph_runner import run_graph_agent
    from app.graph_agent.persistence import task_graph_store

    if not thread_id:
        thread_id = f"graph-{request.task_id}-{uuid.uuid4().hex[:8]}"
    logger.info(
        "GRAPH_AGENT_BACKGROUND | task_id=%s | thread=%s",
        request.task_id, thread_id,
    )

    state = await run_graph_agent(request, thread_id)

    # Adapt LangGraph state to handle_background return format
    graph_data = state.get("task_graph") or {}
    graph_id = graph_data.get("id", "") if isinstance(graph_data, dict) else ""
    graph_status = graph_data.get("status", "") if isinstance(graph_data, dict) else ""

    # Link sub-graph to master map
    try:
        await task_graph_store.link_task_subgraph(
            task_id=request.task_id,
            sub_graph_id=graph_id,
            title=request.query[:80] if request.query else f"Task {request.task_id}",
        )
    except Exception as e:
        logger.warning("Failed to link sub-graph to master map: %s", e)

    return {
        "success": graph_status not in ("failed", "cancelled"),
        "summary": state.get("final_result", ""),
        "artifacts": state.get("artifacts", []),
        "step_results": [],
        "branch": state.get("branch"),
        "thread_id": thread_id,
    }


def _estimate_tokens_total(messages: list[dict], tools: list[dict]) -> int:
    """Estimate total token count for routing decisions."""
    message_tokens = sum(estimate_tokens(str(m)) for m in messages)
    tools_tokens = sum(estimate_tokens(str(t)) for t in tools)
    return message_tokens + tools_tokens + settings.default_output_tokens


async def handle_background(
    request: OrchestrateRequest,
    thread_id: str | None = None,
) -> dict:
    """Handle a background task via Graph Agent (vertex/edge DAG).

    All background tasks run through the graph agent — decomposes the task
    into focused vertices, each processed with small context window.

    Args:
        request: OrchestrateRequest from Kotlin BackgroundEngine.
        thread_id: Thread ID for LangGraph checkpointing.

    Returns:
        dict with {success, summary, artifacts, step_results, branch}
    """
    return await _run_graph_agent_background(request, thread_id=thread_id)

