"""Calendar Agent -- calendar events, scheduling, and reminders.

Manages calendar events, meeting scheduling, deadline tracking,
and reminders through the Kotlin server RPC bridge. Integrates
with external calendar systems (Google Calendar, Outlook, etc.).
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType

logger = logging.getLogger(__name__)


TOOL_CALENDAR_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "calendar_search",
        "description": (
            "Search calendar events by date range, query text, or attendees. "
            "Returns matching events with title, time, duration, location, "
            "and attendee list."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Free-text search query for event titles or descriptions.",
                },
                "date_from": {
                    "type": "string",
                    "description": "Start of date range in ISO 8601 format (e.g. 2025-01-15).",
                },
                "date_to": {
                    "type": "string",
                    "description": "End of date range in ISO 8601 format.",
                },
                "attendee": {
                    "type": "string",
                    "description": "Filter by attendee email address (optional).",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results (default 20).",
                    "default": 20,
                },
            },
            "required": [],
        },
    },
}


TOOL_CALENDAR_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "calendar_create",
        "description": (
            "Create a new calendar event. Supports setting title, date, "
            "time, duration, location, description, and inviting attendees."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Event title.",
                },
                "date": {
                    "type": "string",
                    "description": "Event date in ISO 8601 format (e.g. 2025-01-15).",
                },
                "time": {
                    "type": "string",
                    "description": "Event start time (e.g. 14:00, 2:00 PM).",
                },
                "duration_minutes": {
                    "type": "integer",
                    "description": "Event duration in minutes (default 60).",
                    "default": 60,
                },
                "location": {
                    "type": "string",
                    "description": "Event location or meeting link (optional).",
                },
                "description": {
                    "type": "string",
                    "description": "Event description or agenda (optional).",
                },
                "attendees": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "List of attendee email addresses (optional).",
                },
                "recurrence": {
                    "type": "string",
                    "enum": ["none", "daily", "weekly", "biweekly", "monthly"],
                    "description": "Recurrence pattern (default none).",
                    "default": "none",
                },
            },
            "required": ["title", "date", "time"],
        },
    },
}


TOOL_CALENDAR_UPDATE: dict = {
    "type": "function",
    "function": {
        "name": "calendar_update",
        "description": (
            "Update an existing calendar event. Supports changing title, "
            "time, duration, location, description, and attendees."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "event_id": {
                    "type": "string",
                    "description": "Unique event identifier.",
                },
                "title": {
                    "type": "string",
                    "description": "New title (omit to keep current).",
                },
                "date": {
                    "type": "string",
                    "description": "New date in ISO 8601 format (omit to keep current).",
                },
                "time": {
                    "type": "string",
                    "description": "New start time (omit to keep current).",
                },
                "duration_minutes": {
                    "type": "integer",
                    "description": "New duration in minutes (omit to keep current).",
                },
                "location": {
                    "type": "string",
                    "description": "New location (omit to keep current).",
                },
                "attendees": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Updated attendee list (replaces existing).",
                },
                "cancel": {
                    "type": "boolean",
                    "description": "If true, cancel the event (default false).",
                    "default": False,
                },
            },
            "required": ["event_id"],
        },
    },
}


TOOL_REMINDER_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "reminder_create",
        "description": (
            "Create a reminder for a specific date and time. "
            "Supports one-time and recurring reminders with "
            "customizable notification settings."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Reminder title/description.",
                },
                "date": {
                    "type": "string",
                    "description": "Reminder date in ISO 8601 format.",
                },
                "time": {
                    "type": "string",
                    "description": "Reminder time (e.g. 09:00).",
                },
                "recurrence": {
                    "type": "string",
                    "enum": ["none", "daily", "weekly", "monthly"],
                    "description": "Recurrence pattern (default none).",
                    "default": "none",
                },
                "priority": {
                    "type": "string",
                    "enum": ["low", "medium", "high"],
                    "description": "Reminder priority (default medium).",
                    "default": "medium",
                },
            },
            "required": ["title", "date", "time"],
        },
    },
}


_CALENDAR_TOOLS: list[dict] = [
    TOOL_CALENDAR_SEARCH,
    TOOL_CALENDAR_CREATE,
    TOOL_CALENDAR_UPDATE,
    TOOL_REMINDER_CREATE,
]


class CalendarAgent(BaseAgent):
    """Specialist agent for calendar and scheduling operations.

    Manages calendar events, meeting scheduling, deadline tracking,
    and reminders. Does not sub-delegate to other agents -- all
    calendar operations are handled directly.
    """

    name = "calendar"
    description = (
        "Manages calendar events, scheduling, and reminders. "
        "Can search, create, and update events, and set reminders."
    )
    domains = [DomainType.ADMINISTRATIVE]
    tools = _CALENDAR_TOOLS
    can_sub_delegate = False

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute calendar operations.

        Uses the agentic loop with calendar tools. Does not
        sub-delegate -- all operations are handled directly.
        """
        logger.info(
            "CalendarAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        system_prompt = (
            "You are the CalendarAgent, a specialist in calendar management "
            "and scheduling.\n\n"
            "Your capabilities:\n"
            "- Search calendar events by date range, query, or attendees\n"
            "- Create new events with attendees, location, and recurrence\n"
            "- Update or cancel existing events\n"
            "- Create one-time or recurring reminders\n\n"
            "Guidelines:\n"
            "- Check for scheduling conflicts before creating events\n"
            "- Include clear titles and descriptions for all events\n"
            "- Set appropriate duration based on event type\n"
            "- Confirm attendee availability when scheduling meetings\n"
            "- Use ISO 8601 date format for consistency\n"
            "- Consider timezone differences for remote attendees\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=8,
        )
