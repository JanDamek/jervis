"""Agent framework — base classes, registry, and legacy fallback.

Specialist agents are in agents/specialists/ subpackage.
"""

from app.agents.base import BaseAgent
from app.agents.registry import AgentRegistry
from app.agents.legacy_agent import LegacyAgent

__all__ = [
    "BaseAgent",
    "AgentRegistry",
    "LegacyAgent",
]
