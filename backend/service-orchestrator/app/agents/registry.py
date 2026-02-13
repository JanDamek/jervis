"""Agent registry — singleton that holds all registered agents.

The registry is populated at startup in main.py lifespan.
Used by:
- plan_delegations node: get_capability_summary() for LLM agent selection
- execute_delegation node: get() to find and run agents
- sub-delegation: find_for_domain() for agent discovery
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.agents.base import BaseAgent

from app.models import AgentCapability, DomainType

logger = logging.getLogger(__name__)


class AgentRegistry:
    """Singleton registry of all specialist agents."""

    _instance: AgentRegistry | None = None
    _agents: dict[str, BaseAgent]

    def __init__(self):
        self._agents = {}

    @classmethod
    def instance(cls) -> AgentRegistry:
        """Get or create the singleton registry."""
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    @classmethod
    def reset(cls) -> None:
        """Reset registry (for testing)."""
        cls._instance = None

    def register(self, agent: BaseAgent) -> None:
        """Register an agent. Overwrites if name already exists."""
        if agent.name in self._agents:
            logger.warning("Overwriting agent registration: %s", agent.name)
        self._agents[agent.name] = agent
        logger.info(
            "Registered agent: %s (domains=%s, tools=%d)",
            agent.name,
            [d.value for d in agent.domains],
            len(agent.tools),
        )

    def get(self, name: str) -> BaseAgent | None:
        """Get an agent by name."""
        return self._agents.get(name)

    def list_agents(self) -> list[AgentCapability]:
        """List capabilities of all registered agents."""
        return [agent.get_capability() for agent in self._agents.values()]

    def find_for_domain(self, domain: DomainType) -> list[BaseAgent]:
        """Find all agents that handle a given domain."""
        return [
            agent for agent in self._agents.values()
            if domain in agent.domains
        ]

    def get_capability_summary(self) -> str:
        """Build a text summary of all agents for LLM planning prompts.

        Used by plan_delegations node to tell the LLM what agents are available.
        """
        lines = ["Available specialist agents:\n"]

        for agent in sorted(self._agents.values(), key=lambda a: a.name):
            cap = agent.get_capability()
            domains_str = ", ".join(d.value for d in cap.domains)
            tools_str = ", ".join(cap.tool_names[:5])
            if len(cap.tool_names) > 5:
                tools_str += f" (+{len(cap.tool_names) - 5} more)"

            sub_delegate = "yes" if cap.can_sub_delegate else "no"

            lines.append(
                f"- **{cap.name}**: {cap.description}\n"
                f"  Domains: [{domains_str}]\n"
                f"  Tools: [{tools_str}]\n"
                f"  Can sub-delegate: {sub_delegate}\n"
            )

        return "\n".join(lines)

    @property
    def agent_count(self) -> int:
        """Number of registered agents."""
        return len(self._agents)

    def __contains__(self, name: str) -> bool:
        return name in self._agents
