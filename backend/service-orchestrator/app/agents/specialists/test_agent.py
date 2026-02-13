"""Test Agent -- test generation and execution.

Runs existing tests, analyzes failures, and generates new test cases.
Can sub-delegate to CodingAgent for fixing test failures that require
code changes.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType
from app.tools.definitions import (
    TOOL_EXECUTE_COMMAND,
    TOOL_READ_FILE,
    TOOL_KB_SEARCH,
    TOOL_FIND_FILES,
    TOOL_GREP_FILES,
)

logger = logging.getLogger(__name__)


_TEST_TOOLS: list[dict] = [
    TOOL_EXECUTE_COMMAND,
    TOOL_READ_FILE,
    TOOL_KB_SEARCH,
    TOOL_FIND_FILES,
    TOOL_GREP_FILES,
]


class TestAgent(BaseAgent):
    """Specialist agent for test generation and execution.

    Discovers and runs tests, analyzes failures, and helps generate new
    test cases. Can sub-delegate to CodingAgent for fixing test failures
    that require source code or test code changes.
    """

    name = "test"
    description = (
        "Runs tests, analyzes failures, and generates test cases. "
        "Can discover test frameworks, execute test suites, parse results, "
        "and sub-delegate to CodingAgent for fixing failing tests."
    )
    domains = [DomainType.CODE]
    tools = _TEST_TOOLS
    can_sub_delegate = True

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute test operations.

        Strategy:
        1. Discover test framework and test files.
        2. Run tests and collect results.
        3. If failures found and fix requested, sub-delegate to CodingAgent.
        4. Report test results with pass/fail summary.
        """
        logger.info(
            "TestAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        enriched_context = msg.context

        # Sub-delegate to coding agent if task explicitly asks to fix failures
        if self._needs_fix(msg):
            coding_output = await self._sub_delegate(
                target_agent_name="coding",
                task_summary=(
                    "Fix test failures identified during test execution: "
                    f"{msg.task_summary}"
                ),
                context=msg.context,
                parent_msg=msg,
                state=state,
            )
            if coding_output.success and coding_output.result:
                enriched_context = (
                    f"{msg.context}\n\n"
                    f"--- Fix Result ---\n{coding_output.result}"
                )

        enriched_msg = msg.model_copy(update={"context": enriched_context})

        system_prompt = (
            "You are the TestAgent, a specialist in test generation, execution, "
            "and failure analysis.\n\n"
            "Your capabilities:\n"
            "- Discover test frameworks (pytest, jest, JUnit, Gradle test, etc.)\n"
            "- Find test files with find_files and grep_files\n"
            "- Run test suites with execute_command\n"
            "- Read test source code and production code for context\n"
            "- Search KB for testing conventions and patterns\n"
            "- Analyze test output and diagnose failures\n\n"
            "Test execution guidelines:\n"
            "- First discover the test framework: look for pytest.ini, jest.config, "
            "build.gradle, pom.xml, or similar config files\n"
            "- Run tests with verbose output for clear failure messages\n"
            "- Parse test output to extract pass/fail counts and failure details\n"
            "- For failures, read the relevant test and source files\n"
            "- Classify failures: real bugs vs flaky tests vs environment issues\n\n"
            "Test generation guidelines:\n"
            "- Follow existing test patterns and conventions in the project\n"
            "- Cover happy path, edge cases, error conditions, and boundaries\n"
            "- Use the project's preferred assertion style and mocking framework\n"
            "- Keep tests focused, readable, and independent\n\n"
            "Guidelines:\n"
            "- Always report test results clearly (X passed, Y failed, Z skipped)\n"
            "- Include failure messages and stack traces for failing tests\n"
            "- Suggest specific fixes for each failure when possible\n"
            "- Respond in English (internal chain language)"
        )

        return await self._agentic_loop(
            msg=enriched_msg,
            state=state,
            system_prompt=system_prompt,
            max_iterations=10,
        )

    @staticmethod
    def _needs_fix(msg: DelegationMessage) -> bool:
        """Heuristic: does this task ask to fix test failures?"""
        fix_keywords = [
            "fix test", "fix failing", "repair test", "resolve failure",
            "make tests pass", "fix broken test",
        ]
        task_lower = msg.task_summary.lower()
        return any(kw in task_lower for kw in fix_keywords)
