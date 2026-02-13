"""Legacy agent — wraps existing 14-node orchestrator logic as fallback.

When use_specialist_agents=False (default), the orchestrator uses this agent
as a catch-all that delegates to the existing respond/plan/execute_step nodes.
This ensures backward compatibility during rollout.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import ALL_RESPOND_TOOLS_FULL

logger = logging.getLogger(__name__)


class LegacyAgent(BaseAgent):
    """Fallback agent wrapping existing orchestrator node logic.

    Routes tasks to existing respond node for ADVICE-type tasks,
    or returns a result indicating coding delegation is needed.
    """

    name = "legacy"
    description = (
        "Fallback agent for backward compatibility. "
        "Handles all domains using existing orchestrator node logic."
    )
    domains = list(DomainType)  # Handles everything
    tools = ALL_RESPOND_TOOLS_FULL
    can_sub_delegate = False

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute using existing orchestrator logic.

        For analytical tasks: uses the agentic loop (same as respond node).
        For coding tasks: returns a structured output indicating K8s Job needed.
        """
        logger.info(
            "LegacyAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        # Use standard agentic loop with full tool set
        system_prompt = (
            "You are Jervis, a helpful AI assistant for software development and project management.\n"
            "You have access to tools for searching the knowledge base, web, and project files.\n"
            "Answer the user's question accurately and concisely.\n"
            "If you need more information, use the available tools.\n"
            "\nAlways respond in English (internal processing language)."
        )

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=15,
        )
