"""KB outcome ingestion — store completed task outcomes for long-term memory.

After a task completes, this module stores the task result directly into KB
without additional LLM extraction. The KB graph extraction pipeline handles
entity/relationship extraction during ingest.

All operations are fire-and-forget: failures are logged but never block
task completion.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


def is_significant_task(state: dict) -> bool:
    """Determine if the task outcome is worth ingesting to KB."""
    final_result = state.get("final_result")
    if not final_result or not str(final_result).strip():
        return False
    if state.get("error"):
        return False

    task_category = state.get("task_category", "advice")

    # ADVICE with affair context is significant
    if task_category == "advice":
        return bool(state.get("memory_agent"))

    # EPIC and GENERATIVE are always significant
    if task_category in ("epic", "generative"):
        return True

    # SINGLE_TASK with actual work done
    if task_category == "single_task":
        task_action = state.get("task_action", "respond")
        if task_action in ("code", "tracker_ops", "mixed"):
            return True
        if task_action == "respond" and state.get("step_results"):
            return True

    return False


async def extract_outcome(state: dict) -> dict | None:
    """Build outcome directly from task state — no LLM call needed.

    KB graph extraction pipeline will extract entities/relationships
    when the outcome is ingested.
    """
    from app.models import CodingTask, StepResult

    task = CodingTask(**state["task"])
    final_result = state.get("final_result", "")
    task_category = state.get("task_category", "single_task")
    task_action = state.get("task_action", "respond")
    step_results = [StepResult(**r) for r in state.get("step_results", [])]
    branch = state.get("branch")

    # Build summary from actual data
    summary_parts = [f"Task: {task.query[:300]}"]
    summary_parts.append(f"Category: {task_category}, Action: {task_action}")

    if branch:
        summary_parts.append(f"Branch: {branch}")

    if step_results:
        for r in step_results[:5]:
            status = "✓" if r.success else "✗"
            files = ", ".join(r.changed_files[:3]) if r.changed_files else ""
            summary_parts.append(f"  {status} Step {r.step_index + 1} ({r.agent_type}): {r.summary}{' [' + files + ']' if files else ''}")

    # Affair context
    if state.get("memory_agent"):
        active = state["memory_agent"].get("session", {}).get("active_affair")
        if active:
            summary_parts.append(f"Affair: {active.get('title', 'Unknown')}")

    summary_parts.append(f"\nResult: {final_result[:2000]}")

    return {
        "outcome_summary": "\n".join(summary_parts),
        "topics": [],  # KB graph extraction will handle entity extraction
    }


async def ingest_outcome_to_kb(
    task_id: str,
    client_id: str,
    project_id: str | None,
    outcome: dict,
    task_query: str,
) -> bool:
    """POST outcome to KB — graph extraction pipeline handles the rest."""
    kb_write_url = settings.knowledgebase_write_url or settings.knowledgebase_url
    url = f"{kb_write_url}/api/v1/ingest"

    content = f"# Task Outcome: {task_query[:200]}\n\n{outcome.get('outcome_summary', '')}"

    payload = {
        "clientId": client_id,
        "projectId": project_id,
        "sourceUrn": f"task-outcome:{task_id}",
        "kind": "task_outcome",
        "content": content,
        "metadata": {
            "task_id": task_id,
            "task_query": task_query[:500],
            "ingested_at": datetime.now(timezone.utc).isoformat(),
            "source": "task_completion",
        },
    }

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()

        chunk_count = data.get("chunk_count", data.get("chunks_count", 0))
        logger.info("KB_OUTCOME_STORED | task=%s | chunks=%d", task_id, chunk_count)
        return True

    except Exception as e:
        logger.warning("KB outcome ingest failed for task %s: %s", task_id, e)
        return False
