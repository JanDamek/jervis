"""Issue Tracker Agent -- CRUD operations on issues via Kotlin API.

Manages issues in external trackers (Jira, GitHub Issues, GitLab Issues)
through the Kotlin server RPC bridge. Can sub-delegate to ResearchAgent
for gathering context before creating or updating issues.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH

logger = logging.getLogger(__name__)


TOOL_TRACKER_SEARCH: dict = {
    "type": "function",
    "function": {
        "name": "tracker_search",
        "description": (
            "Search for issues in the project issue tracker (Jira, GitHub Issues, "
            "GitLab Issues). Supports JQL-like queries, free-text search, and "
            "filtering by status, assignee, or labels."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search query -- free text or JQL-like expression.",
                },
                "status": {
                    "type": "string",
                    "description": "Filter by status (e.g. open, in_progress, done).",
                },
                "assignee": {
                    "type": "string",
                    "description": "Filter by assignee username or email.",
                },
                "labels": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Filter by labels/tags.",
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


TOOL_TRACKER_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "tracker_create",
        "description": (
            "Create a new issue in the project issue tracker. "
            "Returns the created issue key/ID and URL."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Issue title/summary.",
                },
                "description": {
                    "type": "string",
                    "description": "Issue description in Markdown.",
                },
                "issue_type": {
                    "type": "string",
                    "enum": ["bug", "task", "story", "epic", "subtask"],
                    "description": "Type of issue to create.",
                },
                "priority": {
                    "type": "string",
                    "enum": ["low", "medium", "high", "critical"],
                    "description": "Issue priority (default medium).",
                    "default": "medium",
                },
                "labels": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Labels/tags to apply.",
                },
                "assignee": {
                    "type": "string",
                    "description": "Assignee username or email.",
                },
                "parent_key": {
                    "type": "string",
                    "description": "Parent issue key for subtasks or stories under epics.",
                },
            },
            "required": ["title", "description", "issue_type"],
        },
    },
}


TOOL_TRACKER_UPDATE: dict = {
    "type": "function",
    "function": {
        "name": "tracker_update",
        "description": (
            "Update an existing issue fields (title, description, labels, "
            "assignee, priority). Does NOT change status -- use tracker_transition."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "issue_key": {
                    "type": "string",
                    "description": "Issue key/ID to update (e.g. PROJ-123).",
                },
                "title": {
                    "type": "string",
                    "description": "New title (omit to keep current).",
                },
                "description": {
                    "type": "string",
                    "description": "New description in Markdown (omit to keep current).",
                },
                "priority": {
                    "type": "string",
                    "enum": ["low", "medium", "high", "critical"],
                    "description": "New priority (omit to keep current).",
                },
                "labels": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "New labels (replaces existing).",
                },
                "assignee": {
                    "type": "string",
                    "description": "New assignee username or email.",
                },
                "comment": {
                    "type": "string",
                    "description": "Add a comment to the issue.",
                },
            },
            "required": ["issue_key"],
        },
    },
}


TOOL_TRACKER_TRANSITION: dict = {
    "type": "function",
    "function": {
        "name": "tracker_transition",
        "description": (
            "Transition an issue to a new status. Validates that the "
            "transition is allowed by the tracker workflow."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "issue_key": {
                    "type": "string",
                    "description": "Issue key/ID to transition (e.g. PROJ-123).",
                },
                "target_status": {
                    "type": "string",
                    "description": "Target status (e.g. in_progress, done, closed).",
                },
                "comment": {
                    "type": "string",
                    "description": "Optional transition comment.",
                },
            },
            "required": ["issue_key", "target_status"],
        },
    },
}


_TRACKER_TOOLS: list[dict] = [
    TOOL_TRACKER_SEARCH,
    TOOL_TRACKER_CREATE,
    TOOL_TRACKER_UPDATE,
    TOOL_TRACKER_TRANSITION,
    TOOL_KB_SEARCH,
]


class IssueTrackerAgent(BaseAgent):
    """Specialist agent for issue tracker CRUD operations.

    Manages the full lifecycle of issues: searching, creating, updating
    fields, and transitioning statuses. Can sub-delegate to ResearchAgent
    to gather context from the KB or codebase before modifying issues.
    """

    name = "issue_tracker"
    description = (
        "Manages issues in external trackers (Jira, GitHub Issues, GitLab). "
        "Can search, create, update, and transition issues. "
        "Sub-delegates to ResearchAgent for gathering context."
    )
    domains = [DomainType.PROJECT_MANAGEMENT, DomainType.CODE]
    tools = _TRACKER_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute issue tracker operations."""
        logger.info(
            "IssueTrackerAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        enriched_context = msg.context
        if self._needs_research(msg):
            research_output = await self._sub_delegate(
                target_agent_name="research",
                task_summary=(
                    "Gather context for issue tracker operation: "
                    f"{msg.task_summary}"
                ),
                context=msg.context,
                parent_msg=msg,
                state=state,
            )
            if research_output.success and research_output.result:
                enriched_context = (
                    f"{msg.context}\n\n"
                    f"--- Research Context ---\n{research_output.result}"
                )

        enriched_msg = msg.model_copy(update={"context": enriched_context})

        system_prompt = (
            "You are the IssueTrackerAgent, a specialist in managing issues in "
            "project trackers (Jira, GitHub Issues, GitLab Issues).\n\n"
            "Your capabilities:\n"
            "- Search for existing issues by text, status, assignee, or labels\n"
            "- Create new issues (bugs, tasks, stories, epics, subtasks)\n"
            "- Update issue fields (title, description, priority, labels, assignee)\n"
            "- Transition issues between statuses\n"
            "- Search the knowledge base for additional project context\n\n"
            "Guidelines:\n"
            "- Always search for existing issues before creating duplicates\n"
            "- Provide clear, actionable descriptions when creating issues\n"
            "- Include relevant labels and set appropriate priority\n"
            "- When transitioning, verify the current status first\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=enriched_msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=10,
        )

    @staticmethod
    def _needs_research(msg: DelegationMessage) -> bool:
        """Heuristic: does this task need background research first?"""
        research_keywords = [
            "analyze", "investigate", "understand", "why",
            "root cause", "impact", "context", "background",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in research_keywords)

