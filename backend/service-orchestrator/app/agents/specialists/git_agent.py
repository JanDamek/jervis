"""GitAgent -- git operations specialist.

Handles all git operations: status inspection, branching, committing,
pushing, PR creation, and conflict resolution. For complex merge
conflicts, sub-delegates to CodingAgent.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType, ModelTier
from app.tools.definitions import GIT_WORKSPACE_TOOLS, TOOL_READ_FILE, TOOL_KB_SEARCH

logger = logging.getLogger(__name__)

GIT_SYSTEM_PROMPT = """\
You are the GitAgent in the Jervis multi-agent orchestrator.

Your role is to perform git operations safely and correctly. You handle:
- Repository status inspection (status, log, diff, blame)
- Branch creation and management
- Commit preparation and execution
- Push operations (with approval flow awareness)
- Pull request creation and description generation
- Merge conflict analysis

Safety rules:
1. NEVER force-push to protected branches (main, master, develop).
2. ALWAYS check git status before committing to avoid accidental inclusions.
3. ALWAYS use the project branch naming convention from constraints.
4. ALWAYS use the project commit prefix convention from constraints.
5. NEVER commit files matching forbidden file patterns.
6. For merge conflicts you cannot resolve with simple edits, report them for sub-delegation.

Workflow for commits:
1. Run git_status to see current state.
2. Run git_diff to review changes.
3. Verify no forbidden files are staged.
4. Prepare a meaningful commit message following the project commit prefix.
5. Report the result with changed files list.

Workflow for PR creation:
1. Review recent commits on the branch (git_log).
2. Generate a clear PR title and description.
3. Include summary of changes, files modified, and testing notes.

Output should include:
- Commands executed and their results
- Summary of git state changes
- Any warnings about risky operations
- List of changed files
"""


class GitAgent(BaseAgent):
    """Git operations specialist.

    Performs repository operations with safety guardrails and project
    convention enforcement. Sub-delegates complex conflict resolution
    to CodingAgent.
    """

    name = "git"
    description = (
        "Git operations specialist -- handles branching, commits, pushes, "
        "PR creation, and conflict resolution with safety guardrails."
    )
    domains = [DomainType.CODE, DomainType.DEVOPS]
    tools = GIT_WORKSPACE_TOOLS + [TOOL_READ_FILE, TOOL_KB_SEARCH]
    can_sub_delegate = True
    max_depth = 4

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute git operations."""
        logger.info(
            "GitAgent executing task: %s (delegation=%s)",
            msg.task_summary[:80],
            msg.delegation_id,
        )

        system_prompt = GIT_SYSTEM_PROMPT
        rules = state.get("rules")
        if rules:
            system_prompt += self._format_rules(rules)

        output = await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=10,
            model_tier=ModelTier.LOCAL_STANDARD,
        )

        # Check if the result mentions unresolved conflicts
        if output.success and self._needs_conflict_resolution(output.result):
            logger.info(
                "GitAgent detected merge conflict, sub-delegating to "
                "CodingAgent (delegation=%s)",
                msg.delegation_id,
            )
            conflict_output = await self._sub_delegate(
                target_agent_name="coding",
                task_summary=(
                    "Resolve merge conflicts in the following files. "
                    "Analyze both sides and produce the correct merged result."
                ),
                context=output.result,
                parent_msg=msg,
                state=state,
            )
            output.sub_delegations.append(conflict_output.delegation_id)
            if conflict_output.success:
                output.result += (
                    "\n\n--- Conflict Resolution (via CodingAgent) ---\n"
                    + conflict_output.result
                )
                output.changed_files.extend(conflict_output.changed_files)
            else:
                output.result += (
                    "\n\nWARNING: Conflict resolution failed: "
                    + conflict_output.result
                )
                output.confidence = min(output.confidence, 0.4)

        output.structured_data["agent_type"] = "git"
        return output

    @staticmethod
    def _format_rules(rules: dict) -> str:
        """Format project rules for the system prompt."""
        keys = ["branch_naming", "commit_prefix", "forbidden_files"]
        lines = [f"- {k}: {rules.get(k)}" for k in keys if rules.get(k)]
        return "\n\nProject conventions:\n" + "\n".join(lines)

    @staticmethod
    def _needs_conflict_resolution(result: str) -> bool:
        """Detect if the agent result mentions unresolved merge conflicts."""
        markers = [
            "CONFLICT (content)",
            "merge conflict",
            "<<<<<<< HEAD",
            "unresolved conflict",
        ]
        result_lower = result.lower()
        return any(m.lower() in result_lower for m in markers)
