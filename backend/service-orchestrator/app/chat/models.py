"""Chat models — request/response types for foreground chat endpoint."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Intent Router categories (Phase 3)
# ---------------------------------------------------------------------------

class ChatCategory(str, Enum):
    """Category of user intent — determines tool set, prompt, and routing."""
    DIRECT = "direct"           # Simple answer, no tools needed (greeting, chit-chat, quick question)
    RESEARCH = "research"       # KB search, code search, web search
    TASK_MGMT = "task_mgmt"    # Task lifecycle (create, schedule, list, cancel)
    COMPLEX = "complex"         # Multi-step work plan, decomposition, coding dispatch
    MEMORY = "memory"           # KB corrections, learning, fact verification


@dataclass
class RoutingDecision:
    """Result of intent routing — determines how the message is processed."""
    category: ChatCategory
    confidence: float           # 0.0–1.0
    reason: str                 # Human-readable reason for the decision
    use_cloud: bool             # Whether to use cloud model (OpenRouter)
    max_iterations: int         # Max agentic loop iterations
    tool_names: list[str] = field(default_factory=list)  # Tool names for this category


class ChatRequest(BaseModel):
    """Request from Kotlin server to Python /chat endpoint.

    Python reads MongoDB for context directly (motor).
    Kotlin sends just the minimum needed.
    """
    session_id: str                              # ChatSession._id as string
    message: str                                 # User message text
    message_sequence: int                        # Sequence number (assigned by Kotlin)
    user_id: str = "jan"

    # Scope — from UI selection
    active_client_id: str | None = None          # Currently selected client in UI
    active_project_id: str | None = None         # Currently selected project in UI
    active_group_id: str | None = None           # Group ID of active project (for cross-project KB)

    # Context — when user clicks on a specific user_task
    context_task_id: str | None = None           # TaskDocument._id if responding to user_task

    # Timestamp
    timestamp: str | None = None

    # Cloud routing policy (from CloudModelPolicy)
    max_openrouter_tier: str = "NONE"  # "NONE" / "FREE" / "PAID" / "PREMIUM" (compat: "PAID_LOW" / "PAID_HIGH")


class ChatStreamEvent(BaseModel):
    """SSE event pushed back to Kotlin -> UI."""
    type: str               # "token" | "tool_call" | "tool_result" | "done" | "error" | "thinking"
    content: str = ""
    metadata: dict = Field(default_factory=dict)
    # metadata examples:
    #   type=tool_call:   {"tool": "kb_search", "args": {...}}
    #   type=tool_result: {"tool": "kb_search", "result_preview": "..."}
    #   type=thinking:    {"thought": "..."}
    #   type=done:        {"created_tasks": [...], "used_tools": [...], "responded_tasks": [...]}
    #   type=error:       {"error": "..."}


# ---------------------------------------------------------------------------
# Long message decomposition (internal, not API-facing)
# ---------------------------------------------------------------------------

@dataclass
class SubTopic:
    """One extracted sub-topic from a multi-topic user message."""
    title: str           # Short Czech title (e.g., "Bug v přihlašování")
    topic_type: str      # "bug_report" | "question" | "request" | "info" | "task"
    char_start: int      # Start index in original message
    char_end: int        # End index in original message


@dataclass
class SubTopicResult:
    """Result of processing one sub-topic through the mini agentic loop."""
    topic: SubTopic
    text: str
    used_tools: list[str] = field(default_factory=list)
    created_tasks: list[dict] = field(default_factory=list)
    responded_tasks: list[str] = field(default_factory=list)
