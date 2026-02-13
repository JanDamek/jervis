"""Base agent framework for multi-agent orchestrator.

Every specialist agent inherits from BaseAgent and implements execute().
The base class provides:
- LLM calling with model tier selection and escalation
- Tool execution via the shared ToolExecutor
- Sub-delegation to other agents (with depth/cycle checks)
- System prompt construction
"""

from __future__ import annotations

import json
import logging
import uuid
from abc import ABC, abstractmethod
from typing import Any

from app.config import settings
from app.llm.provider import LLMProvider, llm_provider, EscalationPolicy
from app.models import (
    AgentCapability,
    AgentOutput,
    DelegationMessage,
    DelegationMetrics,
    DomainType,
    ModelTier,
)

logger = logging.getLogger(__name__)


class BaseAgent(ABC):
    """Abstract base class for all specialist agents.

    Subclass contract:
    - Set class-level: name, description, domains, tools, can_sub_delegate
    - Implement execute() → AgentOutput
    """

    # --- Must be set by subclass ---
    name: str = ""
    description: str = ""
    domains: list[DomainType] = []
    tools: list[dict] = []          # OpenAI function-calling schemas
    can_sub_delegate: bool = True
    max_depth: int = 4

    def __init__(self):
        self._llm = llm_provider
        self._escalation = EscalationPolicy()

    # --- Public API ---

    @abstractmethod
    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute the agent's task. Must be implemented by subclass."""
        ...

    def get_capability(self) -> AgentCapability:
        """Return capability descriptor for registry/LLM planning."""
        return AgentCapability(
            name=self.name,
            description=self.description,
            domains=list(self.domains),
            can_sub_delegate=self.can_sub_delegate,
            max_depth=self.max_depth,
            tool_names=[t["function"]["name"] for t in self.tools if "function" in t],
        )

    # --- Shared helpers for subclasses ---

    async def _call_llm(
        self,
        messages: list[dict],
        tools: list[dict] | None = None,
        model_tier: ModelTier | None = None,
        max_tokens: int = 8192,
        temperature: float = 0.1,
    ) -> Any:
        """Call LLM with optional tools. Selects tier based on context size if not specified."""
        if model_tier is None:
            # Estimate context tokens (rough: 4 chars per token)
            total_chars = sum(len(m.get("content", "")) for m in messages)
            model_tier = self._escalation.select_local_tier(total_chars // 4)

        return await self._llm.completion(
            messages=messages,
            tier=model_tier,
            tools=tools or None,
            temperature=temperature,
            max_tokens=max_tokens,
        )

    async def _execute_tool(
        self,
        tool_name: str,
        arguments: dict,
        state: dict,
    ) -> str:
        """Execute a tool call in the agent's workspace context.

        Uses the shared executor from app.tools.executor.
        """
        from app.tools.executor import execute_tool

        client_id = state.get("task", {}).get("client_id", "")
        project_id = state.get("task", {}).get("project_id")

        return await execute_tool(
            tool_name=tool_name,
            arguments=arguments,
            client_id=client_id,
            project_id=project_id,
        )

    async def _sub_delegate(
        self,
        target_agent_name: str,
        task_summary: str,
        context: str,
        parent_msg: DelegationMessage,
        state: dict,
    ) -> AgentOutput:
        """Delegate to another agent with depth+1 and cycle detection.

        Args:
            target_agent_name: Name of the agent to delegate to.
            task_summary: What the target agent should do.
            context: Relevant context (token-budgeted by caller).
            parent_msg: The DelegationMessage this agent received.
            state: Current orchestrator state.

        Returns:
            AgentOutput from the target agent.

        Raises:
            ValueError: If max depth exceeded or cycle detected.
        """
        from app.agents.registry import AgentRegistry

        # Depth check
        new_depth = parent_msg.depth + 1
        if new_depth > settings.max_delegation_depth:
            return AgentOutput(
                delegation_id=f"sub-{uuid.uuid4().hex[:8]}",
                agent_name=target_agent_name,
                success=False,
                result=f"Max delegation depth ({settings.max_delegation_depth}) exceeded.",
                confidence=0.0,
            )

        # Cycle detection: walk up parent chain
        # Simple check: target shouldn't be the same as any ancestor
        if target_agent_name == self.name:
            return AgentOutput(
                delegation_id=f"sub-{uuid.uuid4().hex[:8]}",
                agent_name=target_agent_name,
                success=False,
                result=f"Cycle detected: {self.name} cannot delegate to itself.",
                confidence=0.0,
            )

        # Find target agent
        registry = AgentRegistry.instance()
        target = registry.get(target_agent_name)
        if target is None:
            return AgentOutput(
                delegation_id=f"sub-{uuid.uuid4().hex[:8]}",
                agent_name=target_agent_name,
                success=False,
                result=f"Agent '{target_agent_name}' not found in registry.",
                confidence=0.0,
            )

        # Build sub-delegation message
        sub_msg = DelegationMessage(
            delegation_id=f"sub-{uuid.uuid4().hex[:8]}",
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

        logger.info(
            "Sub-delegation: %s (depth %d) → %s (depth %d), task: %s",
            self.name, parent_msg.depth,
            target_agent_name, new_depth,
            task_summary[:80],
        )

        try:
            output = await target.execute(sub_msg, state)
            logger.info(
                "Sub-delegation complete: %s → %s, success=%s, confidence=%.2f",
                self.name, target_agent_name, output.success, output.confidence,
            )
            return output
        except Exception as e:
            logger.error(
                "Sub-delegation failed: %s → %s, error: %s",
                self.name, target_agent_name, e,
            )
            return AgentOutput(
                delegation_id=sub_msg.delegation_id,
                agent_name=target_agent_name,
                success=False,
                result=f"Sub-delegation error: {e}",
                confidence=0.0,
            )

    # Communication protocol rules — every agent follows these in responses
    _COMMUNICATION_PROTOCOL = (
        "\n\n## Response Protocol (MANDATORY)\n"
        "You report back to the orchestrator. Follow these rules:\n"
        "- Be maximally COMPACT but include ALL substantive content.\n"
        "- Never pad, never repeat the question, never add pleasantries.\n"
        "- Use structured format:\n"
        "  STATUS: 1 (success) | 0 (failure) | P (partial)\n"
        "  RESULT: <your complete answer — as short as possible, as long as needed>\n"
        "  ARTIFACTS: <list of created/changed files, commits, etc. if any>\n"
        "  ISSUES: <problems found, blockers, risks — only if any>\n"
        "  CONFIDENCE: <0.0-1.0>\n"
        "  NEEDS_VERIFICATION: <true/false — set true if KB cross-check recommended>\n"
        "- For error reports: include error type, root cause, and suggested fix.\n"
        "- For code changes: include file paths and brief description of each change.\n"
        "- Omit sections that are empty (e.g., skip ARTIFACTS if none).\n"
        "- NEVER truncate your findings. The orchestrator needs complete information to decide.\n"
    )

    def _build_system_prompt(self, msg: DelegationMessage) -> str:
        """Build agent-specific system prompt.

        Override in subclass for custom prompts.
        Includes communication protocol that ensures compact but complete responses.
        """
        tool_descriptions = ""
        if self.tools:
            tool_names = [t["function"]["name"] for t in self.tools if "function" in t]
            tool_descriptions = f"\n\nAvailable tools: {', '.join(tool_names)}"

        constraints_text = ""
        if msg.constraints:
            constraints_text = "\n\nConstraints:\n" + "\n".join(
                f"- {c}" for c in msg.constraints
            )

        return (
            f"You are {self.name}, a specialist agent in the Jervis multi-agent system.\n"
            f"Role: {self.description}\n"
            f"\nYou MUST respond in English (internal chain language). "
            f"The final response will be translated to '{msg.response_language}' by the orchestrator."
            f"{tool_descriptions}"
            f"{constraints_text}"
            f"{self._COMMUNICATION_PROTOCOL}"
        )

    async def _agentic_loop(
        self,
        msg: DelegationMessage,
        state: dict,
        system_prompt: str | None = None,
        max_iterations: int = 15,
        model_tier: ModelTier | None = None,
    ) -> AgentOutput:
        """Run a standard agentic loop: LLM call → tool calls → iterate.

        This is the default execution pattern for most agents.
        Override execute() for custom behavior.
        """
        if system_prompt is None:
            system_prompt = self._build_system_prompt(msg)

        messages: list[dict] = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": f"{msg.task_summary}\n\nContext:\n{msg.context}"},
        ]

        tools_to_use = self.tools if self.tools else None
        final_content = ""
        tool_call_count = 0

        for iteration in range(max_iterations):
            response = await self._call_llm(
                messages, tools=tools_to_use,
                model_tier=model_tier,
            )

            choice = response.choices[0] if response.choices else None
            if choice is None:
                break

            assistant_msg = choice.message
            content = getattr(assistant_msg, "content", None) or ""
            tool_calls = getattr(assistant_msg, "tool_calls", None)

            if content:
                final_content = content

            if not tool_calls:
                # No more tool calls — done
                break

            # Append assistant message with tool calls
            messages.append({
                "role": "assistant",
                "content": content,
                "tool_calls": [
                    {
                        "id": tc.id if hasattr(tc, "id") else f"call_{iteration}_{i}",
                        "type": "function",
                        "function": {
                            "name": tc.function.name,
                            "arguments": tc.function.arguments,
                        },
                    }
                    for i, tc in enumerate(tool_calls)
                ],
            })

            # Execute each tool call
            for tc in tool_calls:
                func_name = tc.function.name
                try:
                    func_args = json.loads(tc.function.arguments)
                except json.JSONDecodeError:
                    func_args = {}

                tool_call_count += 1
                logger.debug(
                    "Agent %s tool call #%d: %s(%s)",
                    self.name, tool_call_count, func_name,
                    str(func_args)[:100],
                )

                result = await self._execute_tool(func_name, func_args, state)

                result_str = str(result)
                if len(result_str) > 8000:
                    result_str = (
                        result_str[:8000]
                        + f"\n\n[TRUNCATED — full result was {len(result_str)} chars. "
                        f"Request specific portions if needed.]"
                    )

                messages.append({
                    "role": "tool",
                    "tool_call_id": tc.id if hasattr(tc, "id") else f"call_{iteration}",
                    "content": result_str,
                })

        return AgentOutput(
            delegation_id=msg.delegation_id,
            agent_name=self.name,
            success=True,
            result=final_content,
            confidence=0.8 if final_content else 0.3,
        )
