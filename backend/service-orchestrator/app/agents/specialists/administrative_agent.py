"""AdministrativeAgent -- Travel planning, logistics, and organisation.

Handles administrative tasks such as travel arrangements, document
management, logistics coordination, and general organisational duties.
Sub-delegates to CalendarAgent for scheduling-related sub-tasks.
"""

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH, TOOL_WEB_SEARCH

SYSTEM_PROMPT = """\
You are the AdministrativeAgent -- a specialist for administrative and
organisational tasks within the Jervis assistant.

Your capabilities:
1. Plan and coordinate travel (flights, hotels, transport, itineraries).
2. Manage logistics for events, meetings, and projects.
3. Organise documents, templates, and administrative workflows.
4. Research venues, services, and providers via web search.
5. Look up internal knowledge base for policies, contacts, and procedures.

Guidelines:
- For any task that requires scheduling or calendar entries, sub-delegate
  to CalendarAgent.
- Always provide cost estimates when planning travel or events.
- Present options in a structured comparison format.
- Verify information from multiple sources when planning logistics.
- Track deadlines and deliverables proactively.

Always respond in the language detected from the user input.
Internal reasoning must be in English.
"""


class AdministrativeAgent(BaseAgent):
    """Administrative specialist for travel, logistics, and organisation."""

    name: str = "administrative"
    domains: list[DomainType] = [DomainType.ADMINISTRATIVE]
    can_sub_delegate: bool = True

    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        """Analyse the administrative task and handle it or sub-delegate.

        Scheduling sub-tasks are routed to CalendarAgent. Everything else
        is handled directly via web search and KB lookup.
        """
        task_lower = msg.task_summary.lower()

        # Sub-delegate pure scheduling tasks to CalendarAgent
        scheduling_keywords = (
            "schedule", "calendar", "meeting", "appointment",
            "reminder",
        )
        if any(kw in task_lower for kw in scheduling_keywords):
            return await self._sub_delegate(
                target_agent_name="calendar",
                task_summary=msg.task_summary,
                context=msg.context,
                parent_msg=msg,
                state=state,
            )

        # General administrative tasks via agentic loop
        tools = [TOOL_WEB_SEARCH, TOOL_KB_SEARCH]

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=SYSTEM_PROMPT,
            tools=tools,
            max_iterations=8,
            model_tier="standard",
        )
