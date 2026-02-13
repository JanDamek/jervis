"""CodingAgent -- central gateway to K8s coding agents.

Routes coding tasks to the appropriate K8s-based coding agent.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType, ModelTier
from app.tools.definitions import (
    TOOL_READ_FILE,
    TOOL_FIND_FILES,
    TOOL_LIST_FILES,
    TOOL_GREP_FILES,
    TOOL_CODE_SEARCH,
    TOOL_GET_REPOSITORY_STRUCTURE,
    TOOL_GET_TECHNOLOGY_STACK,
    TOOL_KB_SEARCH,
)

logger = logging.getLogger(__name__)

CODING_SYSTEM_PROMPT = """\
You are the CodingAgent in the Jervis multi-agent orchestrator.

Your role is to analyze coding tasks, understand the codebase context, and produce
detailed, actionable coding instructions that a K8s coding agent (Aider, OpenHands,
Claude Code, or Junie) will execute.

Workflow:
1. Analyze the task requirements and constraints.
2. Search the codebase to understand existing patterns, conventions, and relevant files.
3. Identify which files need to be created or modified.
4. Produce precise coding instructions including:
   - Exact file paths to create/modify
   - Code changes with full context (not just snippets)
   - Test expectations if applicable
   - Any dependencies or imports needed

Guidelines:
- Follow existing code conventions discovered via search tools.
- Respect forbidden files and branch naming constraints.
- Prefer small, focused changes over sweeping refactors.
- Include error handling and edge cases.
- If the task is ambiguous, state assumptions clearly.

Output a structured coding plan with all changes needed to complete the task.
"""


class CodingAgent(BaseAgent):
    """Central gateway to K8s coding agents.

    Currently runs an agentic loop to produce coding instructions.
    Future: will prepare workspace, dispatch K8s Job to the selected
    coding agent, monitor completion, and return the result.
    """

    name = "coding"
    description = (
        "Central coding gateway -- analyzes tasks, searches codebase, and produces "
        "detailed coding instructions for K8s-based coding agents (Aider/OpenHands/"
        "Claude Code/Junie)."
    )
    domains = [DomainType.CODE]
    tools = [
        TOOL_READ_FILE,
        TOOL_FIND_FILES,
        TOOL_LIST_FILES,
        TOOL_GREP_FILES,
        TOOL_CODE_SEARCH,
        TOOL_GET_REPOSITORY_STRUCTURE,
        TOOL_GET_TECHNOLOGY_STACK,
        TOOL_KB_SEARCH,
    ]
    can_sub_delegate = False
    max_depth = 4

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute the coding task.

        Currently uses the agentic loop to produce coding instructions.
        Once K8s integration is wired, this will:
        1. Prepare workspace via workspace_manager
        2. Select the best coding agent based on task + project rules
        3. Dispatch a K8s Job via job_runner
        4. Monitor job completion
        5. Read and validate the result
        """
        logger.info(
            "CodingAgent executing task: %s (delegation=%s)",
            msg.task_summary[:80],
            msg.delegation_id,
        )

        system_prompt = CODING_SYSTEM_PROMPT
        if msg.constraints:
            constraints_block = "\n".join(f"- {c}" for c in msg.constraints)
            system_prompt += f"\n\nProject constraints:\n{constraints_block}"

        output = await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=15,
            model_tier=ModelTier.LOCAL_LARGE,
        )

        output.structured_data["agent_type"] = "coding"
        output.structured_data["k8s_dispatch"] = False
        output.needs_verification = True

        return output
