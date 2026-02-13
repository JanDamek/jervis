"""Coding Agent -- central gateway to coding agents via K8s Jobs.

Dispatches coding work to external agents (Aider, OpenHands, Claude, Junie)
running as Kubernetes Jobs. Prepares workspaces, creates jobs, and reads
results. Does not sub-delegate to other agents.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType

logger = logging.getLogger(__name__)


TOOL_K8S_JOB_CREATE: dict = {
    "type": "function",
    "function": {
        "name": "k8s_job_create",
        "description": (
            "Create and dispatch a Kubernetes Job for a coding agent. "
            "The job runs the specified agent type (aider, openhands, claude, junie) "
            "with the given instructions in the prepared workspace. "
            "Returns the job name and status."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "agent_type": {
                    "type": "string",
                    "enum": ["aider", "openhands", "claude", "junie"],
                    "description": "Coding agent to use for the job.",
                },
                "instructions": {
                    "type": "string",
                    "description": (
                        "Detailed coding instructions for the agent. Include what files "
                        "to modify, what changes to make, and acceptance criteria."
                    ),
                },
                "workspace_path": {
                    "type": "string",
                    "description": "Absolute path to the prepared workspace directory.",
                },
                "branch": {
                    "type": "string",
                    "description": "Git branch to work on (agent will checkout this branch).",
                },
                "timeout_minutes": {
                    "type": "integer",
                    "description": "Job timeout in minutes (default 30, max 120).",
                    "default": 30,
                },
            },
            "required": ["agent_type", "instructions", "workspace_path", "branch"],
        },
    },
}


TOOL_WORKSPACE_PREPARE: dict = {
    "type": "function",
    "function": {
        "name": "workspace_prepare",
        "description": (
            "Prepare a workspace directory for a coding job. Clones the "
            "repository, checks out the correct branch, and ensures the "
            "workspace is clean and ready for agent modifications. "
            "Returns the workspace path and current branch."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "repository_url": {
                    "type": "string",
                    "description": "Git repository URL to clone (if workspace doesn't exist).",
                },
                "branch": {
                    "type": "string",
                    "description": "Branch to checkout (will create if it doesn't exist).",
                },
                "base_branch": {
                    "type": "string",
                    "description": "Base branch to create new branch from (default: main/master).",
                },
            },
            "required": ["branch"],
        },
    },
}


TOOL_RESULT_READ: dict = {
    "type": "function",
    "function": {
        "name": "result_read",
        "description": (
            "Read the result of a completed K8s coding job. Returns the "
            "agent's output including summary, changed files, success status, "
            "and any error messages. Polls until job completes or times out."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "job_name": {
                    "type": "string",
                    "description": "Name of the K8s job to read results from.",
                },
                "timeout_seconds": {
                    "type": "integer",
                    "description": "Maximum seconds to wait for job completion (default 600).",
                    "default": 600,
                },
            },
            "required": ["job_name"],
        },
    },
}


_CODING_TOOLS: list[dict] = [
    TOOL_K8S_JOB_CREATE,
    TOOL_WORKSPACE_PREPARE,
    TOOL_RESULT_READ,
]


class CodingAgent(BaseAgent):
    """Central gateway to coding agents (Aider/OpenHands/Claude/Junie).

    Prepares workspaces, dispatches K8s Jobs for coding work, and reads
    results. Does not sub-delegate -- all coordination happens through
    K8s Job lifecycle management.
    """

    name = "coding"
    description = (
        "Central gateway to coding agents (Aider, OpenHands, Claude, Junie). "
        "Prepares workspaces, dispatches K8s Jobs for coding work, and "
        "reads results. Handles agent selection and workspace lifecycle."
    )
    domains = [DomainType.CODE]
    tools = _CODING_TOOLS
    can_sub_delegate = False

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute coding task via K8s Job dispatch.

        Flow:
        1. Prepare workspace (clone/checkout branch).
        2. Create K8s Job with coding agent and instructions.
        3. Read job result when complete.
        """
        logger.info(
            "CodingAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        system_prompt = (
            "You are the CodingAgent, the central gateway to coding agents. "
            "You coordinate coding work by preparing workspaces, dispatching "
            "K8s Jobs to external coding agents, and reading their results.\n\n"
            "Your capabilities:\n"
            "- Prepare workspace (clone repo, checkout branch)\n"
            "- Create K8s coding jobs with agent selection (aider, openhands, claude, junie)\n"
            "- Read results from completed coding jobs\n\n"
            "Agent selection guidelines:\n"
            "- aider: Best for focused, single-file or small-scope edits\n"
            "- openhands: Good for multi-file refactoring and complex changes\n"
            "- claude: Best for nuanced reasoning, architecture changes, documentation\n"
            "- junie: Best for JetBrains-integrated projects (Kotlin, Java)\n\n"
            "Workflow:\n"
            "1. First prepare the workspace with workspace_prepare\n"
            "2. Create a K8s job with k8s_job_create, providing clear instructions\n"
            "3. Read the result with result_read\n"
            "4. Report the outcome (success/failure, changed files, summary)\n\n"
            "Guidelines:\n"
            "- Write detailed, unambiguous instructions for the coding agent\n"
            "- Include specific file paths and acceptance criteria in instructions\n"
            "- Choose the right agent type based on the task complexity\n"
            "- Always read and report the job result\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=5,
        )
