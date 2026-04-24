"""Background handler — routes background tasks through the Graph Agent.

Coding work no longer flows through this handler — agent-job migration
moved all coding dispatch to the Kotlin AgentJobDispatcher (gRPC
ServerAgentJobService.DispatchAgentJob, MCP tool `dispatch_agent_job`).
Anything arriving here with a coding `source_urn` is a legacy event from
an upstream caller that hasn't been updated yet; we reject it cleanly
so the Kotlin side re-routes.
"""

from __future__ import annotations

import logging
import uuid

from app.background.tools import ALL_BACKGROUND_TOOLS
from app.config import settings, estimate_tokens
from app.llm.provider import llm_provider
from app.models import OrchestrateRequest
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)

# Cross-model retry on cloud failure now lives inside the router — it picks
# the next cloud model (find_cloud_model_for_context + skip_models). The old
# _bg_retry_with_next_model helper was removed with the LiteLLM-in-orchestrator
# stack.


async def _run_graph_agent_background(
    request: OrchestrateRequest,
    thread_id: str | None = None,
) -> dict:
    """Run graph agent for a background task and adapt its return to handle_background format.

    Links the task sub-graph to the master graph so all work is visible
    in the global thinking graph.

    Args:
        request: Orchestration request from Kotlin.
        thread_id: Thread ID for LangGraph checkpointing. If None, generates one.
                   Must match what's tracked in _active_tasks for interrupt/resume to work.
    """
    from app.agent.langgraph_runner import run_graph_agent

    if not thread_id:
        thread_id = f"graph-{request.task_id}-{uuid.uuid4().hex[:8]}"
    logger.info(
        "GRAPH_AGENT_BACKGROUND | task_id=%s | thread=%s",
        request.task_id, thread_id,
    )

    task_title = request.task_name or request.query[:80] or f"Task {request.task_id}"

    # Push "started" to chat
    try:
        await kotlin_client.notify_thinking_graph_update(
            task_id=request.task_id,
            task_title=task_title,
            status="started",
            message=f"Přemýšlím nad úlohou: {task_title}",
        )
    except Exception:
        pass

    # Run graph agent — always update memory graph on completion (even on crash)
    state: dict = {}
    agent_failed = False
    try:
        state = await run_graph_agent(request, thread_id)
    except Exception as e:
        logger.error("GRAPH_AGENT_CRASHED | task_id=%s | %s", request.task_id, e)
        agent_failed = True
        state = {"final_result": f"Agent crashed: {e}", "task_graph": {"status": "failed"}}

    # Adapt LangGraph state to handle_background return format
    graph_data = state.get("task_graph") or {}
    graph_id = graph_data.get("id", "") if isinstance(graph_data, dict) else ""
    graph_status = graph_data.get("status", "") if isinstance(graph_data, dict) else ""

    # Link sub-graph to master graph (with final status)
    is_blocked = graph_status == "blocked"
    success = not agent_failed and graph_status not in ("failed", "cancelled", "blocked")
    summary = state.get("final_result", "")

    # If final_result is empty (common for multi-vertex graphs where results
    # were stored in KB via kb_store tool calls), synthesize a summary from
    # completed vertex results so the chat message has actual content.
    if not summary and isinstance(graph_data, dict):
        vertices = graph_data.get("vertices", {})
        if isinstance(vertices, dict):
            completed_results = []
            for v in vertices.values():
                if isinstance(v, dict) and v.get("status") == "completed":
                    result = v.get("result", "") or v.get("result_summary", "")
                    if result and len(result) > 20:
                        completed_results.append(result)
            if completed_results:
                # Use the longest result (likely the synthesis/final vertex)
                summary = max(completed_results, key=len)[:3000]
                logger.info(
                    "BACKGROUND_SUMMARY_SYNTHESIZED: task=%s from %d vertex results, len=%d",
                    request.task_id, len(completed_results), len(summary),
                )

    # Push completion/failure to chat
    try:
        await kotlin_client.notify_thinking_graph_update(
            task_id=request.task_id,
            task_title=task_title,
            graph_id=graph_id,
            status="completed" if success else "failed",
            message=(summary[:200] if summary else "") if success else (summary[:200] if summary else "Selhalo"),
        )
    except Exception:
        pass

    return {
        "success": success,
        "blocked": is_blocked,
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
    """Handle a background task — Graph Agent path only.

    Coding dispatch moved to Kotlin AgentJobDispatcher (MCP
    `dispatch_agent_job` / gRPC `ServerAgentJobService.DispatchAgentJob`).
    Anything still arriving here with a coding `source_urn` is a legacy
    event from an upstream caller not yet migrated — reject with a
    structured error so the Kotlin side routes it correctly.
    """
    urn = request.source_urn or ""
    if (
        urn == "chat:coding-agent"
        or urn.startswith("code-review:")
        or urn.startswith("code-review-fix:")
    ):
        logger.warning(
            "Legacy coding source_urn '%s' routed to orchestrator — "
            "coding dispatch now owned by Kotlin AgentJobDispatcher.",
            urn,
        )
        return {
            "success": False,
            "summary": (
                "Coding dispatch moved to Kotlin AgentJobDispatcher "
                "(MCP dispatch_agent_job). Update the caller to use that path."
            ),
        }
    return await _run_graph_agent_background(request, thread_id=thread_id)

