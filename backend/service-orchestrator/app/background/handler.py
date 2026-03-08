"""Background handler — delegates to Graph Agent or K8s coding agent.

Regular background tasks run through the Graph Agent (vertex/edge DAG with decomposition).
Coding tasks (source_urn="chat:coding-agent") are dispatched as K8s Jobs directly,
bypassing the graph agent — the K8s Job runs Claude CLI / Kilo Code in a container.
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
    from app.agent.langgraph_runner import run_graph_agent
    from app.agent.persistence import agent_store

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

    # Link sub-graph to master map (with final status)
    success = graph_status not in ("failed", "cancelled")
    summary = state.get("final_result", "")
    try:
        await agent_store.link_thinking_map(
            task_id=request.task_id,
            sub_graph_id=graph_id,
            title=request.query[:80] if request.query else f"Task {request.task_id}",
            completed=success,
            failed=not success,
            result_summary=summary[:500] if summary else "",
            client_id=request.client_id,
            client_name=request.client_name or "",
            project_id=request.project_id,
            project_name=request.project_name or "",
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


async def _run_coding_agent_background(
    request: OrchestrateRequest,
    thread_id: str | None = None,
) -> dict:
    """Dispatch a coding agent K8s Job for chat-initiated coding tasks.

    Prepares workspace with instructions, KB context, and environment,
    then creates a K8s Job (Claude CLI / Kilo Code). Returns immediately —
    AgentTaskWatcher monitors the job and marks task DONE on completion.

    Args:
        request: OrchestrateRequest with source_urn="chat:coding-agent".
        thread_id: Thread ID (stored on task for watcher).

    Returns:
        dict with coding_dispatched=True and job metadata.
    """
    from app.agents.job_runner import job_runner
    from app.agents.workspace_manager import workspace_manager
    from app.agent.persistence import agent_store
    from app.tools.kotlin_client import kotlin_client

    agent_type = request.agent_preference if request.agent_preference != "auto" else "claude"
    logger.info(
        "CODING_AGENT_BACKGROUND | task_id=%s | agent=%s | workspace=%s",
        request.task_id, agent_type, request.workspace_path,
    )

    # 1. Fetch merged guidelines (global → client → project)
    guidelines_text = None
    try:
        from app.context.guidelines_resolver import resolve_guidelines, format_guidelines_for_coding_agent
        guidelines = await resolve_guidelines(request.client_id, request.project_id)
        if guidelines:
            guidelines_text = format_guidelines_for_coding_agent(guidelines)
    except Exception as e:
        logger.debug("Guidelines fetch failed (non-fatal): %s", e)

    # 2. Prefetch KB context (optional — best effort)
    kb_context = None
    if request.project_id:
        try:
            from app.tools.executor import execute_tool
            kb_result = await execute_tool(
                tool_name="kb_search",
                arguments={"query": request.query[:200], "max_results": 5},
                client_id=request.client_id,
                project_id=request.project_id,
            )
            if kb_result and not str(kb_result).startswith("Error"):
                kb_context = str(kb_result)[:4000]
        except Exception as e:
            logger.debug("KB prefetch failed (non-fatal): %s", e)

    # 3. Build git config from rules
    git_config = None
    if request.rules:
        git_config = {
            "git_author_name": request.rules.git_author_name,
            "git_author_email": request.rules.git_author_email,
            "git_committer_name": request.rules.git_committer_name,
            "git_committer_email": request.rules.git_committer_email,
            "git_gpg_sign": request.rules.git_gpg_sign,
            "git_gpg_key_id": request.rules.git_gpg_key_id,
        }

    # 4. Prepare workspace (instructions, KB, environment, git config, guidelines, CLAUDE.md)
    workspace_path = await workspace_manager.prepare_workspace(
        task_id=request.task_id,
        client_id=request.client_id,
        project_id=request.project_id,
        project_path=request.workspace_path,
        instructions=request.query,
        files=[],  # Agent determines files to modify
        agent_type=agent_type,
        kb_context=kb_context,
        environment_context=request.environment,
        git_config=git_config,
        guidelines_text=guidelines_text,
    )

    # 5. Dispatch K8s Job (returns immediately)
    dispatch_info = await job_runner.dispatch_coding_agent(
        task_id=request.task_id,
        agent_type=agent_type,
        client_id=request.client_id,
        project_id=request.project_id,
        workspace_path=str(workspace_path),
        allow_git=False,
        gpg_key_id=request.rules.git_gpg_key_id if request.rules else None,
        git_user_name=request.rules.git_author_name if request.rules else None,
        git_user_email=request.rules.git_author_email if request.rules else None,
    )
    job_name = dispatch_info["job_name"]

    # 5. Notify Kotlin — task state → CODING
    await kotlin_client.notify_agent_dispatched(
        task_id=request.task_id,
        job_name=job_name,
        workspace_path=str(workspace_path),
        agent_type=agent_type,
    )

    # 6. Link to master map (not completed yet — watcher will update)
    try:
        await agent_store.link_thinking_map(
            task_id=request.task_id,
            sub_graph_id="",
            title=request.query[:80] if request.query else f"Coding {request.task_id}",
            completed=False,
            result_summary="",
        )
    except Exception as e:
        logger.warning("Failed to link coding task to master map: %s", e)

    logger.info(
        "CODING_DISPATCHED | task_id=%s | job=%s | agent=%s",
        request.task_id, job_name, agent_type,
    )

    return {
        "coding_dispatched": True,
        "job_name": job_name,
        "agent_type": agent_type,
        "workspace_path": str(workspace_path),
    }


async def handle_background(
    request: OrchestrateRequest,
    thread_id: str | None = None,
) -> dict:
    """Handle a background task — routing between graph agent and coding agent.

    Coding tasks (source_urn="chat:coding-agent") are dispatched as K8s Jobs.
    All other tasks run through the graph agent (vertex/edge DAG).

    Args:
        request: OrchestrateRequest from Kotlin BackgroundEngine.
        thread_id: Thread ID for LangGraph checkpointing.

    Returns:
        dict with {success, summary, ...} or {coding_dispatched: True, ...}
    """
    if request.source_urn == "chat:coding-agent":
        return await _run_coding_agent_background(request, thread_id=thread_id)
    return await _run_graph_agent_background(request, thread_id=thread_id)

