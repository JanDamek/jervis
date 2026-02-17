"""KB outcome ingestion — store completed task outcomes for long-term memory.

After a task completes in the finalize node, this module:
1. Checks if the task is "significant" (worth ingesting)
2. Extracts structured knowledge via LLM
3. Ingests the outcome into KB for future semantic retrieval

All operations are fire-and-forget: failures are logged but never block
task completion.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime

import httpx

from app.config import settings
from app.models import CodingTask, ModelTier, StepResult

logger = logging.getLogger(__name__)


# --- Significance filter (deterministic, no LLM) ---


def is_significant_task(state: dict) -> bool:
    """Determine if the task outcome is worth ingesting to KB.

    Significant tasks produce meaningful work that should be remembered:
    - SINGLE_TASK with code/tracker_ops/mixed action
    - EPIC and GENERATIVE tasks (always significant)
    - SINGLE_TASK/respond with non-empty step_results

    Not significant:
    - ADVICE tasks (simple Q&A)
    - Tasks with errors or no final_result
    """
    # No result = nothing to ingest
    final_result = state.get("final_result")
    if not final_result or not str(final_result).strip():
        return False

    # Error present = failed task, skip
    if state.get("error"):
        return False

    task_category = state.get("task_category", "advice")
    task_action = state.get("task_action", "respond")

    # ADVICE — normally not significant, UNLESS Memory Agent tracked an affair
    if task_category == "advice":
        if state.get("memory_agent"):
            # Affair context means this ADVICE had structured context worth preserving
            logger.debug("ADVICE task with affair context → significant for KB ingestion")
            return True
        return False

    # EPIC and GENERATIVE are always significant
    if task_category in ("epic", "generative"):
        return True

    # SINGLE_TASK with code/tracker/mixed action
    if task_category == "single_task":
        if task_action in ("code", "tracker_ops", "mixed"):
            return True
        # respond action — only if actual steps were executed
        if task_action == "respond" and state.get("step_results"):
            return True

    return False


# --- LLM-based outcome extraction ---


_EXTRACTION_SYSTEM_PROMPT = """\
You are a knowledge extraction engine. Given a completed task's context, \
extract structured knowledge for future reference.

Output ONLY a valid JSON object with these fields:
{
  "outcome_summary": "2-3 sentences describing what was done and the result",
  "key_decisions": ["decision 1", "decision 2"],
  "patterns_used": ["pattern or approach used"],
  "artifacts": ["changed files, PRs, commits"],
  "lessons_learned": ["what worked, what didn't"],
  "topics": ["searchable keywords for future retrieval"]
}

Rules:
- Be concise (each list max 3-5 items)
- Focus on WHAT was done and WHY
- Include technical details useful for similar future tasks
- Topics should be searchable keywords (e.g. "retry", "backoff", "circuit breaker")
- Output ONLY JSON, no markdown fences, no explanation
"""


async def extract_outcome(state: dict) -> dict | None:
    """Use LLM to extract structured knowledge from completed task.

    Calls LLM with LOCAL_FAST tier (short prompt, short output).
    Returns parsed dict or None on failure.
    """
    from app.graph.nodes._helpers import llm_with_cloud_fallback

    task = CodingTask(**state["task"])
    step_results = [StepResult(**r) for r in state.get("step_results", [])]
    task_category = state.get("task_category", "single_task")
    task_action = state.get("task_action", "respond")
    final_result = state.get("final_result", "")
    branch = state.get("branch")
    goal_summaries = state.get("goal_summaries", [])
    artifacts = state.get("artifacts", [])

    # Build context for LLM
    steps_text = ""
    if step_results:
        lines = []
        for r in step_results:
            status = "success" if r.success else "failure"
            files = ", ".join(r.changed_files[:5]) if r.changed_files else "none"
            lines.append(f"- Step {r.step_index + 1} ({r.agent_type}): {status} — {r.summary} [files: {files}]")
        steps_text = "\n".join(lines)

    goals_text = ""
    if goal_summaries:
        lines = []
        for gs in goal_summaries:
            if isinstance(gs, dict):
                lines.append(f"- {gs.get('goal_title', 'Goal')}: {gs.get('summary', '')}")
        goals_text = "\n".join(lines)

    context_parts = [
        f"Task query: {task.query}",
        f"Category: {task_category}, Action: {task_action}",
    ]
    if branch:
        context_parts.append(f"Branch: {branch}")
    if steps_text:
        context_parts.append(f"Steps:\n{steps_text}")
    if goals_text:
        context_parts.append(f"Goals:\n{goals_text}")
    if artifacts:
        context_parts.append(f"Artifacts: {', '.join(artifacts[:10])}")
    # Enrich with affair context when Memory Agent is active
    if state.get("memory_agent"):
        memory_agent_data = state["memory_agent"]
        session = memory_agent_data.get("session", {})
        active = session.get("active_affair")
        if active:
            context_parts.append(f"Active affair: {active.get('title', 'Unknown')}")
            key_facts = active.get("key_facts", [])
            if key_facts:
                context_parts.append(f"Key facts: {'; '.join(key_facts[:10])}")
        parked = session.get("parked_affairs", [])
        if parked:
            parked_titles = [a.get("title", "?") for a in parked[:5]]
            context_parts.append(f"Parked affairs: {', '.join(parked_titles)}")

    context_parts.append(f"Final result: {final_result[:2000]}")

    messages = [
        {"role": "system", "content": _EXTRACTION_SYSTEM_PROMPT},
        {"role": "user", "content": "\n\n".join(context_parts)},
    ]

    try:
        response = await llm_with_cloud_fallback(
            state=state,
            messages=messages,
            task_type="summarization",
            max_tokens=1024,
            temperature=0.1,
        )
        raw = response.choices[0].message.content
        if not raw:
            logger.warning("KB outcome extraction: empty LLM response")
            return None

        # Strip markdown fences if present
        raw = raw.strip()
        if raw.startswith("```"):
            raw = raw.split("\n", 1)[1] if "\n" in raw else raw[3:]
            if raw.endswith("```"):
                raw = raw[:-3]
            raw = raw.strip()

        outcome = json.loads(raw)

        # Validate required fields
        required = ("outcome_summary", "topics")
        for field in required:
            if field not in outcome or not outcome[field]:
                logger.warning("KB outcome extraction: missing field '%s'", field)
                return None

        # Ensure all list fields exist (even if empty)
        for field in ("key_decisions", "patterns_used", "artifacts", "lessons_learned", "topics"):
            if field not in outcome:
                outcome[field] = []

        return outcome

    except json.JSONDecodeError as e:
        logger.warning("KB outcome extraction: JSON parse error: %s", e)
        return None
    except Exception as e:
        logger.warning("KB outcome extraction LLM call failed: %s", e)
        return None


# --- KB ingest ---


async def ingest_outcome_to_kb(
    task_id: str,
    client_id: str,
    project_id: str | None,
    outcome: dict,
    task_query: str,
) -> bool:
    """POST structured outcome to KB write endpoint.

    Uses stable sourceUrn (task-outcome:{task_id}) so re-runs overwrite
    the previous entry.

    Returns True on success, False on failure. Never raises.
    """
    kb_write_url = settings.knowledgebase_write_url or settings.knowledgebase_url
    url = f"{kb_write_url}/api/v1/ingest"

    # Build markdown content for KB
    sections = [f"# Task Outcome: {task_query[:200]}"]

    sections.append(f"\n## Summary\n{outcome.get('outcome_summary', '')}")

    if outcome.get("key_decisions"):
        sections.append("\n## Key Decisions")
        for d in outcome["key_decisions"]:
            sections.append(f"- {d}")

    if outcome.get("patterns_used"):
        sections.append("\n## Patterns Used")
        for p in outcome["patterns_used"]:
            sections.append(f"- {p}")

    if outcome.get("artifacts"):
        sections.append("\n## Artifacts")
        for a in outcome["artifacts"]:
            sections.append(f"- {a}")

    if outcome.get("lessons_learned"):
        sections.append("\n## Lessons Learned")
        for l in outcome["lessons_learned"]:
            sections.append(f"- {l}")

    content = "\n".join(sections)
    timestamp = datetime.now().isoformat()

    payload = {
        "clientId": client_id,
        "projectId": project_id,
        "sourceUrn": f"task-outcome:{task_id}",
        "kind": "task_outcome",
        "content": content,
        "metadata": {
            "task_id": task_id,
            "task_query": task_query[:500],
            "topics": ",".join(outcome.get("topics", [])),
            "ingested_at": timestamp,
            "source": "task_completion",
        },
    }

    # No priority header = NORMAL (background, non-urgent embedding)
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()

        chunk_count = data.get("chunk_count", data.get("chunks_count", 0))
        logger.info(
            "KB_OUTCOME_STORED | task=%s | chunks=%d | topics=%s",
            task_id, chunk_count, outcome.get("topics", []),
        )
        return True

    except httpx.TimeoutException:
        logger.warning("KB outcome ingest timed out (15s) for task %s", task_id)
        return False
    except httpx.HTTPStatusError as e:
        logger.warning("KB outcome ingest HTTP %d for task %s", e.response.status_code, task_id)
        return False
    except Exception as e:
        logger.warning("KB outcome ingest failed for task %s: %s", task_id, e)
        return False
