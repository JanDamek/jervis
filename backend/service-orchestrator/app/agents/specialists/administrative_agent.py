"""Administrative Agent -- travel, logistics, and organizational tasks.

Handles travel planning, logistics coordination, office administration,
and other organizational tasks. Sub-delegates to CalendarAgent for
scheduling-related operations.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_WEB_SEARCH, TOOL_KB_SEARCH

logger = logging.getLogger(__name__)


_ADMINISTRATIVE_TOOLS: list[dict] = [
    TOOL_WEB_SEARCH,
    TOOL_KB_SEARCH,
]


class AdministrativeAgent(BaseAgent):
    """Specialist agent for administrative and organizational tasks.

    Handles travel planning, logistics coordination, office management,
    and general organizational tasks. Sub-delegates to CalendarAgent
    for scheduling and event management.
    """

    name = "administrative"
    description = (
        "Handles travel planning, logistics, organizational tasks, "
        "and office administration. Sub-delegates to CalendarAgent "
        "for scheduling operations."
    )
    domains = [DomainType.ADMINISTRATIVE]
    tools = _ADMINISTRATIVE_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute administrative operations.

        Strategy:
        1. If the task involves scheduling, sub-delegate to CalendarAgent.
        2. Run agentic loop with web search and KB for planning and logistics.
        """
        logger.info(
            "AdministrativeAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        # Sub-delegate scheduling tasks to CalendarAgent
        if self._is_scheduling_task(msg):
            calendar_output = await self._sub_delegate(
                target_agent_name="calendar",
                task_summary=msg.task_summary,
                context=msg.context,
                parent_msg=msg,
                state=state,
            )
            if calendar_output.success:
                return calendar_output

        system_prompt = (
            "You are the AdministrativeAgent, a specialist in travel planning, "
            "logistics, and organizational management.\n\n"
            "Your capabilities:\n"
            "- Search the web for travel options, venues, and logistics info\n"
            "- Search the knowledge base for internal policies and contacts\n"
            "- Coordinate multi-step administrative tasks\n"
            "- Plan travel itineraries with transportation and accommodation\n\n"
            "Guidelines:\n"
            "- Search for company travel policies before planning\n"
            "- Consider budget constraints and preferences\n"
            "- Provide multiple options when possible\n"
            "- Include all relevant details (addresses, times, costs)\n"
            "- Verify information from multiple sources\n"
            "- Organize results in a clear, actionable format\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=10,
        )

    @staticmethod
    def _is_scheduling_task(msg: DelegationMessage) -> bool:
        """Heuristic: is this primarily a scheduling/calendar task?"""
        scheduling_keywords = [
            "schedule", "meeting", "appointment", "calendar",
            "remind", "reminder", "event", "book a time",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in scheduling_keywords)
