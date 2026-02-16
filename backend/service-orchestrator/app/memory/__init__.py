"""Memory Agent architecture for the orchestrator.

Provides affair-based context management, Local Quick Memory (LQM),
and intelligent context switching between topics.
"""

from app.memory.agent import MemoryAgent, reset_lqm
from app.memory.lqm import LocalQuickMemory
from app.memory.models import (
    Affair,
    AffairMessage,
    AffairStatus,
    ContextSwitchResult,
    ContextSwitchType,
    PendingWrite,
    SessionContext,
    WritePriority,
)

__all__ = [
    "Affair",
    "AffairMessage",
    "AffairStatus",
    "ContextSwitchResult",
    "ContextSwitchType",
    "LocalQuickMemory",
    "MemoryAgent",
    "PendingWrite",
    "SessionContext",
    "WritePriority",
    "reset_lqm",
]
