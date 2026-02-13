"""PersonalAgent -- Personal life management specialist.

Handles personal tasks such as shopping lists, car maintenance tracking,
vacation planning, household organisation, and lifestyle management.
Sub-delegates to CalendarAgent for scheduling-related sub-tasks.
"""

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH, TOOL_WEB_SEARCH

# ---------------------------------------------------------------------------
# Inline tool definitions
# ---------------------------------------------------------------------------

TOOL_PLANNING_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "planning_create",
        "description": (
            "Create a structured personal plan (shopping list, maintenance "
            "schedule, vacation itinerary, task checklist, etc.)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "plan_type": {
                    "type": "string",
                    "enum": ["shopping_list", "maintenance_schedule", "vacation_itinerary", "task_checklist", "meal_plan", "packing_list", "custom"],
                    "description": "Type of plan to create.",
                },
                "title": {"type": "string", "description": "Title of the plan."},
                "items": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "quantity": {"type": "string"},
                            "notes": {"type": "string"},
                            "priority": {"type": "string", "enum": ["high", "medium", "low"]},
                            "due_date": {"type": "string"},
                        },
                        "required": ["name"],
                    },
                    "description": "List of items or tasks in the plan.",
                },
                "deadline": {"type": "string", "description": "ISO-8601 deadline for the entire plan."},
                "notes": {"type": "string", "description": "Additional notes or context."},
                "tags": {"type": "array", "items": {"type": "string"}, "description": "Tags for categorisation."},
            },
            "required": ["plan_type", "title"],
        },
    },
}

SYSTEM_PROMPT = """\
You are the PersonalAgent -- a specialist for personal life management within
the Jervis assistant.

Your capabilities:
1. Create and manage shopping lists with quantities and priorities.
2. Track car maintenance schedules (service intervals, inspections, tyres).
3. Plan vacations with itineraries, packing lists, and bookings research.
4. Organise household tasks, chores, and maintenance.
5. Research products, services, and local businesses via web search.
6. Access personal knowledge base for preferences, history, and notes.

Guidelines:
- For any task requiring calendar entries or reminders, sub-delegate to
  CalendarAgent.
- When creating shopping lists, group items by category (produce, dairy, etc.).
- For car maintenance, reference manufacturer-recommended intervals.
- For vacation planning, consider budget, duration, and preferences.
- Present plans in clear, structured formats that are easy to follow.
- Proactively suggest items or tasks the user might have forgotten.

Always respond in the language detected from the user input.
Internal reasoning must be in English.
"""


class PersonalAgent(BaseAgent):
    """Personal life specialist for shopping, maintenance, and vacation planning."""

    name: str = "personal"
    domains: list[DomainType] = [DomainType.PERSONAL]
    can_sub_delegate: bool = True

    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        """Handle personal tasks, sub-delegating scheduling to CalendarAgent."""
        task_lower = msg.task_summary.lower()

        # Sub-delegate pure scheduling to CalendarAgent
        scheduling_keywords = (
            "schedule", "calendar", "reminder", "appointment",
        )
        if any(kw in task_lower for kw in scheduling_keywords):
            return await self._sub_delegate(
                target_agent_name="calendar",
                task_summary=msg.task_summary,
                context=msg.context,
                parent_msg=msg,
                state=state,
            )

        # General personal tasks via agentic loop
        tools = [TOOL_WEB_SEARCH, TOOL_KB_SEARCH, TOOL_PLANNING_CREATE]

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=SYSTEM_PROMPT,
            tools=tools,
            max_iterations=8,
            model_tier="standard",
        )
