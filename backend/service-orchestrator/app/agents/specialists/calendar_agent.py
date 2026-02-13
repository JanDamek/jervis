"""CalendarAgent -- Calendar management specialist.

Handles scheduling, reminders, deadlines, availability checking, and
recurring event management through the Kotlin backend calendar API.
"""

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType

# ---------------------------------------------------------------------------
# Inline tool definitions
# ---------------------------------------------------------------------------

TOOL_CALENDAR_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "calendar_search",
        "description": "Search calendar events by date range, title, attendees, or location.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Free-text search for event titles/descriptions."},
                "date_from": {"type": "string", "description": "ISO-8601 start of search range."},
                "date_to": {"type": "string", "description": "ISO-8601 end of search range."},
                "attendee": {"type": "string", "description": "Filter by attendee email or name."},
                "calendar_id": {"type": "string", "description": "Specific calendar to search."},
                "include_recurring": {"type": "boolean", "description": "Include recurring instances.", "default": True},
                "limit": {"type": "integer", "description": "Max events to return.", "default": 50},
            },
            "required": [],
        },
    },
}

TOOL_CALENDAR_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "calendar_create",
        "description": "Create a new calendar event with optional attendees, reminders, and recurrence.",
        "parameters": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Event title."},
                "start": {"type": "string", "description": "ISO-8601 start datetime."},
                "end": {"type": "string", "description": "ISO-8601 end datetime."},
                "all_day": {"type": "boolean", "description": "Whether this is an all-day event.", "default": False},
                "location": {"type": "string", "description": "Event location (physical or virtual link)."},
                "description": {"type": "string", "description": "Event description or agenda."},
                "attendees": {"type": "array", "items": {"type": "string"}, "description": "Attendee email addresses."},
                "reminders": {
                    "type": "array",
                    "items": {"type": "object", "properties": {"minutes_before": {"type": "integer"}, "method": {"type": "string", "enum": ["popup", "email"]}}},
                    "description": "Reminder settings.",
                },
                "recurrence": {"type": "string", "description": "RRULE string for recurring events."},
                "calendar_id": {"type": "string", "description": "Target calendar ID."},
            },
            "required": ["title", "start", "end"],
        },
    },
}

TOOL_CALENDAR_UPDATE: dict = {
    "type": "function",
    "function": {
        "name": "calendar_update",
        "description": "Update an existing calendar event. Only provided fields are modified.",
        "parameters": {
            "type": "object",
            "properties": {
                "event_id": {"type": "string", "description": "ID of the event to update."},
                "title": {"type": "string", "description": "New event title."},
                "start": {"type": "string", "description": "New ISO-8601 start datetime."},
                "end": {"type": "string", "description": "New ISO-8601 end datetime."},
                "location": {"type": "string", "description": "Updated location."},
                "description": {"type": "string", "description": "Updated description."},
                "attendees": {"type": "array", "items": {"type": "string"}, "description": "Updated attendee list."},
                "status": {"type": "string", "enum": ["confirmed", "tentative", "cancelled"], "description": "Event status."},
                "update_scope": {"type": "string", "enum": ["this_event", "this_and_following", "all_events"], "description": "Scope for recurring events.", "default": "this_event"},
            },
            "required": ["event_id"],
        },
    },
}

SYSTEM_PROMPT = """\
You are the CalendarAgent -- a specialist for calendar management within the
Jervis assistant.

Your capabilities:
1. Search and list calendar events across date ranges.
2. Create new events with attendees, reminders, and recurrence.
3. Update or reschedule existing events.
4. Check availability and suggest optimal meeting times.

Guidelines:
- Always verify there are no conflicts before creating an event.
- When scheduling meetings with attendees, check availability first.
- For recurring events, confirm the recurrence pattern with the user.
- Use ISO-8601 format for all dates and times.
- Respect timezone context from the user profile or message.

Always respond in the language detected from the user input.
Internal reasoning must be in English.
"""


class CalendarAgent(BaseAgent):
    """Calendar management specialist for scheduling, reminders, and deadlines."""

    name: str = "calendar"
    domains: list[DomainType] = [DomainType.ADMINISTRATIVE, DomainType.PERSONAL]
    can_sub_delegate: bool = False

    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        """Run the calendar-specific agentic loop."""
        tools = [
            TOOL_CALENDAR_SEARCH,
            TOOL_CALENDAR_CREATE,
            TOOL_CALENDAR_UPDATE,
        ]

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=SYSTEM_PROMPT,
            tools=tools,
            max_iterations=6,
            model_tier="standard",
        )
