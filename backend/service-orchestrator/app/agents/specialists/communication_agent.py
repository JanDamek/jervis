"""Communication Agent -- central hub for all communication channels.

Routes communication tasks to the appropriate specialist (email, Teams,
Slack, Discord) and handles cross-platform messaging, document drafting,
and communication coordination.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH

logger = logging.getLogger(__name__)


TOOL_MESSAGE_SEND: dict = {
    "type": "function",
    "function": {
        "name": "message_send",
        "description": (
            "Send a message via a specific communication platform. "
            "Supports email, Teams, Slack, and Discord. For email, "
            "prefer sub-delegating to EmailAgent for full capabilities."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "platform": {
                    "type": "string",
                    "enum": ["email", "teams", "slack", "discord"],
                    "description": "Communication platform to use.",
                },
                "recipient": {
                    "type": "string",
                    "description": "Recipient identifier (email, channel name, user ID).",
                },
                "message": {
                    "type": "string",
                    "description": "Message content to send.",
                },
                "subject": {
                    "type": "string",
                    "description": "Subject line (required for email, optional for others).",
                },
                "thread_id": {
                    "type": "string",
                    "description": "Thread/conversation ID to reply in (optional).",
                },
            },
            "required": ["platform", "recipient", "message"],
        },
    },
}


TOOL_DRAFT_DOCUMENT: dict = {
    "type": "function",
    "function": {
        "name": "draft_document",
        "description": (
            "Create a document draft using a template. Supports various "
            "document types: email, report, meeting summary, proposal, "
            "status update. Returns the drafted content for review."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "template": {
                    "type": "string",
                    "enum": [
                        "email", "report", "meeting_summary",
                        "proposal", "status_update", "announcement",
                    ],
                    "description": "Document template type.",
                },
                "title": {
                    "type": "string",
                    "description": "Document title or subject.",
                },
                "content_brief": {
                    "type": "string",
                    "description": "Brief description of what the document should contain.",
                },
                "audience": {
                    "type": "string",
                    "description": "Target audience (e.g. team, management, client).",
                },
                "tone": {
                    "type": "string",
                    "enum": ["formal", "professional", "casual", "technical"],
                    "description": "Desired writing tone (default professional).",
                    "default": "professional",
                },
            },
            "required": ["template", "title", "content_brief"],
        },
    },
}


_COMMUNICATION_TOOLS: list[dict] = [
    TOOL_MESSAGE_SEND,
    TOOL_DRAFT_DOCUMENT,
    TOOL_KB_SEARCH,
]


class CommunicationAgent(BaseAgent):
    """Central hub agent for all communication channels.

    Routes communication tasks to the appropriate specialist agent
    (e.g. EmailAgent for email operations). Handles cross-platform
    messaging, document drafting, and communication coordination.
    Sub-delegates to EmailAgent for email-specific tasks.
    """

    name = "communication"
    description = (
        "Central hub for all communication: email, Teams, Slack, Discord. "
        "Drafts documents, sends messages across platforms. "
        "Sub-delegates to EmailAgent for email operations."
    )
    domains = [DomainType.COMMUNICATION]
    tools = _COMMUNICATION_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute communication operations.

        Strategy:
        1. If the task is email-specific, sub-delegate to EmailAgent.
        2. Otherwise, run the agentic loop for cross-platform messaging
           and document drafting.
        """
        logger.info(
            "CommunicationAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        # Sub-delegate email tasks to the specialist
        if self._is_email_task(msg):
            email_output = await self._sub_delegate(
                target_agent_name="email",
                task_summary=msg.task_summary,
                context=msg.context,
                parent_msg=msg,
                state=state,
            )
            if email_output.success:
                return email_output

        system_prompt = (
            "You are the CommunicationAgent, the central hub for all "
            "communication across platforms.\n\n"
            "Your capabilities:\n"
            "- Send messages via Teams, Slack, Discord, and email\n"
            "- Draft documents from templates (reports, proposals, summaries)\n"
            "- Search the knowledge base for context and contacts\n\n"
            "Guidelines:\n"
            "- Choose the appropriate platform for each message\n"
            "- Use professional tone unless instructed otherwise\n"
            "- Draft documents before sending for complex communications\n"
            "- Search KB for relevant context before composing messages\n"
            "- Coordinate multi-platform communications efficiently\n"
            "- Never expose sensitive information in messages\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=10,
        )

    @staticmethod
    def _is_email_task(msg: DelegationMessage) -> bool:
        """Heuristic: is this task specifically about email?"""
        email_keywords = [
            "email", "e-mail", "mail", "inbox", "send email",
            "reply email", "compose email", "read email",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in email_keywords)
