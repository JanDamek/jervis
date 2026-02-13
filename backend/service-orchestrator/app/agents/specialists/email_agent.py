"""Email Agent -- read, compose, send, reply, and search emails.

Handles all email operations through the Kotlin server RPC bridge.
Supports reading, composing, sending, replying to, and searching
emails across configured email accounts.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType

logger = logging.getLogger(__name__)


TOOL_EMAIL_READ: dict = {
    "type": "function",
    "function": {
        "name": "email_read",
        "description": (
            "Read a specific email by its ID. Returns the full email "
            "content including subject, sender, recipients, body, "
            "attachments list, and timestamps."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "email_id": {
                    "type": "string",
                    "description": "Unique email identifier.",
                },
            },
            "required": ["email_id"],
        },
    },
}


TOOL_EMAIL_SEND: dict = {
    "type": "function",
    "function": {
        "name": "email_send",
        "description": (
            "Compose and send an email. Supports plain text and HTML "
            "body, multiple recipients, CC, and BCC."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "to": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "List of recipient email addresses.",
                },
                "subject": {
                    "type": "string",
                    "description": "Email subject line.",
                },
                "body": {
                    "type": "string",
                    "description": "Email body content (plain text or HTML).",
                },
                "cc": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "CC recipient email addresses (optional).",
                },
                "bcc": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "BCC recipient email addresses (optional).",
                },
            },
            "required": ["to", "subject", "body"],
        },
    },
}


TOOL_EMAIL_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "email_search",
        "description": (
            "Search emails by query, sender, date range, or folder. "
            "Returns matching emails with subject, sender, date, and excerpt."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Free-text search query.",
                },
                "from_address": {
                    "type": "string",
                    "description": "Filter by sender email address (optional).",
                },
                "date_from": {
                    "type": "string",
                    "description": "Start date filter in ISO 8601 format (optional).",
                },
                "date_to": {
                    "type": "string",
                    "description": "End date filter in ISO 8601 format (optional).",
                },
                "folder": {
                    "type": "string",
                    "description": "Email folder to search (e.g. inbox, sent, drafts).",
                    "default": "inbox",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results (default 20).",
                    "default": 20,
                },
            },
            "required": ["query"],
        },
    },
}


TOOL_EMAIL_REPLY: dict = {
    "type": "function",
    "function": {
        "name": "email_reply",
        "description": (
            "Reply to an existing email. Supports reply and reply-all. "
            "Automatically includes the original message in the reply thread."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "email_id": {
                    "type": "string",
                    "description": "ID of the email to reply to.",
                },
                "body": {
                    "type": "string",
                    "description": "Reply body content.",
                },
                "reply_all": {
                    "type": "boolean",
                    "description": "If true, reply to all recipients (default false).",
                    "default": False,
                },
            },
            "required": ["email_id", "body"],
        },
    },
}


TOOL_EMAIL_LIST: dict = {
    "type": "function",
    "function": {
        "name": "email_list",
        "description": (
            "List recent emails with optional filtering by folder, "
            "read/unread status, and importance. Returns email summaries "
            "with subject, sender, date, and read status."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "folder": {
                    "type": "string",
                    "description": "Email folder (e.g. inbox, sent, drafts). Default inbox.",
                    "default": "inbox",
                },
                "unread_only": {
                    "type": "boolean",
                    "description": "Only return unread emails (default false).",
                    "default": False,
                },
                "important_only": {
                    "type": "boolean",
                    "description": "Only return important/flagged emails (default false).",
                    "default": False,
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of emails to return (default 20).",
                    "default": 20,
                },
            },
            "required": [],
        },
    },
}


_EMAIL_TOOLS: list[dict] = [
    TOOL_EMAIL_READ,
    TOOL_EMAIL_SEND,
    TOOL_EMAIL_SEARCH,
    TOOL_EMAIL_REPLY,
    TOOL_EMAIL_LIST,
]


class EmailAgent(BaseAgent):
    """Specialist agent for email operations.

    Handles reading, composing, sending, replying to, and searching
    emails. Does not sub-delegate to other agents -- all email
    operations are handled directly.
    """

    name = "email"
    description = (
        "Handles all email operations: reading, composing, sending, "
        "replying, and searching emails across configured accounts."
    )
    domains = [DomainType.COMMUNICATION]
    tools = _EMAIL_TOOLS
    can_sub_delegate = False

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute email operations.

        Uses the agentic loop with email tools. Does not sub-delegate --
        all email operations are handled directly.
        """
        logger.info(
            "EmailAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        system_prompt = (
            "You are the EmailAgent, a specialist in email operations.\n\n"
            "Your capabilities:\n"
            "- Read emails by ID to get full content and metadata\n"
            "- Compose and send new emails with recipients, CC, and BCC\n"
            "- Search emails by query, sender, date range, or folder\n"
            "- Reply to existing emails (reply or reply-all)\n"
            "- List recent emails with filters (folder, read status, importance)\n\n"
            "Guidelines:\n"
            "- Always confirm recipients and subject before sending\n"
            "- Use professional tone unless instructed otherwise\n"
            "- Include relevant context when replying to threads\n"
            "- Search before composing to check for existing conversations\n"
            "- Never expose sensitive information in email content\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=8,
        )
