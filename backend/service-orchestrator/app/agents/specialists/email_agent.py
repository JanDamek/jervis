"""EmailAgent -- Handles all email operations.

Provides read, compose, reply, search, and send capabilities by calling
the Kotlin backend API through dedicated tool definitions.
"""

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType

# ---------------------------------------------------------------------------
# Inline tool definitions -- each maps to a Kotlin backend API endpoint
# ---------------------------------------------------------------------------

TOOL_EMAIL_READ: dict = {
    "type": "function",
    "function": {
        "name": "email_read",
        "description": (
            "Read emails from the inbox. Can filter by sender, "
            "subject, date range, or read/unread status."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "folder": {"type": "string", "description": "Mailbox folder.", "default": "inbox"},
                "sender": {"type": "string", "description": "Filter by sender email or name."},
                "subject_contains": {"type": "string", "description": "Filter by substring in subject."},
                "unread_only": {"type": "boolean", "description": "Return only unread messages.", "default": False},
                "limit": {"type": "integer", "description": "Max emails to return.", "default": 20},
                "since": {"type": "string", "description": "ISO-8601 date -- only emails after this date."},
            },
            "required": [],
        },
    },
}

TOOL_EMAIL_COMPOSE: dict = {
    "type": "function",
    "function": {
        "name": "email_compose",
        "description": "Compose a new email draft. Returns the draft for review before sending.",
        "parameters": {
            "type": "object",
            "properties": {
                "to": {"type": "array", "items": {"type": "string"}, "description": "Recipient email addresses."},
                "cc": {"type": "array", "items": {"type": "string"}, "description": "CC recipients."},
                "bcc": {"type": "array", "items": {"type": "string"}, "description": "BCC recipients."},
                "subject": {"type": "string", "description": "Email subject line."},
                "body": {"type": "string", "description": "Email body content (plain text or HTML)."},
                "reply_to_message_id": {"type": "string", "description": "Message ID to reply to."},
                "is_html": {"type": "boolean", "description": "Whether the body is HTML.", "default": False},
            },
            "required": ["to", "subject", "body"],
        },
    },
}

TOOL_EMAIL_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "email_search",
        "description": "Search across all email folders using full-text search.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search query string."},
                "folder": {"type": "string", "description": "Limit search to a specific folder."},
                "from_address": {"type": "string", "description": "Filter by sender address."},
                "date_from": {"type": "string", "description": "ISO-8601 start date."},
                "date_to": {"type": "string", "description": "ISO-8601 end date."},
                "has_attachment": {"type": "boolean", "description": "Filter for emails with attachments."},
                "limit": {"type": "integer", "description": "Maximum results.", "default": 20},
            },
            "required": ["query"],
        },
    },
}

TOOL_EMAIL_SEND: dict = {
    "type": "function",
    "function": {
        "name": "email_send",
        "description": "Send a previously composed email draft.",
        "parameters": {
            "type": "object",
            "properties": {
                "draft_id": {"type": "string", "description": "ID of the draft to send."},
                "send_at": {"type": "string", "description": "Optional ISO-8601 datetime for scheduled send."},
            },
            "required": ["draft_id"],
        },
    },
}

SYSTEM_PROMPT = """\
You are the EmailAgent -- a specialist for all email operations within the
Jervis assistant.

Your capabilities:
1. Read and list emails from any folder with filtering.
2. Compose new emails and replies with proper formatting.
3. Search across the entire mailbox using full-text search.
4. Send composed drafts (always compose first, then send).

Guidelines:
- Always compose a draft before sending -- never send without user review
  unless explicitly instructed to do so.
- When replying, use reply_to_message_id to maintain threading.
- Format HTML emails when the content benefits from rich formatting.
- Summarise long email threads concisely when asked.

Always respond in the language detected from the user input.
Internal reasoning must be in English.
"""


class EmailAgent(BaseAgent):
    """Email specialist handling read, compose, reply, search, and send."""

    name: str = "email"
    domains: list[DomainType] = [DomainType.COMMUNICATION]
    can_sub_delegate: bool = False

    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        """Run the email-specific agentic loop with all email tools."""
        tools = [
            TOOL_EMAIL_READ,
            TOOL_EMAIL_COMPOSE,
            TOOL_EMAIL_SEARCH,
            TOOL_EMAIL_SEND,
        ]

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=SYSTEM_PROMPT,
            tools=tools,
            max_iterations=8,
            model_tier="standard",
        )
