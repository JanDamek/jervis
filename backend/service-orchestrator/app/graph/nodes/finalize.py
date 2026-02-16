"""Finalize node — generate final report + KB outcome ingestion.

Replaces the old `report` node with support for all task categories.
Uses LLM to produce a human-readable Czech summary.

After summary generation, significant tasks are ingested into KB
for long-term memory (fire-and-forget, never blocks completion).
"""

from __future__ import annotations

import logging

from app.models import CodingTask, StepResult
from app.graph.nodes._helpers import llm_with_cloud_fallback
from app.kb.outcome_ingest import is_significant_task, extract_outcome, ingest_outcome_to_kb

logger = logging.getLogger(__name__)


async def finalize(state: dict) -> dict:
    """Generate final report based on task category and results.

    Two phases:
    1. Summary generation (existing) — Czech report for user
    2. KB outcome ingestion (new) — structured knowledge for long-term memory
    """
    task_category = state.get("task_category", "advice")

    # --- Phase 1: Generate summary ---

    # ADVICE / respond: final_result already set by respond node
    if state.get("final_result"):
        result = {}
    else:
        result = await _generate_summary_async(state, task_category)

    # --- Phase 2: KB outcome ingestion (fire-and-forget) ---
    # Merge state with result so is_significant_task sees final_result
    merged_state = {**state, **result} if result else state
    kb_ingested = await _try_kb_ingest(merged_state)
    if kb_ingested:
        result["kb_ingested"] = True

    # --- Phase 3: Memory Agent safety net (idempotent flush) ---
    if state.get("memory_agent"):
        try:
            from app.memory.agent import MemoryAgent
            agent = MemoryAgent.from_state_dict(state["memory_agent"])
            await agent.flush_session()
            logger.debug("Finalize: Memory Agent safety-net flush complete")
        except Exception as e:
            logger.warning("Finalize: Memory Agent safety-net flush failed (non-blocking): %s", e)

    return result


async def _generate_summary_async(state: dict, task_category: str) -> dict:
    """Generate the Czech summary for the user (existing logic, extracted)."""
    task = CodingTask(**state["task"])
    step_results = [StepResult(**r) for r in state.get("step_results", [])]
    branch = state.get("branch")

    if not step_results:
        return {
            "final_result": "Nebyl proveden žádný krok.",
        }

    successful = sum(1 for r in step_results if r.success)
    total = len(step_results)

    artifacts = []
    for r in step_results:
        artifacts.extend(r.changed_files)

    # Build context for LLM summarization
    steps_summary = []
    for r in step_results:
        status = "úspěšný" if r.success else "neúspěšný"
        steps_summary.append(f"- Krok {r.step_index + 1} ({r.agent_type}): {status} — {r.summary}")

    steps_block = "\n".join(steps_summary)

    # Context from state
    client_name = state.get("client_name", "")
    project_name = state.get("project_name", "")
    action = state.get("task_action", "respond")
    goals = state.get("goals", [])
    goal_summaries = state.get("goal_summaries", [])

    context_parts = []
    if client_name:
        context_parts.append(f"Klient: {client_name}")
    if project_name:
        context_parts.append(f"Projekt: {project_name}")
    if branch:
        context_parts.append(f"Větev: {branch}")
    if artifacts:
        context_parts.append(f"Změněné soubory: {', '.join(artifacts[:10])}")

    # Conversation context — helps finalize reference prior discussion
    chat_history = state.get("chat_history")
    if chat_history and chat_history.get("total_message_count", 0) > 1:
        context_parts.append(
            f"Konverzace: {chat_history['total_message_count']} zpráv celkem"
        )
        # Include key decisions for continuity
        decisions = []
        for block in (chat_history.get("summary_blocks") or []):
            for d in (block.get("key_decisions") or []):
                decisions.append(d)
        if decisions:
            context_parts.append(
                "Klíčová rozhodnutí: " + "; ".join(decisions[-5:])
            )

    context_block = "\n".join(context_parts)

    messages = [
        {
            "role": "system",
            "content": (
                "Jsi Jervis, AI asistent. Tvůj úkol je shrnout výsledky provedené práce "
                "v krátkém, srozumitelném českém textu pro uživatele.\n\n"
                "Pravidla:\n"
                "- Piš česky, stručně a srozumitelně\n"
                "- Shrň CO bylo provedeno a s jakým výsledkem\n"
                "- Pokud některý krok selhal, uveď to\n"
                "- NIKDY nezobrazuj technické ID úloh\n"
                "- Max 3-5 vět"
            ),
        },
        {
            "role": "user",
            "content": (
                f"Úloha: {task.query}\n"
                f"Typ akce: {action}\n"
                f"Výsledky kroků:\n{steps_block}\n"
                f"\n{context_block}" if context_block else
                f"Úloha: {task.query}\n"
                f"Typ akce: {action}\n"
                f"Výsledky kroků:\n{steps_block}"
            ),
        },
    ]

    try:
        response = await llm_with_cloud_fallback(
            state=state, messages=messages, task_type="summarization", max_tokens=1024,
        )
        summary = response.choices[0].message.content
    except Exception as e:
        logger.warning("LLM summary failed, using fallback: %s", e)
        # Fallback: structured Czech summary without LLM
        if task_category == "single_task":
            summary = f"Provedeno {successful}/{total} kroků ({action})."
            if artifacts:
                summary += f" Změněno: {', '.join(artifacts[:5])}."
            if branch:
                summary += f" Větev: {branch}."
        elif task_category in ("epic", "generative"):
            summary = (
                f"Dokončeno {len(goal_summaries)}/{len(goals)} cílů, "
                f"{successful}/{total} kroků úspěšně."
            )
        else:
            summary = f"Provedeno {successful}/{total} kroků."

    logger.info("Finalize: summary generated (%d chars)", len(summary))

    return {
        "final_result": summary,
        "artifacts": list(set(artifacts)),
    }


async def _try_kb_ingest(state: dict) -> bool:
    """Attempt KB outcome ingestion. Never raises — all errors are logged.

    Returns True if outcome was successfully ingested, False otherwise.
    """
    try:
        if not is_significant_task(state):
            return False

        outcome = await extract_outcome(state)
        if not outcome:
            return False

        task = CodingTask(**state["task"])
        ingested = await ingest_outcome_to_kb(
            task_id=task.id,
            client_id=task.client_id,
            project_id=task.project_id,
            outcome=outcome,
            task_query=task.query,
        )
        if ingested:
            logger.info(
                "KB_OUTCOME_INGESTED | task=%s | topics=%s",
                task.id, outcome.get("topics", []),
            )
        return ingested

    except Exception as e:
        logger.warning("KB outcome ingestion failed (non-blocking): %s", e)
        return False
