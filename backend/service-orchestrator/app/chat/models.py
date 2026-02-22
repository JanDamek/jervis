"""Chat models — request/response types for foreground chat endpoint."""

from __future__ import annotations

from pydantic import BaseModel, Field


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

    # Context — when user clicks on a specific user_task
    context_task_id: str | None = None           # TaskDocument._id if responding to user_task

    # Timestamp
    timestamp: str | None = None


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
