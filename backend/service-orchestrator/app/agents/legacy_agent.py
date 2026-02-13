"""Legacy agent — wraps the existing 14-node orchestrator logic as a BaseAgent.

This agent serves as a safety-net fallback when the new delegation system
encounters an unsupported domain or when ``use_specialist_agents`` is off.
It delegates to the existing respond/plan/execute_step pipeline.
"""

from __future__ import annotations

import logging

from app.agents.base import BaseAgent
from app.models import (
    AgentOutput,
    DelegationMessage,
    DomainType,
)

logger = logging.getLogger(__name__)


class LegacyAgent(BaseAgent):
    """Fallback agent that wraps the existing orchestrator pipeline.

    Handles ALL domains — used as last-resort when no specialist matches
    or when the delegation system is disabled.
    """

    name = "legacy"
    description = (
        "Fallback agent wrapping the existing orchestrator pipeline. "
        "Handles any domain by delegating to the legacy 14-node graph logic."
    )
    domains = list(DomainType)  # Handles everything
    tools = []  # Uses internal node functions, not OpenAI tools
    can_sub_delegate = False
    max_depth = 0

    async def execute(
        self, msg: DelegationMessage, state: dict,
    ) -> AgentOutput:
        """Execute using the legacy respond node logic.

        For now this uses the respond node's agentic loop directly,
        which gives the LLM access to all standard tools (KB, web search,
        code search, filesystem, etc.).
        """
        logger.info(
            "LegacyAgent executing: delegation=%s, task=%s",
            msg.delegation_id,
            msg.task_summary[:80],
        )

        # Use the respond node's tool-calling loop as the execution engine.
        # Import here to avoid circular imports.
        from app.graph.nodes._helpers import llm_with_cloud_fallback
        from app.tools.definitions import ALL_RESPOND_TOOLS

        system_prompt = (
            "You are a general-purpose AI assistant. Answer the user's question "
            "using the available tools. Search the knowledge base and web as needed. "
            "Be thorough and provide actionable answers.\n\n"
            "Respond in English (internal chain language). The final response "
            "will be translated to the user's language by the orchestrator."
        )

        messages: list[dict] = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": self._build_user_message(msg)},
        ]

        max_iterations = 10
        for _ in range(max_iterations):
            try:
                response = await llm_with_cloud_fallback(
                    state=state,
                    messages=messages,
                    task_type="respond",
                    max_tokens=8192,
                    tools=ALL_RESPOND_TOOLS,
                )
            except Exception as exc:
                logger.error("LegacyAgent LLM call failed: %s", exc)
                return AgentOutput(
                    delegation_id=msg.delegation_id,
                    agent_name=self.name,
                    success=False,
                    result=f"LLM call failed: {exc}",
                    confidence=0.0,
                )

            message = response.choices[0].message
            content = message.content or ""
            tool_calls = getattr(message, "tool_calls", None)

            # Append assistant message
            assistant_msg: dict = {"role": "assistant", "content": content}
            if tool_calls:
                import json as _json
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

            if not tool_calls:
                return AgentOutput(
                    delegation_id=msg.delegation_id,
                    agent_name=self.name,
                    success=True,
                    result=content,
                )

            # Execute tool calls
            for tc in tool_calls:
                result = await self._execute_tool(
                    tool_name=tc.function.name,
                    arguments=_safe_json(tc.function.arguments),
                    state=state,
                    msg=msg,
                )
                messages.append({
                    "role": "tool",
                    "tool_call_id": tc.id,
                    "content": result,
                })

        return AgentOutput(
            delegation_id=msg.delegation_id,
            agent_name=self.name,
            success=False,
            result="Max iterations reached in legacy agent.",
            confidence=0.3,
        )


def _safe_json(s: str) -> dict:
    """Parse JSON string, returning empty dict on failure."""
    import json
    try:
        return json.loads(s)
    except (json.JSONDecodeError, TypeError):
        return {}
