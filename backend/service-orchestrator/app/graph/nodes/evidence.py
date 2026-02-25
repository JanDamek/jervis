"""Evidence pack node — parallel KB + tracker artifact fetch.

Gathers all context needed for routing decisions and execution.
Validates branch references detected by intake against actual KB data.
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings
from app.kb.prefetch import prefetch_kb_context
from app.models import CodingTask, EvidencePack

logger = logging.getLogger(__name__)


async def _fetch_branch_names(client_id: str, project_id: str | None) -> list[str]:
    """Fetch actual branch names from KB graph.

    Returns list of branch labels, or empty list if KB unavailable.
    """
    url = f"{settings.knowledgebase_url}/api/v1/graph/search"
    params = {
        "query": "",
        "nodeType": "branch",
        "clientId": client_id,
        "limit": 100,
    }
    if project_id:
        params["projectId"] = project_id

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            return [b.get("label", "") for b in resp.json() if b.get("label")]
    except Exception as e:
        logger.debug("Branch list fetch failed (KB may not have data): %s", e)
        return []


def _validate_branch(target: str, known_branches: list[str]) -> str | None:
    """Validate detected branch against known branches.

    Returns:
        Validated branch name (possibly corrected case), or None if no match.
    """
    if not known_branches:
        return target  # Can't validate — keep regex result

    # Exact match
    if target in known_branches:
        return target

    # Case-insensitive match
    target_lower = target.lower()
    for branch in known_branches:
        if branch.lower() == target_lower:
            logger.info("Branch case-corrected: '%s' → '%s'", target, branch)
            return branch

    # Partial suffix match (e.g. user says "auth" but branch is "feature/auth")
    for branch in known_branches:
        if branch.lower().endswith("/" + target_lower):
            logger.info("Branch expanded: '%s' → '%s'", target, branch)
            return branch

    return None


async def evidence_pack(state: dict) -> dict:
    """Parallel fetch: KB + tracker artifacts for evidence gathering.

    Steps:
    1. KB retrieve (relevant knowledge for the task)
    2. For each external_ref: fetch from KB (indexed issues/pages)
    3. Validate target_branch against actual branches from KB
    4. Chat history summary from state (if available)
    5. Assemble EvidencePack
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

    # 3. Validate target branch against actual branches from KB
    target_branch = state.get("target_branch")
    validated_branch = target_branch
    if target_branch:
        kb_branches = await _fetch_branch_names(task.client_id, task.project_id)
        if kb_branches:
            validated = _validate_branch(target_branch, kb_branches)
            if validated:
                validated_branch = validated
                if validated != target_branch:
                    facts.append(f"Branch corrected: '{target_branch}' → '{validated}'")
            else:
                logger.warning(
                    "Branch '%s' (from query) not found in KB (%d known branches)",
                    target_branch, len(kb_branches),
                )
                unknowns.append(
                    f"Branch '{target_branch}' not found in repository "
                    f"(known: {', '.join(kb_branches[:5])})"
                )
                validated_branch = None

    # 4. Chat history summary from state (if available)
    chat_history_summary = ""
    chat_history = state.get("chat_history")
    if chat_history:
        summary_parts = []
        for block in (chat_history.get("summary_blocks") or []):
            summary_parts.append(block.get("summary", ""))
        if summary_parts:
            chat_history_summary = " | ".join(summary_parts)

    # 5. Build evidence pack
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

    result = {"evidence_pack": pack.model_dump()}
    # Update target_branch only if validation changed it
    if target_branch and validated_branch != target_branch:
        result["target_branch"] = validated_branch
    return result
