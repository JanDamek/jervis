"""CodeReviewAgent -- code review orchestrator.

Reviews diffs, checks code quality, enforces forbidden file rules,
and coordinates fixes via sub-delegation to CodingAgent, TestAgent,
and ResearchAgent.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType, ModelTier
from app.tools.definitions import (
    TOOL_GIT_DIFF,
    TOOL_KB_SEARCH,
    TOOL_READ_FILE,
    TOOL_GREP_FILES,
)

logger = logging.getLogger(__name__)

REVIEW_SYSTEM_PROMPT = """\
You are the CodeReviewAgent in the Jervis multi-agent orchestrator.

Your role is to perform thorough code reviews. You analyze diffs, check code quality,
enforce project conventions, and identify potential issues.

Review checklist:
1. Correctness: Does the code do what it is supposed to? Logic errors, edge cases.
2. Security: No hardcoded secrets, SQL injection, command injection, XSS.
3. Forbidden files: Check that no forbidden files (e.g., .env, secrets/*) are modified.
4. Code style: Consistent with existing codebase conventions (use KB search).
5. Error handling: Proper error handling, no swallowed exceptions.
6. Performance: No obvious N+1 queries, unnecessary allocations, blocking calls.
7. Testing: Are new features covered by tests? Are existing tests still valid?
8. Documentation: Are public APIs documented? Are complex algorithms explained?

Workflow:
1. Use git_diff to get the changes being reviewed.
2. Use read_file to examine changed files in full context.
3. Use kb_search to check project conventions and existing patterns.
4. Use grep_files to find related code that might be affected.
5. Produce a structured review with findings categorized by severity.

Output format:
- Critical: Must fix before merge (bugs, security issues, forbidden files)
- Warning: Should fix (performance, missing error handling)
- Suggestion: Nice to have (style improvements, refactoring opportunities)
- Approved: Explicitly state if the code is approved or needs changes
"""


class CodeReviewAgent(BaseAgent):
    """Code review orchestrator.

    Reviews diffs for correctness, security, style, and convention compliance.
    Sub-delegates to CodingAgent for fixes, TestAgent for test gaps, and
    ResearchAgent for additional context when needed.
    """

    name = "code_review"
    description = (
        "Code review orchestrator -- reviews diffs for correctness, security, "
        "style compliance, and forbidden file violations. Coordinates fixes "
        "via CodingAgent and test verification via TestAgent."
    )
    domains = [DomainType.CODE]
    tools = [
        TOOL_GIT_DIFF,
        TOOL_KB_SEARCH,
        TOOL_READ_FILE,
        TOOL_GREP_FILES,
    ]
    can_sub_delegate = True
    max_depth = 4

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute code review with optional sub-delegation for fixes."""
        logger.info(
            "CodeReviewAgent executing: %s (id=%s)",
            msg.task_summary[:80], msg.delegation_id,
        )

        system_prompt = REVIEW_SYSTEM_PROMPT
        rules = state.get("rules")
        if rules:
            system_prompt += self._format_review_rules(rules)

        output = await self._agentic_loop(
            msg=msg, state=state,
            system_prompt=system_prompt,
            max_iterations=12,
            model_tier=ModelTier.LOCAL_LARGE,
        )

        review_text = output.result.lower()
        sub_results: list[str] = []

        if self._has_critical_issues(review_text):
            logger.info("CodeReviewAgent found critical issues")
            fix_out = await self._sub_delegate(
                target_agent_name="coding",
                task_summary="Fix critical issues from code review.",
                context="Review:\n" + output.result,
                parent_msg=msg, state=state,
            )
            output.sub_delegations.append(fix_out.delegation_id)
            sub_results.append("CodingAgent: " + str(fix_out.success))
            output.changed_files.extend(fix_out.changed_files)

        require_tests = rules and rules.get("require_tests", False)
        if require_tests and self._has_test_gaps(review_text):
            logger.info("CodeReviewAgent found test gaps")
            test_out = await self._sub_delegate(
                target_agent_name="test",
                task_summary="Generate tests for gaps found in review.",
                context="Review:\n" + output.result,
                parent_msg=msg, state=state,
            )
            output.sub_delegations.append(test_out.delegation_id)
            sub_results.append("TestAgent: " + str(test_out.success))

        if sub_results:
            output.result += "\n\n--- Sub-delegation Results ---\n"
            output.result += "\n".join(sub_results)

        output.structured_data["agent_type"] = "code_review"
        output.structured_data["has_critical"] = self._has_critical_issues(review_text)
        return output

    @staticmethod
    def _format_review_rules(rules: dict) -> str:
        """Format project rules for the review system prompt."""
        forbidden = rules.get("forbidden_files", [])
        max_files = rules.get("max_changed_files", 20)
        require_tests = rules.get("require_tests", False)
        return (
            f"\n\nProject rules:\n"
            f"- Forbidden file patterns: {forbidden}\n"
            f"- Max changed files per PR: {max_files}\n"
            f"- Require tests: {require_tests}"
        )

    @staticmethod
    def _has_critical_issues(review_text: str) -> bool:
        """Check if the review contains critical issues needing fixes."""
        indicators = [
            "critical:", "must fix", "security vulnerability",
            "forbidden file", "hardcoded secret",
            "sql injection", "command injection",
        ]
        return any(ind in review_text for ind in indicators)

    @staticmethod
    def _has_test_gaps(review_text: str) -> bool:
        """Check if the review mentions missing or insufficient tests."""
        indicators = [
            "missing test", "no tests", "test coverage",
            "untested", "needs testing", "should have tests",
        ]
        return any(ind in review_text for ind in indicators)
