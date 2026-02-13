"""TestAgent -- test generation, execution, and analysis.

Generates tests for new code, runs existing test suites, analyzes
failures, and sub-delegates to CodingAgent for fixing broken tests.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import AgentOutput, DelegationMessage, DomainType, ModelTier
from app.tools.definitions import (
    TOOL_EXECUTE_COMMAND,
    TOOL_READ_FILE,
    TOOL_FIND_FILES,
    TOOL_LIST_FILES,
)

logger = logging.getLogger(__name__)

TEST_SYSTEM_PROMPT = """\
You are the TestAgent in the Jervis multi-agent orchestrator.

Your role is to ensure code quality through testing. You can:
1. Generate tests -- Write unit/integration tests for new or changed code.
2. Run tests -- Execute test suites and report results.
3. Analyze failures -- Parse test output, identify root causes, suggest fixes.
4. Verify coverage -- Check that new code has adequate test coverage.

Workflow for test generation:
1. Use find_files to locate existing test files and understand the test framework.
2. Use read_file to examine the code that needs testing.
3. Use list_files to understand the project structure and test directory layout.
4. Generate tests following the project existing test patterns and conventions.
5. Report the test file paths and a summary of what is covered.

Workflow for test execution:
1. Use find_files to locate test configuration (pytest.ini, build.gradle, etc.).
2. Use execute_command to run the appropriate test command.
3. Parse the output for passes, failures, and errors.
4. For failures, use read_file to examine the failing test and source code.
5. Produce a structured report of results.

Test generation guidelines:
- Match the project test framework (JUnit, pytest, Jest, etc.).
- Follow existing test naming conventions.
- Include happy path, edge cases, and error scenarios.
- Use descriptive test names that explain what is being tested.
- Mock external dependencies appropriately.
- Keep tests focused -- one assertion concept per test.

Output should include:
- Test file paths (created or modified)
- Summary of test cases and what they cover
- Test execution results if tests were run
- Any failures with root cause analysis
"""


class TestAgent(BaseAgent):
    """Test generation, execution, and analysis specialist.

    Generates tests following project conventions, runs test suites,
    analyzes failures, and sub-delegates to CodingAgent for fixing
    test failures caused by bugs in production code.
    """

    name = "test"
    description = (
        "Test specialist -- generates unit/integration tests, runs test suites, "
        "analyzes failures, and verifies coverage for new or changed code."
    )
    domains = [DomainType.CODE]
    tools = [
        TOOL_EXECUTE_COMMAND,
        TOOL_READ_FILE,
        TOOL_FIND_FILES,
        TOOL_LIST_FILES,
    ]
    can_sub_delegate = True
    max_depth = 4

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute testing task.

        Runs the agentic loop for test generation/execution. If test
        failures are detected that stem from production code bugs,
        sub-delegates to CodingAgent for fixes.
        """
        logger.info(
            "TestAgent executing task: %s (delegation=%s)",
            msg.task_summary[:80],
            msg.delegation_id,
        )

        output = await self._agentic_loop(
            msg=msg,
            state=state,
            system_prompt=TEST_SYSTEM_PROMPT,
            max_iterations=12,
            model_tier=ModelTier.LOCAL_LARGE,
        )

        if output.success and self._has_production_code_failures(output.result):
            logger.info("TestAgent found production code bugs, delegating fix")
            fix_out = await self._sub_delegate(
                target_agent_name="coding",
                task_summary=(
                    "Fix the production code bugs identified by test failures. "
                    "The tests are correct -- the production code needs fixing."
                ),
                context="Test results:\n" + output.result,
                parent_msg=msg,
                state=state,
            )
            output.sub_delegations.append(fix_out.delegation_id)
            if fix_out.success:
                output.result += (
                    "\n\n--- Production Code Fix (via CodingAgent) ---\n"
                    + fix_out.result
                )
                output.changed_files.extend(fix_out.changed_files)
            else:
                output.result += (
                    "\n\nWARNING: Production code fix failed: "
                    + fix_out.result
                )
                output.confidence = min(output.confidence, 0.5)
                output.needs_verification = True

        output.structured_data["agent_type"] = "test"
        return output

    @staticmethod
    def _has_production_code_failures(result: str) -> bool:
        """Detect if test output suggests bugs in production code.

        Looks for patterns indicating the test is correct but the
        production code is wrong.
        """
        failure_indicators = [
            "production code bug", "implementation error",
            "source code needs fix", "expected behavior differs",
            "assertion failed", "FAILED",
        ]
        result_lower = result.lower()
        has_failures = any(i.lower() in result_lower for i in failure_indicators)
        test_wrong = any(
            p in result_lower
            for p in ["test is incorrect", "fix the test", "test bug", "flaky test"]
        )
        return has_failures and not test_wrong
