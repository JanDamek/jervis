"""TaskMode -- configuration for unified agent handler.

Each agent instance runs with a specific TaskMode that controls:
- How it communicates (SSE streaming vs callbacks)
- What tools are available (tier)
- How many iterations it can run
- Whether it can ask for user approval
- Whether it should plan before executing
- Whether it should review code after dispatch
- Its priority level
- Whether to prefer cloud over GPU
"""
from __future__ import annotations

from dataclasses import dataclass

from .tool_sets import ToolTier


@dataclass(frozen=True)
class TaskMode:
    stream_sse: bool = False
    tool_tier: ToolTier = ToolTier.CORE
    max_openrouter_tier: str = "FREE"
    max_iterations: int = 15
    allow_approval: bool = False
    allow_planning: bool = False
    allow_review: bool = False
    priority: str = "normal"      # "critical" | "normal" | "idle"
    prefer_cloud: bool = False    # True = prefer OpenRouter over GPU


# ---------------------------------------------------------------------------
# Presets
# ---------------------------------------------------------------------------

CHAT_MODE = TaskMode(
    stream_sse=True,
    tool_tier=ToolTier.FULL,
    max_iterations=25,
    allow_approval=True,
    priority="critical",
    prefer_cloud=True,
)

BACKGROUND_MODE = TaskMode(
    tool_tier=ToolTier.EXTENDED,
    max_iterations=15,
    allow_planning=True,
    allow_review=True,
    priority="normal",
)

QUALIFICATION_MODE = TaskMode(
    tool_tier=ToolTier.CORE,
    max_iterations=5,
    priority="normal",
)

IDLE_MODE = TaskMode(
    tool_tier=ToolTier.EXTENDED,
    max_iterations=10,
    allow_planning=True,
    priority="idle",
)
