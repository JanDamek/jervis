"""Project Management Agent -- sprint planning, epic decomposition, issue management.

Handles high-level project management operations including sprint planning,
epic creation and decomposition, issue prioritization, and workload
analysis. Sub-delegates to IssueTrackerAgent for actual tracker operations.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import TOOL_KB_SEARCH

logger = logging.getLogger(__name__)


TOOL_PM_LIST_ISSUES: dict = {
    "type": "function",
    "function": {
        "name": "pm_list_issues",
        "description": (
            "List issues for a project with filtering and sorting options. "
            "Returns issues with status, priority, assignee, and sprint info. "
            "Use for backlog review and sprint planning."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "project_key": {
                    "type": "string",
                    "description": "Project key to list issues for.",
                },
                "status": {
                    "type": "string",
                    "description": "Filter by status (e.g. open, in_progress, done).",
                },
                "sprint": {
                    "type": "string",
                    "description": "Filter by sprint name or ID (e.g. current, next, Sprint 42).",
                },
                "sort_by": {
                    "type": "string",
                    "enum": ["priority", "created", "updated", "status"],
                    "description": "Sort field (default priority).",
                    "default": "priority",
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results (default 50).",
                    "default": 50,
                },
            },
            "required": ["project_key"],
        },
    },
}


TOOL_PM_CREATE_EPIC: dict = {
    "type": "function",
    "function": {
        "name": "pm_create_epic",
        "description": (
            "Create an epic with decomposed child stories/tasks. Automatically "
            "breaks down the epic description into actionable sub-issues."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Epic title.",
                },
                "description": {
                    "type": "string",
                    "description": "Epic description with acceptance criteria.",
                },
                "project_key": {
                    "type": "string",
                    "description": "Project key to create the epic in.",
                },
                "labels": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Labels/tags for the epic.",
                },
                "decompose": {
                    "type": "boolean",
                    "description": "Auto-decompose into child stories (default true).",
                    "default": true,
                },
            },
            "required": ["title", "description", "project_key"],
        },
    },
}


TOOL_PM_PLAN_SPRINT: dict = {
    "type": "function",
    "function": {
        "name": "pm_plan_sprint",
        "description": (
            "Plan a sprint by selecting issues from the backlog, estimating "
            "capacity, and assigning work. Returns a proposed sprint plan."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "project_key": {
                    "type": "string",
                    "description": "Project key for sprint planning.",
                },
                "sprint_name": {
                    "type": "string",
                    "description": "Name for the sprint (e.g. Sprint 43).",
                },
                "duration_days": {
                    "type": "integer",
                    "description": "Sprint duration in days (default 14).",
                    "default": 14,
                },
                "team_capacity": {
                    "type": "integer",
                    "description": "Team capacity in story points (optional).",
                },
                "priority_labels": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Priority labels to focus on (optional).",
                },
            },
            "required": ["project_key", "sprint_name"],
        },
    },
}


_PM_TOOLS: list[dict] = [
    TOOL_KB_SEARCH,
    TOOL_PM_LIST_ISSUES,
    TOOL_PM_CREATE_EPIC,
    TOOL_PM_PLAN_SPRINT,
]


class ProjectManagementAgent(BaseAgent):
    """Specialist agent for project management operations.

    Handles sprint planning, epic creation and decomposition, backlog
    management, and workload analysis. Sub-delegates to IssueTrackerAgent
    for direct issue tracker CRUD operations.
    """

    name = "project_management"
    description = (
        "Handles sprint planning, epic decomposition, backlog management, "
        "and workload analysis. Sub-delegates to IssueTrackerAgent for "
        "direct tracker operations."
    )
    domains = [DomainType.PROJECT_MANAGEMENT]
    tools = _PM_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute project management operations.

        Strategy:
        1. For complex tracker operations, sub-delegate to IssueTrackerAgent.
        2. Run agentic loop with PM tools for planning and analysis.
        """
        logger.info(
            "ProjectManagementAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        # If task is primarily about individual issue CRUD, delegate down
        if self._is_tracker_task(msg):
            tracker_output = await self._sub_delegate(
                target_agent_name="issue_tracker",
                task_summary=msg.task_summary,
                context=msg.context,
                parent_msg=msg,
                state=state,
            )
            if tracker_output.success:
                return tracker_output

        system_prompt = (
            "You are the ProjectManagementAgent, a specialist in agile project "
            "management and sprint planning.\n\n"
            "Your capabilities:\n"
            "- List and analyze project backlog issues\n"
            "- Create epics with automatic decomposition into stories\n"
            "- Plan sprints with capacity estimation\n"
            "- Search the knowledge base for project context\n\n"
            "Guidelines:\n"
            "- Review the current backlog before planning sprints\n"
            "- Prioritize based on business value and dependencies\n"
            "- Break epics into manageable, well-defined stories\n"
            "- Consider team capacity when planning sprints\n"
            "- Include acceptance criteria in epic descriptions\n"
            "- Balance feature work with technical debt\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=12,
        )

    @staticmethod
    def _is_tracker_task(msg: DelegationMessage) -> bool:
        """Heuristic: is this a simple tracker CRUD task?"""
        tracker_keywords = [
            "create issue", "update issue", "close issue",
            "transition", "assign", "move to",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in tracker_keywords)

