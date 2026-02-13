"""Personal Agent -- personal assistance tasks.

Handles personal tasks such as shopping lists, car maintenance
schedules, travel planning, appointment booking, and general
life administration. Sub-delegates to CalendarAgent for scheduling.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_WEB_SEARCH, TOOL_KB_SEARCH

logger = logging.getLogger(__name__)


TOOL_PLANNING_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "planning_create",
        "description": (
            "Create a personal plan or checklist. Supports various plan "
            "types: shopping list, maintenance schedule, travel itinerary, "
            "task checklist, and custom plans with steps and deadlines."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Plan or checklist title.",
                },
                "plan_type": {
                    "type": "string",
                    "enum": [
                        "shopping_list", "maintenance_schedule",
                        "travel_itinerary", "task_checklist", "custom",
                    ],
                    "description": "Type of plan to create.",
                },
                "items": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "description": {
                                "type": "string",
                                "description": "Item or step description.",
                            },
                            "due_date": {
                                "type": "string",
                                "description": "Due date in ISO 8601 format (optional).",
                            },
                            "priority": {
                                "type": "string",
                                "enum": ["low", "medium", "high"],
                                "description": "Item priority (optional).",
                            },
                        },
                        "required": ["description"],
                    },
                    "description": "List of items or steps in the plan.",
                },
                "notes": {
                    "type": "string",
                    "description": "Additional notes or context for the plan.",
                },
            },
            "required": ["title", "plan_type", "items"],
        },
    },
}


_PERSONAL_TOOLS: list[dict] = [
    TOOL_WEB_SEARCH,
    TOOL_KB_SEARCH,
    TOOL_PLANNING_CREATE,
]


class PersonalAgent(BaseAgent):
    """Specialist agent for personal assistance tasks.

    Handles shopping lists, car maintenance schedules, travel planning,
    appointment booking, and general life administration. Sub-delegates
    to CalendarAgent for scheduling and reminder operations.
    """

    name = "personal"
    description = (
        "Handles personal tasks: shopping lists, car maintenance, "
        "travel planning, and general life administration. "
        "Sub-delegates to CalendarAgent for scheduling."
    )
    domains = [DomainType.PERSONAL]
    tools = _PERSONAL_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute personal assistance operations.

        Strategy:
        1. If the task involves scheduling, sub-delegate to CalendarAgent.
        2. Run agentic loop with web search, KB, and planning tools.
        """
        logger.info(
            "PersonalAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        # Sub-delegate scheduling-related tasks to CalendarAgent
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
            "You are the PersonalAgent, a specialist in personal "
            "assistance and life administration.\n\n"
            "Your capabilities:\n"
            "- Search the web for products, services, and information\n"
            "- Search the knowledge base for personal preferences and history\n"
            "- Create shopping lists, checklists, and maintenance schedules\n"
            "- Plan travel itineraries with research\n\n"
            "Guidelines:\n"
            "- Consider personal preferences from previous interactions\n"
            "- Provide practical, actionable plans and lists\n"
            "- Include prices and availability when searching for products\n"
            "- Organize items by priority or category\n"
            "- Include deadlines and reminders for time-sensitive items\n"
            "- Verify information from multiple sources when possible\n"
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
        """Heuristic: does this task need calendar/scheduling?"""
        scheduling_keywords = [
            "schedule", "appointment", "book", "reserve",
            "reminder", "calendar", "deadline", "event",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in scheduling_keywords)
