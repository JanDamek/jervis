"""execute_delegation node — dispatches delegations to agents via DAG executor.

Takes the ExecutionPlan from plan_delegations and executes all delegations
using the DAGExecutor. Reports progress to Kotlin server.
"""

from __future__ import annotations

import logging

from app.config import settings
from app.graph.dag_executor import DAGExecutor
from app.models import CodingTask, ExecutionPlan
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)


async def execute_delegation(state: dict) -> dict:
    """Execute all delegations in the execution plan.

    Input state:
        execution_plan, task, delegation_states

    Output state:
        delegation_states (updated), delegation_results, completed_delegations,
        active_delegation_id, step_results (for backward compat)
    """
    task = CodingTask(**state["task"])
    plan_data = state.get("execution_plan")

    if not plan_data:
        logger.warning("No execution plan found, skipping delegation")
        return {
            "error": "No execution plan to execute",
            "delegation_results": {},
        }

    plan = ExecutionPlan(**plan_data)

    if not plan.delegations:
        logger.warning("Empty execution plan, skipping delegation")
        return {
            "delegation_results": {},
        }

    logger.info(
        "Executing delegation plan: %d delegations, %d parallel groups",
        len(plan.delegations),
        len(plan.parallel_groups),
    )

    # Progress callback — reports to Kotlin server
    async def _progress_callback(
        delegation_id: str,
        agent_name: str,
        status: str,
        message: str,
    ):
        try:
            await kotlin_client.report_progress(
                task_id=task.id,
                client_id=task.client_id,
                node="execute_delegation",
                message=f"[{agent_name}] {message[:200]}",
                delegation_id=delegation_id,
                delegation_agent=agent_name,
                delegation_depth=1,
                thinking_about=f"Running {agent_name}",
            )
        except Exception as e:
            logger.debug("Progress callback failed: %s", e)

    # Execute via DAG executor
    executor = DAGExecutor()
    outputs, delegation_states = await executor.execute_plan(
        plan=plan,
        state=state,
        progress_callback=_progress_callback,
    )

    # Build results dict
    delegation_results: dict[str, str] = {}
    completed: list[str] = []
    all_changed_files: list[str] = []
    all_artifacts: list[str] = []
    any_success = False

    for output in outputs:
        # Full result — agents follow communication protocol for compact responses
        delegation_results[output.delegation_id] = output.result
        completed.append(output.delegation_id)
        all_changed_files.extend(output.changed_files)
        all_artifacts.extend(output.artifacts)
        if output.success:
            any_success = True

    # Convert delegation_states to serializable dict
    states_dict = {}
    for did, ds in delegation_states.items():
        states_dict[did] = {
            "delegation_id": ds.delegation_id,
            "agent_name": ds.agent_name,
            "status": ds.status.value if hasattr(ds.status, "value") else str(ds.status),
            "result_summary": ds.result_summary,
            "sub_delegation_ids": ds.sub_delegation_ids,
        }

    # Store full outputs in context_store for on-demand retrieval
    try:
        from app.context.context_store import context_store
        for output in outputs:
            await context_store.store(
                task_id=task.id,
                scope="delegation",
                scope_key=output.delegation_id,
                data={
                    "agent_name": output.agent_name,
                    "success": output.success,
                    "result": output.result,
                    "structured_data": output.structured_data,
                    "changed_files": output.changed_files,
                    "artifacts": output.artifacts,
                    "confidence": output.confidence,
                },
            )
    except Exception as e:
        logger.debug("Failed to store delegation results in context_store: %s", e)

    logger.info(
        "Delegation execution complete: %d/%d successful, %d changed files",
        sum(1 for o in outputs if o.success),
        len(outputs),
        len(all_changed_files),
    )

    return {
        "delegation_states": states_dict,
        "delegation_results": delegation_results,
        "completed_delegations": completed,
        "active_delegation_id": None,
        "artifacts": all_artifacts,
        # Store outputs for synthesize node (as list of dicts)
        "_delegation_outputs": [o.model_dump() for o in outputs],
    }
