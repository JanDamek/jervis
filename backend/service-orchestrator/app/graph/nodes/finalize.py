"""Finalize node — generate final report.

Replaces the old `report` node with support for all task categories.
Uses LLM to produce a human-readable Czech summary.
"""

from __future__ import annotations

import json
import logging

from app.models import CodingTask, StepResult
from app.graph.nodes._helpers import llm_with_cloud_fallback

logger = logging.getLogger(__name__)


async def finalize(state: dict) -> dict:
    """Generate final report based on task category and results."""
    task_category = state.get("task_category", "advice")

    # ADVICE / respond: final_result already set by respond node
    if state.get("final_result"):
        return {}

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
