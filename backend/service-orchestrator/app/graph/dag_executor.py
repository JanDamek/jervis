"""DAG Executor — parallel execution of delegation groups.

Executes an ExecutionPlan by processing parallel groups in order.
Within each group, delegations run concurrently via asyncio.gather.
Between groups, execution is sequential (group N must finish before
group N+1 starts).

This module is used by the execute_delegation node when
``settings.use_dag_execution`` is enabled.
"""

from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING

from app.config import settings
from app.models import AgentOutput, DelegationMessage, ExecutionPlan

if TYPE_CHECKING:
    from app.agents.registry import AgentRegistry

logger = logging.getLogger(__name__)


class DAGExecutor:
    """Execute a delegation plan respecting parallel group ordering."""

    async def execute_plan(
        self,
        plan: ExecutionPlan,
        state: dict,
        registry: AgentRegistry,
    ) -> list[tuple[str, AgentOutput]]:
        """Execute all delegations in the plan.

        Args:
            plan: The execution plan with delegations and parallel groups.
            state: The orchestrator state dict.
            registry: The agent registry for looking up agents.

        Returns:
            List of (delegation_id, AgentOutput) tuples in execution order.
        """
        delegation_map = {d.delegation_id: d for d in plan.delegations}
        results: list[tuple[str, AgentOutput]] = []

        for group_idx, group_ids in enumerate(plan.parallel_groups):
            logger.info(
                "DAGExecutor: executing group %d/%d (%d delegations)",
                group_idx + 1, len(plan.parallel_groups), len(group_ids),
            )

            if len(group_ids) == 1:
                # Single delegation — no need for gather
                did = group_ids[0]
                output = await self._execute_one(
                    did, delegation_map, registry, state,
                )
                results.append((did, output))
            else:
                # Parallel execution
                coros = [
                    self._execute_one(did, delegation_map, registry, state)
                    for did in group_ids
                ]
                outputs = await asyncio.gather(*coros, return_exceptions=True)

                for did, output_or_exc in zip(group_ids, outputs):
                    if isinstance(output_or_exc, Exception):
                        logger.error(
                            "DAGExecutor: delegation %s raised: %s",
                            did, output_or_exc,
                        )
                        output = AgentOutput(
                            delegation_id=did,
                            agent_name=delegation_map.get(did, DelegationMessage(
                                delegation_id=did, agent_name="unknown",
                                task_summary="",
                            )).agent_name,
                            success=False,
                            result=f"Exception: {output_or_exc}",
                            confidence=0.0,
                        )
                    else:
                        output = output_or_exc
                    results.append((did, output))

        return results

    async def _execute_one(
        self,
        delegation_id: str,
        delegation_map: dict[str, DelegationMessage],
        registry: AgentRegistry,
        state: dict,
    ) -> AgentOutput:
        """Execute a single delegation with timeout."""
        msg = delegation_map.get(delegation_id)
        if not msg:
            return AgentOutput(
                delegation_id=delegation_id,
                agent_name="unknown",
                success=False,
                result=f"Delegation {delegation_id} not found.",
            )

        agent = registry.get(msg.agent_name)
        if not agent:
            agent = registry.get("legacy")
        if not agent:
            return AgentOutput(
                delegation_id=delegation_id,
                agent_name=msg.agent_name,
                success=False,
                result=f"Agent '{msg.agent_name}' not found.",
            )

        try:
            return await asyncio.wait_for(
                agent.execute(msg, state),
                timeout=settings.delegation_timeout,
            )
        except asyncio.TimeoutError:
            return AgentOutput(
                delegation_id=delegation_id,
                agent_name=msg.agent_name,
                success=False,
                result=f"Timed out after {settings.delegation_timeout}s.",
                confidence=0.0,
            )
        except Exception as exc:
            return AgentOutput(
                delegation_id=delegation_id,
                agent_name=msg.agent_name,
                success=False,
                result=f"Failed: {exc}",
                confidence=0.0,
            )


# Singleton
dag_executor = DAGExecutor()
