"""Agent registry — singleton that holds all registered specialist agents.

The orchestrator's ``plan_delegations`` node queries the registry to
discover available agents and their capabilities for LLM-driven routing.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.models import AgentCapability, DomainType

if TYPE_CHECKING:
    from app.agents.base import BaseAgent

logger = logging.getLogger(__name__)


class AgentRegistry:
    """Thread-safe singleton registry for specialist agents."""

    _instance: AgentRegistry | None = None
    _agents: dict[str, BaseAgent]

    def __init__(self) -> None:
        self._agents = {}

    @classmethod
    def instance(cls) -> AgentRegistry:
        """Return (or create) the singleton registry."""
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    @classmethod
    def reset(cls) -> None:
        """Reset the singleton (for testing)."""
        cls._instance = None

    # ------------------------------------------------------------------
    # Registration
    # ------------------------------------------------------------------

    def register(self, agent: BaseAgent) -> None:
        """Register an agent by its ``name`` attribute."""
        if agent.name in self._agents:
            logger.warning("Agent '%s' already registered — overwriting", agent.name)
        self._agents[agent.name] = agent
        logger.info("Registered agent: %s (%s)", agent.name, agent.description[:60])

    # ------------------------------------------------------------------
    # Lookup
    # ------------------------------------------------------------------

    def get(self, name: str) -> BaseAgent | None:
        """Retrieve an agent by name, or ``None`` if not found."""
        return self._agents.get(name)

    def list_agents(self) -> list[AgentCapability]:
        """Return capability descriptors for every registered agent."""
        return [a.capability() for a in self._agents.values()]

    def find_for_domain(self, domain: DomainType) -> list[BaseAgent]:
        """Return all agents that declare the given domain."""
        return [a for a in self._agents.values() if domain in a.domains]

    def all_names(self) -> list[str]:
        """Return sorted list of all registered agent names."""
        return sorted(self._agents.keys())

    # ------------------------------------------------------------------
    # LLM prompt helper
    # ------------------------------------------------------------------

    def get_capability_summary(self) -> str:
        """Build a compact text summary for the LLM planner prompt.

        Example output::

            Available agents:
            - research: Searches KB, codebase, web for information. Domains: research, code. Tools: kb_search, web_search, ...
            - git: Git operations (commit, push, branch, PR). Domains: code. Tools: git_status, git_diff, ...
        """
        lines = ["Available agents:"]
        for agent in sorted(self._agents.values(), key=lambda a: a.name):
            cap = agent.capability()
            domains_str = ", ".join(d.value for d in cap.domains)
            tools_str = ", ".join(cap.tool_names[:8])
            if len(cap.tool_names) > 8:
                tools_str += ", ..."
            sub = " (can sub-delegate)" if cap.can_sub_delegate else ""
            lines.append(
                f"- {cap.name}: {cap.description} "
                f"Domains: {domains_str}. Tools: {tools_str}.{sub}"
            )
        return "\n".join(lines)

    def __len__(self) -> int:
        return len(self._agents)

    def __contains__(self, name: str) -> bool:
        return name in self._agents
