"""EPIC nodes — plan_epic, execute_wave, verify_wave.

Handles EPIC task category: takes an epic + children from tracker,
creates execution waves, and processes them in batches.

Phase 3 implementation — currently wired as placeholder in orchestrator graph.
"""

from __future__ import annotations

import logging

from langgraph.types import interrupt

from app.models import CodingTask, Goal, Complexity
from app.graph.nodes._helpers import (
    llm_with_cloud_fallback,
    parse_json_response,
)

logger = logging.getLogger(__name__)


async def plan_epic(state: dict) -> dict:
    """Fetch epic + children from tracker, create waves, check readiness.

    Steps:
    1. GET /internal/tracker/list-issues (epic children)
    2. Sort by dependencies
    3. Split into waves (1-3 items per wave)
    4. interrupt() for approval of wave structure
    """
    task = CodingTask(**state["task"])
    evidence = state.get("evidence_pack", {})

    # Fetch tracker issues for this epic
    import httpx
    from app.config import settings

    tracker_items = []
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(
                f"{settings.kotlin_server_url}/internal/tracker/list-issues",
                params={
                    "clientId": task.client_id,
                    "projectId": task.project_id or "",
                },
            )
            if resp.status_code == 200:
                tracker_items = resp.json() if isinstance(resp.json(), list) else []
    except Exception as e:
        logger.warning("Failed to fetch tracker issues for epic: %s", e)

    # Build goals from tracker items or LLM decomposition
    if tracker_items:
        goals = [
            Goal(
                id=f"g{i}",
                title=item.get("title", f"Task {i+1}"),
                description=item.get("description", ""),
                complexity=Complexity.MEDIUM,
            )
            for i, item in enumerate(tracker_items)
        ]
    else:
        # Fallback: LLM decomposes the epic description
        messages = [
            {
                "role": "system",
                "content": (
                    "You are an epic planning agent. Break down this epic into "
                    "concrete goals that can be executed in waves (1-3 items per wave).\n\n"
                    "Respond with JSON:\n"
                    '{"goals": [{"id": "g1", "title": "...", "description": "...", '
                    '"complexity": "simple|medium|complex", "dependencies": []}]}'
                ),
            },
            {"role": "user", "content": f"Epic: {task.query}"},
        ]

        response = await llm_with_cloud_fallback(
            state=state, messages=messages, task_type="planning", max_tokens=8192,
        )
        parsed = parse_json_response(response.choices[0].message.content)
        raw_goals = parsed.get("goals", [])

        goals = []
        for g in raw_goals:
            try:
                goals.append(Goal(**g))
            except Exception as e:
                logger.warning("Skipping invalid epic goal: %s (%s)", g, e)

    if not goals:
        goals = [Goal(
            id="g1", title="Execute epic",
            description=task.query, complexity=Complexity.MEDIUM,
        )]

    # Split into waves (1-3 items per wave)
    wave_size = 3
    waves = [goals[i:i + wave_size] for i in range(0, len(goals), wave_size)]

    # Approval gate: show wave structure
    wave_summary = "\n".join(
        f"Wave {i+1}: {', '.join(g.title for g in wave)}"
        for i, wave in enumerate(waves)
    )

    approval = interrupt({
        "type": "approval_request",
        "action": "epic_plan",
        "description": f"Epic plan with {len(goals)} goals in {len(waves)} waves:\n{wave_summary}",
        "task_id": task.id,
        "goals_count": len(goals),
        "waves_count": len(waves),
    })

    if not approval.get("approved", False):
        return {
            "error": "Epic plan rejected by user",
            "final_result": "Epic plán zamítnut uživatelem.",
        }

    logger.info("Epic planned: %d goals in %d waves", len(goals), len(waves))

    return {
        "goals": [g.model_dump() for g in goals],
        "current_goal_index": 0,
    }


async def execute_wave(state: dict) -> dict:
    """Execute one wave — dispatch coding agents for batch.

    Placeholder for Phase 3 — currently delegates to the standard
    execute_step loop.
    """
    logger.info("Execute wave (delegating to standard execution loop)")
    return {}


async def verify_wave(state: dict) -> dict:
    """Verify wave results, update tracker with comments.

    Placeholder for Phase 3.
    """
    logger.info("Verify wave (placeholder)")
    return {}
