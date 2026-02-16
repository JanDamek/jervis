"""Data models for the Memory Agent architecture.

Defines Affairs (thematic containers), SessionContext (working memory),
ContextSwitchResult (LLM classification), and write-buffer types.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, Field


# --- Affair Status ---


class AffairStatus(str, Enum):
    ACTIVE = "active"       # Currently being worked on (max 1 at a time)
    PARKED = "parked"       # Paused, waiting (delivery, response, etc.)
    RESOLVED = "resolved"   # Done, pending archival to KB
    ARCHIVED = "archived"   # In KB, not in RAM


class AffairMessage(BaseModel):
    """A message associated with an affair."""

    role: str       # "user" | "assistant"
    content: str
    timestamp: str  # ISO 8601


class Affair(BaseModel):
    """Thematic container for contextually-related information.

    An affair groups related facts, messages, and pending actions
    under a single topic (e.g., "eBay order for Jeep part").
    """

    id: str
    title: str
    summary: str = ""
    status: AffairStatus = AffairStatus.ACTIVE
    topics: list[str] = Field(default_factory=list)
    key_facts: dict[str, str] = Field(default_factory=dict)
    pending_actions: list[str] = Field(default_factory=list)
    related_affairs: list[str] = Field(default_factory=list)
    messages: list[AffairMessage] = Field(default_factory=list)
    created_at: str = ""   # ISO 8601
    updated_at: str = ""   # ISO 8601
    client_id: str = ""
    project_id: str | None = None

    def to_kb_document(self) -> str:
        """Render affair as structured markdown for KB ingestion."""
        lines = [
            f"# Záležitost: {self.title}",
            "",
            f"## Stav: {self.status.value.upper()}",
            "",
            "## Shrnutí",
            self.summary or "(žádné shrnutí)",
            "",
        ]

        if self.key_facts:
            lines.append("## Klíčové fakta")
            for key, value in self.key_facts.items():
                lines.append(f"- {key}: {value}")
            lines.append("")

        if self.pending_actions:
            lines.append("## Čekající akce")
            for action in self.pending_actions:
                lines.append(f"- {action}")
            lines.append("")

        if self.topics:
            lines.append(f"## Témata: {', '.join(self.topics)}")
            lines.append("")

        return "\n".join(lines)


# --- Context Switch ---


class ContextSwitchType(str, Enum):
    CONTINUE = "continue"       # Continue with current affair
    SWITCH = "switch"           # Switch to existing parked affair
    AD_HOC = "ad_hoc"           # One-off question, return to current after
    NEW_AFFAIR = "new_affair"   # Start a brand new affair


class ContextSwitchResult(BaseModel):
    """Result of LLM-based context switch detection."""

    type: ContextSwitchType
    target_affair_id: str | None = None
    target_affair_title: str | None = None
    new_affair_title: str | None = None
    confidence: float = 0.0
    reasoning: str = ""


# --- Write Buffer ---


class WritePriority(str, Enum):
    CRITICAL = "critical"   # Sync KB write, immediate indexing (<3s)
    HIGH = "high"           # Queue head, fast processing (<10s)
    NORMAL = "normal"       # Standard KB ingest (best-effort)


class PendingWrite(BaseModel):
    """A buffered KB write waiting to be flushed."""

    source_urn: str
    content: str
    kind: str = "affair"
    metadata: dict = Field(default_factory=dict)
    priority: WritePriority = WritePriority.NORMAL
    created_at: str = ""  # ISO 8601


# --- Session Context ---


class SessionContext(BaseModel):
    """Working memory for the current orchestration session."""

    active_affair: Affair | None = None
    parked_affairs: list[Affair] = Field(default_factory=list)
    conversation_topics: list[str] = Field(default_factory=list)
    user_preferences: dict = Field(default_factory=dict)
    last_context_switch_at: str | None = None
