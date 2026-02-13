"""CommunicationAgent -- Central hub for all communication platforms.

Routes tasks to platform-specific sub-agents (EmailAgent, future TeamsAgent,
SlackAgent, DiscordAgent) or composes messages directly when no sub-delegation
is needed.
"""

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH


# ---------------------------------------------------------------------------
# Inline tool definitions
# ---------------------------------------------------------------------------

TOOL_COMPOSE_MESSAGE: dict = {
    "type": "function",
    "function": {
        "name": "compose_message",
        "description": (
            "Compose a structured message for any communication channel. "
            "Returns a draft that can be reviewed before sending."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "channel": {
                    "type": "string",
                    "enum": ["email", "teams", "slack", "discord", "generic"],
                    "description": "Target communication channel.",
                },
                "recipient": {
                    "type": "string",
                    "description": "Recipient identifier (email, username, channel name).",
                },
                "subject": {
                    "type": "string",
                    "description": "Message subject or thread title.",
                },
                "body": {
                    "type": "string",
                    "description": "Message body content.",
                },
                "tone": {
                    "type": "string",
                    "enum": ["formal", "casual", "neutral"],
                    "description": "Desired tone of the message.",
                    "default": "neutral",
                },
            },
            "required": ["channel", "recipient", "body"],
        },
    },
}

TOOL_GENERATE_REPORT: dict = {
    "type": "function",
    "function": {
        "name": "generate_report",
        "description": (
            "Generate a formatted communication report or summary from "
            "multiple conversations, threads, or message histories."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "report_type": {
                    "type": "string",
                    "enum": ["summary", "action_items", "timeline", "full"],
                    "description": "Type of report to generate.",
                },
                "source_channels": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Channels to include in the report.",
                },
                "time_range": {
                    "type": "string",
                    "description": "Time range for the report.",
                },
                "filters": {
                    "type": "object",
                    "description": "Optional filters (sender, keywords, etc.).",
                },
            },
            "required": ["report_type"],
        },
    },
}

SYSTEM_PROMPT = """\
You are the CommunicationAgent -- the central hub for ALL communication tasks
within the Jervis assistant.

Your responsibilities:
1. Analyse incoming communication requests and determine the correct platform.
2. For email tasks, sub-delegate to EmailAgent.
3. For other platforms (Teams, Slack, Discord), sub-delegate to the appropriate
   agent when available, or compose a draft message directly.
4. Use KB search to find contact information, templates, and communication
   history when relevant.
5. Generate reports summarising communication across channels when requested.

Always respond in the language detected from the user input.
Internal reasoning must be in English.
"""


class CommunicationAgent(BaseAgent):
    """Central communication hub that routes to platform-specific sub-agents."""

    name: str = "communication"
    domains: list[DomainType] = [DomainType.COMMUNICATION]
    can_sub_delegate: bool = True

    async def execute(self, msg: DelegationMessage, state: dict) -> AgentOutput:
        """Analyse the communication task and route appropriately.

        For email-specific tasks the agent sub-delegates to EmailAgent.
        For cross-channel reports or generic composition it uses its own
        agentic loop with compose/report tools.
        """
        task_lower = msg.task_summary.lower()

        # Direct sub-delegation for clearly email-scoped tasks
        if any(kw in task_lower for kw in ("email", "e-mail", "mail", "inbox")):
            return await self._sub_delegate(
                target_agent_name="email",
                task_summary=msg.task_summary,
                context=msg.context,
                parent_msg=msg,
                state=state,
            )

        # For everything else, run the agentic loop with communication tools
        tools = [
            TOOL_KB_SEARCH,
            TOOL_COMPOSE_MESSAGE,
            TOOL_GENERATE_REPORT,
        ]

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=SYSTEM_PROMPT,
            tools=tools,
            max_iterations=6,
            model_tier="standard",
        )
