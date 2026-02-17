"""Evidence pack node — parallel KB + tracker artifact fetch.

Gathers all context needed for routing decisions and execution.
"""

from __future__ import annotations

import logging

from app.kb.prefetch import prefetch_kb_context
from app.models import CodingTask, EvidencePack

logger = logging.getLogger(__name__)


async def evidence_pack(state: dict) -> dict:
    """Parallel fetch: KB + tracker artifacts for evidence gathering.

    Steps:
    1. KB retrieve (relevant knowledge for the task)
    2. For each external_ref: fetch from KB (indexed issues/pages)
    3. Chat history summary from KB (if indexed)
    4. Assemble EvidencePack
    5. Compress if > 48k tokens
    """
    task = CodingTask(**state["task"])
    external_refs = state.get("external_refs", [])

    kb_results: list[dict] = []
    tracker_artifacts: list[dict] = []
    facts: list[str] = []
    unknowns: list[str] = []

    # 1. KB retrieve — task-relevant context
    # Use search_queries from state if available (transformed by intake node)
    search_queries = state.get("kb_search_queries")
    try:
        kb_context = await prefetch_kb_context(
            task_description=task.query,
            client_id=task.client_id,
            project_id=task.project_id,
            search_queries=search_queries,
            processing_mode=state.get("processing_mode", "FOREGROUND"),
        )
        if kb_context:
            kb_results.append({
                "source": "kb_retrieve",
                "content": kb_context,
            })
    except Exception as e:
        logger.warning("KB retrieve failed: %s: %s", type(e).__name__, e)
        unknowns.append(f"KB retrieve failed: {e}")

    # 2. External refs — fetch from KB (indexed issues/pages)
    for ref in external_refs[:10]:  # Cap at 10 refs
        try:
            ref_context = await prefetch_kb_context(
                task_description=f"Issue/page: {ref}",
                client_id=task.client_id,
                project_id=task.project_id,
                processing_mode=state.get("processing_mode", "FOREGROUND"),
            )
            if ref_context:
                tracker_artifacts.append({
                    "ref": ref,
                    "content": ref_context[:2000],  # Truncate per-ref
                })
                facts.append(f"Found KB context for {ref}")
            else:
                unknowns.append(f"No KB context found for {ref}")
        except Exception as e:
            logger.warning("KB fetch for ref %s failed: %s", ref, e)
            unknowns.append(f"Failed to fetch {ref}: {e}")

    # 3. Chat history summary from state (if available)
    chat_history_summary = ""
    chat_history = state.get("chat_history")
    if chat_history:
        summary_parts = []
        for block in (chat_history.get("summary_blocks") or []):
            summary_parts.append(block.get("summary", ""))
        if summary_parts:
            chat_history_summary = " | ".join(summary_parts)

    # 4. Build evidence pack
    pack = EvidencePack(
        kb_results=kb_results,
        tracker_artifacts=tracker_artifacts,
        chat_history_summary=chat_history_summary,
        external_refs=external_refs,
        facts=facts,
        unknowns=unknowns,
    )

    logger.info(
        "Evidence pack: %d kb_results, %d tracker_artifacts, %d facts, %d unknowns",
        len(pack.kb_results), len(pack.tracker_artifacts),
        len(pack.facts), len(pack.unknowns),
    )

    return {"evidence_pack": pack.model_dump()}
