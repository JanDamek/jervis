"""Execute step node — dispatches based on step type.

Handles respond, code, and tracker step types.
"""

from __future__ import annotations

import json
import logging

from langgraph.types import interrupt

from app.agents.job_runner import job_runner
from app.agents.workspace_manager import workspace_manager
from app.config import settings
from app.models import (
    CodingStep,
    CodingTask,
    ProjectRules,
    StepResult,
    StepType,
)
from app.graph.nodes._helpers import llm_with_cloud_fallback

logger = logging.getLogger(__name__)


async def execute_step(state: dict) -> dict:
    """Execute one step. Delegates based on step type."""
    task = CodingTask(**state["task"])
    steps = [CodingStep(**s) for s in state["steps"]]
    idx = state.get("current_step_index", 0)
    step = steps[idx]

    logger.info(
        "Executing step %d/%d type=%s",
        idx + 1, len(steps), step.step_type.value,
    )

    if step.step_type == StepType.RESPOND:
        return await _execute_respond_step(state, task, step)

    if step.step_type == StepType.CODE:
        return await _execute_code_step(state, task, step)

    if step.step_type == StepType.TRACKER:
        return await _execute_tracker_step(state, task, step)

    # Fallback: treat as code step
    return await _execute_code_step(state, task, step)


async def _execute_respond_step(
    state: dict, task: CodingTask, step: CodingStep,
) -> dict:
    """Execute an analytical/respond step — LLM + KB directly."""
    evidence = state.get("evidence_pack", {})
    project_context = state.get("project_context", "")

    # Build context
    context_parts: list[str] = []
    if project_context:
        context_parts.append(f"## Project Context\n{project_context[:3000]}")
    if evidence:
        for kr in evidence.get("kb_results", []):
            content = kr.get("content", "")
            if content:
                context_parts.append(f"## Knowledge Base\n{content[:3000]}")

    context_block = "\n\n".join(context_parts)

    messages = [
        {
            "role": "system",
            "content": (
                "You are Jervis, an AI assistant. Analyze and respond to the task.\n"
                "Be concise, helpful, and factual. Use Czech language.\n"
                "Reference KB context when relevant."
            ),
        },
        {
            "role": "user",
            "content": (
                f"{step.instructions}"
                + (f"\n\n{context_block}" if context_block else "")
            ),
        },
    ]

    response = await llm_with_cloud_fallback(
        state=state, messages=messages, task_type="analysis", max_tokens=4096,
    )
    answer = response.choices[0].message.content

    step_result = StepResult(
        step_index=step.index,
        success=True,
        summary=answer[:500],
        agent_type="llm",
    )

    existing_results = list(state.get("step_results", []))
    existing_results.append(step_result.model_dump())

    # For respond steps, also set final_result
    return {
        "step_results": existing_results,
        "final_result": answer,
    }


async def _execute_code_step(
    state: dict, task: CodingTask, step: CodingStep,
) -> dict:
    """Execute a coding step via K8s Job."""
    # Pre-fetch KB context (best-effort)
    try:
        kb_context = await _prefetch_kb_context(
            task_description=step.instructions,
            client_id=task.client_id,
            project_id=task.project_id,
            files=step.files,
        )
    except Exception as e:
        logger.warning(
            "KB pre-fetch failed for task %s (continuing without): %s: %s",
            task.id, type(e).__name__, e,
        )
        kb_context = ""

    # Prepare workspace
    await workspace_manager.prepare_workspace(
        task_id=f"{task.id}-step-{step.index}",
        client_id=task.client_id,
        project_id=task.project_id,
        project_path=task.workspace_path,
        instructions=step.instructions,
        files=step.files,
        agent_type=step.agent_type.value,
        kb_context=kb_context,
        environment_context=state.get("environment"),
    )

    # Create K8s Job (non-blocking) and pause graph until job completes.
    # AgentJobWatcher polls K8s and resumes the graph when the job finishes.
    step_task_id = f"{task.id}-step-{step.index}"
    workspace_full = f"{settings.data_root}/{task.workspace_path}"

    job_name = await job_runner.create_coding_agent_job(
        task_id=step_task_id,
        agent_type=step.agent_type.value,
        client_id=task.client_id,
        project_id=task.project_id,
        workspace_path=workspace_full,
        thread_id=state.get("_thread_id", ""),
    )

    logger.info(
        "K8s Job created: %s — pausing graph (interrupt), watcher will resume",
        job_name,
    )

    # interrupt() pauses the graph and checkpoints to MongoDB.
    # AgentJobWatcher detects this interrupt, monitors the K8s Job,
    # and resumes the graph with the job result when it completes.
    result = interrupt({
        "type": "waiting_for_agent",
        "action": "agent_wait",
        "job_name": job_name,
        "agent_type": step.agent_type.value,
        "task_id": step_task_id,
        "workspace_path": workspace_full,
        "thread_id": state.get("_thread_id", ""),
        "kotlin_task_id": task.id,
        "client_id": task.client_id,
    })

    # result is the job result dict, provided by AgentJobWatcher on resume
    step_result = StepResult(
        step_index=step.index,
        success=result.get("success", False),
        summary=result.get("summary", "No result"),
        agent_type=step.agent_type.value,
        changed_files=result.get("changedFiles", []),
    )

    existing_results = list(state.get("step_results", []))
    existing_results.append(step_result.model_dump())

    return {"step_results": existing_results}


async def _execute_tracker_step(
    state: dict, task: CodingTask, step: CodingStep,
) -> dict:
    """Execute tracker operations via Kotlin internal API."""
    import httpx
    from app.config import settings as app_settings

    kotlin_url = app_settings.kotlin_server_url
    operations = step.tracker_operations
    results_summary: list[str] = []

    for op in operations:
        action = op.get("action", "create")
        try:
            async with httpx.AsyncClient(timeout=30) as client:
                if action == "create":
                    resp = await client.post(
                        f"{kotlin_url}/internal/tracker/create-issue",
                        json={
                            "clientId": task.client_id,
                            "projectId": task.project_id,
                            "title": op.get("title", ""),
                            "description": op.get("description", ""),
                            "type": op.get("type", "task"),
                            "parentKey": op.get("parent_key"),
                        },
                    )
                    resp.raise_for_status()
                    results_summary.append(f"Created: {op.get('title', '?')}")

                elif action == "update":
                    resp = await client.post(
                        f"{kotlin_url}/internal/tracker/update-issue",
                        json={
                            "clientId": task.client_id,
                            "projectId": task.project_id,
                            "issueKey": op.get("issue_key", ""),
                            "status": op.get("status"),
                            "comment": op.get("comment"),
                        },
                    )
                    resp.raise_for_status()
                    results_summary.append(f"Updated: {op.get('issue_key', '?')}")

                elif action == "comment":
                    resp = await client.post(
                        f"{kotlin_url}/internal/tracker/update-issue",
                        json={
                            "clientId": task.client_id,
                            "projectId": task.project_id,
                            "issueKey": op.get("issue_key", ""),
                            "comment": op.get("comment", step.instructions),
                        },
                    )
                    resp.raise_for_status()
                    results_summary.append(f"Commented: {op.get('issue_key', '?')}")

        except Exception as e:
            logger.warning("Tracker operation failed: %s: %s", action, e)
            results_summary.append(f"Failed {action}: {e}")

    summary = "; ".join(results_summary) if results_summary else "No tracker operations executed"

    step_result = StepResult(
        step_index=step.index,
        success=bool(results_summary),
        summary=summary,
        agent_type="tracker",
    )

    existing_results = list(state.get("step_results", []))
    existing_results.append(step_result.model_dump())

    return {"step_results": existing_results}


async def _prefetch_kb_context(
    task_description: str,
    client_id: str,
    project_id: str | None,
    files: list[str],
) -> str:
    """Pre-fetch KB context. Raises if KB is unavailable."""
    from app.kb.prefetch import prefetch_kb_context
    return await prefetch_kb_context(
        task_description=task_description,
        client_id=client_id,
        project_id=project_id,
        files=files,
    )
