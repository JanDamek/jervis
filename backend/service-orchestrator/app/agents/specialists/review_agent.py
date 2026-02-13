"""Code Review Agent -- code review orchestration.

Reviews code changes for quality, bugs, security issues, and adherence
to project conventions. Can sub-delegate to CodingAgent for fixes,
ResearchAgent for context, and TestAgent for running tests.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import (
    TOOL_GIT_DIFF,
    TOOL_GIT_SHOW,
    TOOL_KB_SEARCH,
    TOOL_READ_FILE,
    TOOL_GREP_FILES,
)

logger = logging.getLogger(__name__)


_REVIEW_TOOLS: list[dict] = [
    TOOL_GIT_DIFF,
    TOOL_GIT_SHOW,
    TOOL_KB_SEARCH,
    TOOL_READ_FILE,
    TOOL_GREP_FILES,
]


class CodeReviewAgent(BaseAgent):
    """Specialist agent for code review orchestration.

    Analyzes code changes for quality, bugs, security vulnerabilities,
    and adherence to project conventions. Can sub-delegate to:
    - CodingAgent: for applying fixes to review findings
    - ResearchAgent: for gathering context about patterns and conventions
    - TestAgent: for running tests to validate changes
    """

    name = "code_review"
    description = (
        "Reviews code changes for quality, bugs, security issues, and "
        "adherence to project conventions. Can sub-delegate to CodingAgent "
        "for fixes, ResearchAgent for context, and TestAgent for validation."
    )
    domains = [DomainType.CODE]
    tools = _REVIEW_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute code review.

        Strategy:
        1. If task needs project context, sub-delegate to ResearchAgent.
        2. Run agentic loop to analyze code changes.
        3. Optionally sub-delegate to TestAgent for validation.
        4. Optionally sub-delegate to CodingAgent for automated fixes.
        """
        logger.info(
            "CodeReviewAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        enriched_context = msg.context

        # Gather project conventions and context if needed
        if self._needs_research(msg):
            research_output = await self._sub_delegate(
                target_agent_name="research",
                task_summary=(
                    "Gather project conventions, coding standards, and relevant "
                    f"architecture context for code review: {msg.task_summary}"
                ),
                context=msg.context,
                parent_msg=msg,
                state=state,
            )
            if research_output.success and research_output.result:
                enriched_context = (
                    f"{msg.context}\n\n"
                    f"--- Project Context ---\n{research_output.result}"
                )

        # Run tests if the review involves testable changes
        if self._needs_tests(msg):
            test_output = await self._sub_delegate(
                target_agent_name="test",
                task_summary=(
                    "Run existing tests to verify code changes do not break "
                    f"anything: {msg.task_summary}"
                ),
                context=enriched_context,
                parent_msg=msg,
                state=state,
            )
            if test_output.result:
                enriched_context = (
                    f"{enriched_context}\n\n"
                    f"--- Test Results ---\n{test_output.result}"
                )

        enriched_msg = msg.model_copy(update={"context": enriched_context})

        system_prompt = (
            "You are the CodeReviewAgent, a specialist in reviewing code "
            "changes for quality, bugs, security, and adherence to project "
            "conventions.\n\n"
            "Your capabilities:\n"
            "- Review diffs to identify bugs, logic errors, and anti-patterns\n"
            "- Check code against project coding conventions (from KB)\n"
            "- Read source files for full context around changes\n"
            "- Search codebase for similar patterns and inconsistencies\n"
            "- Analyze security implications of changes\n\n"
            "Review checklist:\n"
            "- Correctness: logic errors, edge cases, null handling\n"
            "- Security: injection, auth bypass, data exposure\n"
            "- Performance: N+1 queries, unnecessary allocations, blocking calls\n"
            "- Style: naming conventions, code organization, documentation\n"
            "- Tests: adequate coverage, edge case tests, regression tests\n"
            "- Architecture: separation of concerns, dependency direction\n\n"
            "Guidelines:\n"
            "- Always start by viewing the diff with git_diff\n"
            "- Read surrounding code for context with read_file\n"
            "- Check KB for project-specific conventions with kb_search\n"
            "- Classify findings by severity (CRITICAL, HIGH, MEDIUM, LOW, INFO)\n"
            "- Provide specific, actionable feedback with code suggestions\n"
            "- Acknowledge good patterns and improvements\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=enriched_msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=12,
        )

    @staticmethod
    def _needs_research(msg: DelegationMessage) -> bool:
        """Heuristic: does this review need project context from research?"""
        research_keywords = [
            "convention", "standard", "architecture", "pattern",
            "best practice", "guideline", "compliance",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in research_keywords)

    @staticmethod
    def _needs_tests(msg: DelegationMessage) -> bool:
        """Heuristic: should tests be run as part of this review?"""
        test_keywords = [
            "test", "verify", "validate", "regression", "coverage",
            "full review", "thorough review",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in test_keywords)
