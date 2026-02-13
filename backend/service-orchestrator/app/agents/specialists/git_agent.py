"""Git Agent -- git operations (commit, push, branch, PR).

Manages all git workflow operations including committing changes,
pushing branches, creating branches, and checkout. Can sub-delegate
to CodingAgent for resolving merge conflicts.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import (
    TOOL_GIT_STATUS,
    TOOL_GIT_LOG,
    TOOL_GIT_DIFF,
    TOOL_GIT_SHOW,
    TOOL_GIT_BLAME,
)

logger = logging.getLogger(__name__)


TOOL_GIT_COMMIT: dict = {
    "type": "function",
    "function": {
        "name": "git_commit",
        "description": (
            "Stage and commit changes in the workspace. Can stage specific files "
            "or all modified files. Uses the project's commit message conventions."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "message": {
                    "type": "string",
                    "description": "Commit message following project conventions.",
                },
                "files": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": (
                        "Specific files to stage and commit. "
                        "If empty, stages all modified files."
                    ),
                },
                "amend": {
                    "type": "boolean",
                    "description": "Amend the previous commit instead of creating new one (default false).",
                    "default": False,
                },
            },
            "required": ["message"],
        },
    },
}


TOOL_GIT_PUSH: dict = {
    "type": "function",
    "function": {
        "name": "git_push",
        "description": (
            "Push committed changes to the remote repository. "
            "Can push to a specific remote and branch. Supports force-push "
            "for amended commits (use with caution)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "remote": {
                    "type": "string",
                    "description": "Remote name (default 'origin').",
                    "default": "origin",
                },
                "branch": {
                    "type": "string",
                    "description": "Branch to push (default: current branch).",
                },
                "force": {
                    "type": "boolean",
                    "description": "Force push (default false, use only after amend).",
                    "default": False,
                },
                "set_upstream": {
                    "type": "boolean",
                    "description": "Set upstream tracking (default true for new branches).",
                    "default": True,
                },
            },
            "required": [],
        },
    },
}


TOOL_GIT_BRANCH_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "git_branch_create",
        "description": (
            "Create a new git branch. Can branch from current HEAD or a "
            "specified base branch/commit."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "branch_name": {
                    "type": "string",
                    "description": "Name for the new branch (e.g. task/PROJ-123).",
                },
                "base": {
                    "type": "string",
                    "description": "Base branch or commit to branch from (default: current HEAD).",
                },
                "checkout": {
                    "type": "boolean",
                    "description": "Checkout the new branch after creation (default true).",
                    "default": True,
                },
            },
            "required": ["branch_name"],
        },
    },
}


TOOL_GIT_CHECKOUT: dict = {
    "type": "function",
    "function": {
        "name": "git_checkout",
        "description": (
            "Checkout an existing branch or commit. Use this to switch "
            "between branches or restore files from specific commits."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "target": {
                    "type": "string",
                    "description": "Branch name, tag, or commit hash to checkout.",
                },
                "file_path": {
                    "type": "string",
                    "description": "Specific file to checkout from target (optional).",
                },
            },
            "required": ["target"],
        },
    },
}


_GIT_TOOLS: list[dict] = [
    TOOL_GIT_STATUS,
    TOOL_GIT_LOG,
    TOOL_GIT_DIFF,
    TOOL_GIT_SHOW,
    TOOL_GIT_BLAME,
    TOOL_GIT_COMMIT,
    TOOL_GIT_PUSH,
    TOOL_GIT_BRANCH_CREATE,
    TOOL_GIT_CHECKOUT,
]


class GitAgent(BaseAgent):
    """Specialist agent for git operations.

    Manages git workflow: branching, committing, pushing, and inspecting
    history. Can sub-delegate to CodingAgent for resolving merge conflicts
    that require code changes.
    """

    name = "git"
    description = (
        "Manages all git operations: commit, push, branch, checkout, diff, "
        "log, blame. Can sub-delegate to CodingAgent for merge conflict "
        "resolution requiring code changes."
    )
    domains = [DomainType.CODE]
    tools = _GIT_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute git operations.

        If merge conflicts are detected, sub-delegates to CodingAgent
        for conflict resolution. Otherwise handles git operations directly.
        """
        logger.info(
            "GitAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        enriched_context = msg.context
        if self._needs_coding_help(msg):
            coding_output = await self._sub_delegate(
                target_agent_name="coding",
                task_summary=(
                    "Resolve merge conflicts in the workspace: "
                    f"{msg.task_summary}"
                ),
                context=msg.context,
                parent_msg=msg,
                state=state,
            )
            if coding_output.success and coding_output.result:
                enriched_context = (
                    f"{msg.context}\n\n"
                    f"--- Conflict Resolution Result ---\n{coding_output.result}"
                )

        enriched_msg = msg.model_copy(update={"context": enriched_context})

        system_prompt = (
            "You are the GitAgent, managing all git operations for the "
            "project workspace.\n\n"
            "Your capabilities:\n"
            "- Check repository status (modified files, staged changes, current branch)\n"
            "- View commit history and diffs\n"
            "- Show commit details and file blame information\n"
            "- Create and checkout branches\n"
            "- Stage and commit changes with proper messages\n"
            "- Push changes to remote repositories\n\n"
            "Guidelines:\n"
            "- Always check git_status before committing to understand what changed\n"
            "- Use git_diff to review changes before committing\n"
            "- Follow project commit message conventions (check context for rules)\n"
            "- Create branches following the project naming convention\n"
            "- Never force-push to main/master or shared branches\n"
            "- Set upstream tracking when pushing new branches\n"
            "- If merge conflicts are detected, they will be resolved via CodingAgent\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=enriched_msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=10,
        )

    @staticmethod
    def _needs_coding_help(msg: DelegationMessage) -> bool:
        """Heuristic: does this task involve merge conflicts needing code changes?"""
        conflict_keywords = [
            "merge conflict", "conflict resolution", "resolve conflict",
            "conflicting changes", "merge failed",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in conflict_keywords)
