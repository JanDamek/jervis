"""DAG executor for parallel delegation execution.

Executes delegations in groups: delegations within a group run in parallel
(asyncio.gather), groups themselves run sequentially.

Feature flag: use_dag_execution
- True: parallel execution within groups
- False: sequential execution (all delegations one by one)
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from app.agents.registry import AgentRegistry
from app.config import settings
from app.models import (
    AgentOutput,
    DelegationMessage,
    DelegationMetrics,
    DelegationState,
    DelegationStatus,
    ExecutionPlan,
)

logger = logging.getLogger(__name__)


class DAGExecutor:
    """Execute delegation plans as a DAG with parallel groups."""

    def __init__(self):
        self._registry = AgentRegistry.instance()

    async def execute_plan(
        self,
        plan: ExecutionPlan,
        state: dict,
        progress_callback: Any | None = None,
    ) -> tuple[list[AgentOutput], dict[str, DelegationState]]:
        """Execute all delegations in the plan.

        Args:
            plan: ExecutionPlan with delegations and parallel_groups.
            state: Current orchestrator state.
            progress_callback: Optional async callback(delegation_id, agent_name, status, message).

        Returns:
            Tuple of (list of AgentOutputs, dict of delegation_id -> DelegationState).
        """
        all_outputs: list[AgentOutput] = []
        delegation_states: dict[str, DelegationState] = {}

        # Initialize all delegation states
        delegation_map: dict[str, DelegationMessage] = {}
        for delegation in plan.delegations:
            delegation_map[delegation.delegation_id] = delegation
            delegation_states[delegation.delegation_id] = DelegationState(
                delegation_id=delegation.delegation_id,
                agent_name=delegation.agent_name,
                status=DelegationStatus.PENDING,
            )

        if settings.use_dag_execution and plan.parallel_groups:
            # DAG mode: execute groups in parallel
            for group_idx, group in enumerate(plan.parallel_groups):
                logger.info(
                    "Executing parallel group %d/%d: %s",
                    group_idx + 1, len(plan.parallel_groups), group,
                )

                group_delegations = [
                    delegation_map[did]
                    for did in group
                    if did in delegation_map
                ]

                if not group_delegations:
                    continue

                group_outputs = await self._execute_parallel_group(
                    group_delegations, state, delegation_states, progress_callback,
                )
                all_outputs.extend(group_outputs)

                # Check if any critical failure should stop execution
                for output in group_outputs:
                    if not output.success and output.confidence < 0.2:
                        logger.warning(
                            "Critical failure in delegation %s (agent=%s), "
                            "stopping plan execution",
                            output.delegation_id, output.agent_name,
                        )
                        return all_outputs, delegation_states
        else:
            # Sequential mode: execute all delegations one by one
            for delegation in plan.delegations:
                output = await self._execute_single(
                    delegation, state, delegation_states, progress_callback,
                )
                all_outputs.append(output)

                # Critical failure check
                if not output.success and output.confidence < 0.2:
                    logger.warning(
                        "Critical failure in delegation %s, stopping",
                        output.delegation_id,
                    )
                    break

        return all_outputs, delegation_states

    async def _execute_parallel_group(
        self,
        delegations: list[DelegationMessage],
        state: dict,
        delegation_states: dict[str, DelegationState],
        progress_callback: Any | None,
    ) -> list[AgentOutput]:
        """Execute a group of delegations in parallel."""
        tasks = [
            self._execute_single(d, state, delegation_states, progress_callback)
            for d in delegations
        ]
        return list(await asyncio.gather(*tasks, return_exceptions=False))

    async def _execute_single(
        self,
        delegation: DelegationMessage,
        state: dict,
        delegation_states: dict[str, DelegationState],
        progress_callback: Any | None,
    ) -> AgentOutput:
        """Execute a single delegation."""
        d_state = delegation_states.get(delegation.delegation_id)
        if d_state:
            d_state.status = DelegationStatus.RUNNING

        if progress_callback:
            await progress_callback(
                delegation.delegation_id,
                delegation.agent_name,
                "running",
                f"Starting {delegation.agent_name}...",
            )

        agent = self._registry.get(delegation.agent_name)
        if agent is None:
            logger.error("Agent '%s' not found in registry", delegation.agent_name)
            output = AgentOutput(
                delegation_id=delegation.delegation_id,
                agent_name=delegation.agent_name,
                success=False,
                result=f"Agent '{delegation.agent_name}' not found in registry.",
                confidence=0.0,
            )
            if d_state:
                d_state.status = DelegationStatus.FAILED
                d_state.result_summary = output.result
            return output

        start_time = time.monotonic()
        try:
            output = await asyncio.wait_for(
                agent.execute(delegation, state),
                timeout=settings.delegation_timeout,
            )
        except asyncio.TimeoutError:
            logger.error(
                "Delegation %s timed out after %ds (agent=%s)",
                delegation.delegation_id,
                settings.delegation_timeout,
                delegation.agent_name,
            )
            output = AgentOutput(
                delegation_id=delegation.delegation_id,
                agent_name=delegation.agent_name,
                success=False,
                result=f"Delegation timed out after {settings.delegation_timeout}s.",
                confidence=0.0,
            )
        except Exception as e:
            logger.error(
                "Delegation %s failed with error: %s (agent=%s)",
                delegation.delegation_id, e, delegation.agent_name,
                exc_info=True,
            )
            output = AgentOutput(
                delegation_id=delegation.delegation_id,
                agent_name=delegation.agent_name,
                success=False,
                result=f"Agent execution error: {e}",
                confidence=0.0,
            )

        elapsed = time.monotonic() - start_time
        logger.info(
            "Delegation %s completed: agent=%s, success=%s, confidence=%.2f, "
            "elapsed=%.1fs, result_len=%d",
            delegation.delegation_id,
            delegation.agent_name,
            output.success,
            output.confidence,
            elapsed,
            len(output.result),
        )

        # Update state
        if d_state:
            d_state.status = (
                DelegationStatus.COMPLETED if output.success
                else DelegationStatus.FAILED
            )
            # Summarize result (max 500 chars for parent visibility)
            d_state.result_summary = output.result[:500]
            d_state.sub_delegation_ids = output.sub_delegations

        if progress_callback:
            status = "completed" if output.success else "failed"
            await progress_callback(
                delegation.delegation_id,
                delegation.agent_name,
                status,
                d_state.result_summary if d_state else "",
            )

        return output
