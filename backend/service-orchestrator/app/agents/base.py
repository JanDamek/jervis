"""Base agent framework for the multi-agent orchestrator.

Every specialist agent inherits from BaseAgent. The framework provides:
- Agentic loop (LLM ↔ tool calls until done)
- LLM calling with cloud escalation
- Tool execution via the shared ToolExecutor
- Sub-delegation with cycle detection and depth limits
"""

from __future__ import annotations

import json
import logging
import uuid
from abc import ABC, abstractmethod

from app.config import settings
from app.models import (
    AgentCapability,
    AgentOutput,
    DelegationMessage,
    DomainType,
)

logger = logging.getLogger(__name__)

# Maximum tokens in an agent response before we warn
_MAX_RESPONSE_TOKENS = 8192


class BaseAgent(ABC):
    """Abstract base class for all specialist agents.

    Subclasses must set class-level attributes and implement ``execute()``.
    """

    # --- Class attributes (set by subclasses) ---
    name: str = ""
    description: str = ""
    domains: list[DomainType] = []
    tools: list[dict] = []
    can_sub_delegate: bool = True
    max_depth: int = 4

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    @abstractmethod
    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute the delegation and return structured output."""

    def capability(self) -> AgentCapability:
        """Return a serialisable description for the LLM planner."""
        return AgentCapability(
            name=self.name,
            description=self.description,
            domains=list(self.domains),
            can_sub_delegate=self.can_sub_delegate,
            max_depth=self.max_depth,
            tool_names=[t["function"]["name"] for t in self.tools if "function" in t],
        )

    # ------------------------------------------------------------------
    # Agentic loop
    # ------------------------------------------------------------------

    async def _agentic_loop(
        self,
        msg: DelegationMessage,
        state: dict,
        system_prompt: str,
        max_iterations: int = 10,
    ) -> AgentOutput:
        """Core loop: LLM → tool calls → repeat → final answer.

        The loop ends when the LLM produces a text response without any
        tool calls, or when ``max_iterations`` is reached.
        """
        messages: list[dict] = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": self._build_user_message(msg)},
        ]

        artifacts: list[str] = []
        changed_files: list[str] = []
        sub_delegations: list[str] = []

        for iteration in range(max_iterations):
            logger.debug(
                "%s: agentic loop iteration %d/%d",
                self.name, iteration + 1, max_iterations,
            )

            response = await self._call_llm(
                messages=messages,
                tools=self.tools if self.tools else None,
                state=state,
            )
            message = response.choices[0].message
            content = message.content or ""
            tool_calls = getattr(message, "tool_calls", None)

            # Append assistant message to conversation
            assistant_msg: dict = {"role": "assistant", "content": content}
            if tool_calls:
                assistant_msg["tool_calls"] = [
                    {
                        "id": tc.id,
                        "type": "function",
                        "function": {
                            "name": tc.function.name,
                            "arguments": tc.function.arguments,
                        },
                    }
                    for tc in tool_calls
                ]
            messages.append(assistant_msg)

            # If no tool calls → final answer
            if not tool_calls:
                return AgentOutput(
                    delegation_id=msg.delegation_id,
                    agent_name=self.name,
                    success=True,
                    result=content,
                    artifacts=artifacts,
                    changed_files=changed_files,
                    sub_delegations=sub_delegations,
                )

            # Execute tool calls
            for tc in tool_calls:
                fn_name = tc.function.name
                try:
                    fn_args = json.loads(tc.function.arguments)
                except (json.JSONDecodeError, TypeError):
                    fn_args = {}

                tool_result = await self._execute_tool(
                    tool_name=fn_name,
                    arguments=fn_args,
                    state=state,
                    msg=msg,
                )

                messages.append({
                    "role": "tool",
                    "tool_call_id": tc.id,
                    "content": tool_result,
                })

        # Max iterations exhausted
        logger.warning(
            "%s: max iterations (%d) reached for delegation=%s",
            self.name, max_iterations, msg.delegation_id,
        )
        last_content = messages[-1].get("content", "") if messages else ""
        return AgentOutput(
            delegation_id=msg.delegation_id,
            agent_name=self.name,
            success=False,
            result=f"Max iterations ({max_iterations}) reached. Last: {last_content[:500]}",
            artifacts=artifacts,
            changed_files=changed_files,
            sub_delegations=sub_delegations,
            confidence=0.3,
        )

    # ------------------------------------------------------------------
    # LLM calling
    # ------------------------------------------------------------------

    async def _call_llm(
        self,
        messages: list[dict],
        tools: list[dict] | None = None,
        state: dict | None = None,
        max_tokens: int = _MAX_RESPONSE_TOKENS,
    ) -> object:
        """Call the LLM with cloud fallback using the shared helper.

        Uses ``llm_with_cloud_fallback`` from ``_helpers`` so that the
        same escalation logic (local → cloud → interrupt) is reused.
        """
        # Import here to avoid circular imports at module level
        from app.graph.nodes._helpers import llm_with_cloud_fallback

        effective_state = state or {}
        return await llm_with_cloud_fallback(
            state=effective_state,
            messages=messages,
            task_type="agent",
            max_tokens=max_tokens,
            tools=tools,
        )

    # ------------------------------------------------------------------
    # Tool execution
    # ------------------------------------------------------------------

    async def _execute_tool(
        self,
        tool_name: str,
        arguments: dict,
        state: dict,
        msg: DelegationMessage | None = None,
    ) -> str:
        """Execute a tool call via the shared executor.

        Returns the result as a string (never raises).
        """
        from app.tools.executor import execute_tool

        client_id = msg.client_id if msg else state.get("task", {}).get("client_id", "")
        project_id = msg.project_id if msg else state.get("task", {}).get("project_id")

        try:
            result = await execute_tool(
                tool_name=tool_name,
                arguments=arguments,
                client_id=client_id,
                project_id=project_id,
            )
            return result if isinstance(result, str) else json.dumps(result, default=str)
        except Exception as exc:
            logger.warning(
                "%s: tool %s failed: %s", self.name, tool_name, exc,
            )
            return f"Error executing {tool_name}: {exc}"

    # ------------------------------------------------------------------
    # Sub-delegation
    # ------------------------------------------------------------------

    async def _sub_delegate(
        self,
        target_agent_name: str,
        task_summary: str,
        context: str,
        parent_msg: DelegationMessage,
        state: dict,
    ) -> AgentOutput:
        """Delegate to another agent with cycle detection and depth check.

        Args:
            target_agent_name: The ``name`` attribute of the target agent.
            task_summary: What the sub-agent should do.
            context: Relevant context (token-budgeted by caller).
            parent_msg: The delegation message of the calling agent.
            state: Current orchestrator state dict.

        Returns:
            AgentOutput from the sub-agent (or a failure output on error).
        """
        from app.agents.registry import AgentRegistry

        # Depth check
        new_depth = parent_msg.depth + 1
        if new_depth > settings.max_delegation_depth:
            logger.warning(
                "%s: max delegation depth (%d) reached, cannot sub-delegate to %s",
                self.name, settings.max_delegation_depth, target_agent_name,
            )
            return AgentOutput(
                delegation_id=f"depth-limit-{uuid.uuid4().hex[:8]}",
                agent_name=target_agent_name,
                success=False,
                result=f"Max delegation depth ({settings.max_delegation_depth}) exceeded.",
                confidence=0.0,
            )

        # Cycle detection — walk the delegation chain
        delegation_stack = state.get("_delegation_stack", [])
        if target_agent_name in delegation_stack:
            logger.warning(
                "%s: cycle detected — %s already in stack %s",
                self.name, target_agent_name, delegation_stack,
            )
            return AgentOutput(
                delegation_id=f"cycle-{uuid.uuid4().hex[:8]}",
                agent_name=target_agent_name,
                success=False,
                result=f"Cycle detected: {target_agent_name} already in delegation chain.",
                confidence=0.0,
            )

        # Look up agent
        registry = AgentRegistry.instance()
        agent = registry.get(target_agent_name)
        if agent is None:
            return AgentOutput(
                delegation_id=f"unknown-{uuid.uuid4().hex[:8]}",
                agent_name=target_agent_name,
                success=False,
                result=f"Agent '{target_agent_name}' not found in registry.",
                confidence=0.0,
            )

        # Build sub-delegation message
        sub_id = f"sub-{parent_msg.delegation_id}-{uuid.uuid4().hex[:8]}"
        sub_msg = DelegationMessage(
            delegation_id=sub_id,
            parent_delegation_id=parent_msg.delegation_id,
            depth=new_depth,
            agent_name=target_agent_name,
            task_summary=task_summary,
            context=context,
            constraints=parent_msg.constraints,
            expected_output="",
            response_language=parent_msg.response_language,
            client_id=parent_msg.client_id,
            project_id=parent_msg.project_id,
            group_id=parent_msg.group_id,
        )

        # Push onto stack for cycle detection
        sub_state = dict(state)
        sub_state["_delegation_stack"] = delegation_stack + [self.name]

        logger.info(
            "%s → %s: sub-delegating (depth=%d, id=%s)",
            self.name, target_agent_name, new_depth, sub_id,
        )

        try:
            return await agent.execute(sub_msg, sub_state)
        except Exception as exc:
            logger.error(
                "%s: sub-delegation to %s failed: %s",
                self.name, target_agent_name, exc,
            )
            return AgentOutput(
                delegation_id=sub_id,
                agent_name=target_agent_name,
                success=False,
                result=f"Sub-delegation failed: {exc}",
                confidence=0.0,
            )

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _build_user_message(msg: DelegationMessage) -> str:
        """Build the initial user message from the delegation payload."""
        parts = [f"## Task\n{msg.task_summary}"]
        if msg.context:
            parts.append(f"\n## Context\n{msg.context}")
        if msg.constraints:
            parts.append(
                "\n## Constraints\n" + "\n".join(f"- {c}" for c in msg.constraints)
            )
        if msg.expected_output:
            parts.append(f"\n## Expected Output\n{msg.expected_output}")
        return "\n".join(parts)
