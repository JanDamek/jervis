"""Execute Delegation node — dispatches DelegationMessages to agents.

Iterates through the ExecutionPlan's parallel groups, dispatching
delegations to agents via the AgentRegistry. Reports progress to
the Kotlin server after each delegation completes.
"""

from __future__ import annotations

import asyncio
import logging
import time

from app.agents.registry import AgentRegistry
from app.config import settings
from app.context.summarizer import summarize_agent_output, summarize_for_session
from app.context.session_memory import session_memory_store
from app.models import (
    AgentOutput,
    CodingTask,
    DelegationMessage,
    DelegationState,
    DelegationStatus,
    ExecutionPlan,
    SessionEntry,
)
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)


async def execute_delegation(state: dict) -> dict:
    """Execute all delegations in the execution plan.

    State reads:
        task, execution_plan, delegation_states
    State writes:
        delegation_states, delegation_results, completed_delegations,
        active_delegation_id, artifacts, final_result (for single-agent plans)
    """
    task = CodingTask(**state["task"])
    plan_data = state.get("execution_plan")
    if not plan_data:
        return {"error": "No execution plan found."}

    plan = ExecutionPlan(**plan_data)
    if not plan.delegations:
        return {"error": "Execution plan has no delegations."}

    delegation_states = {
        k: DelegationState(**v) if isinstance(v, dict) else v
        for k, v in state.get("delegation_states", {}).items()
    }
    delegation_results: dict[str, str] = dict(state.get("delegation_results", {}))
    completed: list[str] = list(state.get("completed_delegations", []))
    all_artifacts: list[str] = list(state.get("artifacts", []))
    all_changed_files: list[str] = []

    registry = AgentRegistry.instance()

    # Build delegation lookup
    delegation_map: dict[str, DelegationMessage] = {
        d.delegation_id: d for d in plan.delegations
    }

    total_delegations = len(plan.delegations)
    completed_count = len(completed)

    # --- Execute parallel groups sequentially ---
    for group_idx, group_ids in enumerate(plan.parallel_groups):
        # Filter out already-completed delegations (for resume support)
        pending_ids = [did for did in group_ids if did not in completed]
        if not pending_ids:
            continue

        await _report(
            task,
            f"Executing group {group_idx + 1}/{len(plan.parallel_groups)} "
            f"({len(pending_ids)} delegation(s))",
            _calc_percent(completed_count, total_delegations),
        )

        if settings.use_dag_execution and len(pending_ids) > 1:
            # Parallel execution within group
            results = await _execute_parallel(
                pending_ids, delegation_map, delegation_states,
                registry, state, task,
            )
        else:
            # Sequential execution
            results = []
            for did in pending_ids:
                result = await _execute_single(
                    did, delegation_map, delegation_states,
                    registry, state, task,
                )
                results.append(result)

        # Process results
        for did, output in results:
            ds = delegation_states.get(did)
            if ds:
                ds.status = (
                    DelegationStatus.COMPLETED if output.success
                    else DelegationStatus.FAILED
                )
                ds.result_summary = output.result[:500] if output.result else ""
                delegation_states[did] = ds

            delegation_results[did] = summarize_agent_output(output)
            completed.append(did)
            completed_count += 1
            all_artifacts.extend(output.artifacts)
            all_changed_files.extend(output.changed_files)

            # Save to session memory
            try:
                await session_memory_store.append(
                    client_id=task.client_id,
                    project_id=task.project_id,
                    entry=SessionEntry(
                        timestamp=str(int(time.time())),
                        source="orchestrator_decision",
                        summary=summarize_for_session(output),
                        task_id=task.id,
                    ),
                )
            except Exception:
                pass  # Non-critical

            await _report(
                task,
                f"✓ {output.agent_name}: {'success' if output.success else 'failed'} "
                f"({completed_count}/{total_delegations})",
                _calc_percent(completed_count, total_delegations),
            )

    return {
        "delegation_states": {
            k: v.model_dump() if hasattr(v, "model_dump") else v
            for k, v in delegation_states.items()
        },
        "delegation_results": delegation_results,
        "completed_delegations": completed,
        "active_delegation_id": None,
        "artifacts": all_artifacts,
    }


# ---------------------------------------------------------------------------
# Execution helpers
# ---------------------------------------------------------------------------


async def _execute_single(
    delegation_id: str,
    delegation_map: dict[str, DelegationMessage],
    delegation_states: dict[str, DelegationState],
    registry: AgentRegistry,
    state: dict,
    task: CodingTask,
) -> tuple[str, AgentOutput]:
    """Execute a single delegation and return (id, output)."""
    msg = delegation_map.get(delegation_id)
    if not msg:
        return delegation_id, AgentOutput(
            delegation_id=delegation_id,
            agent_name="unknown",
            success=False,
            result=f"Delegation {delegation_id} not found in plan.",
        )

    agent = registry.get(msg.agent_name)
    if not agent:
        logger.warning("Agent '%s' not in registry, falling back to legacy", msg.agent_name)
        agent = registry.get("legacy")
        if not agent:
            return delegation_id, AgentOutput(
                delegation_id=delegation_id,
                agent_name=msg.agent_name,
                success=False,
                result=f"Agent '{msg.agent_name}' not found and no legacy fallback.",
            )

    # Update state
    ds = delegation_states.get(delegation_id)
    if ds:
        ds.status = DelegationStatus.RUNNING
        delegation_states[delegation_id] = ds

    logger.info(
        "Executing delegation: id=%s agent=%s task=%s",
        delegation_id, msg.agent_name, msg.task_summary[:60],
    )

    try:
        output = await asyncio.wait_for(
            agent.execute(msg, state),
            timeout=settings.delegation_timeout,
        )
    except asyncio.TimeoutError:
        logger.error(
            "Delegation %s timed out after %ds", delegation_id, settings.delegation_timeout,
        )
        output = AgentOutput(
            delegation_id=delegation_id,
            agent_name=msg.agent_name,
            success=False,
            result=f"Delegation timed out after {settings.delegation_timeout}s.",
            confidence=0.0,
        )
    except Exception as exc:
        logger.error(
            "Delegation %s failed with exception: %s", delegation_id, exc,
        )
        output = AgentOutput(
            delegation_id=delegation_id,
            agent_name=msg.agent_name,
            success=False,
            result=f"Delegation failed: {exc}",
            confidence=0.0,
        )

    return delegation_id, output


async def _execute_parallel(
    delegation_ids: list[str],
    delegation_map: dict[str, DelegationMessage],
    delegation_states: dict[str, DelegationState],
    registry: AgentRegistry,
    state: dict,
    task: CodingTask,
) -> list[tuple[str, AgentOutput]]:
    """Execute multiple delegations concurrently."""
    coros = [
        _execute_single(did, delegation_map, delegation_states, registry, state, task)
        for did in delegation_ids
    ]
    return await asyncio.gather(*coros, return_exceptions=False)


def _calc_percent(completed: int, total: int) -> int:
    """Calculate progress percentage (40-90 range for execution phase)."""
    if total == 0:
        return 40
    return 40 + int(50 * completed / total)


async def _report(task: CodingTask, message: str, percent: int) -> None:
    """Send progress report to Kotlin server."""
    try:
        await kotlin_client.report_progress(
            task_id=task.id,
            client_id=task.client_id,
            node="execute_delegation",
            message=message,
            percent=percent,
        )
    except Exception:
        pass
