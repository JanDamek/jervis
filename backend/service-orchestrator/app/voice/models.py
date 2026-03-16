"""Voice pipeline models."""

from __future__ import annotations

from enum import Enum
from dataclasses import dataclass, field
from pydantic import BaseModel, Field


class VoiceIntent(str, Enum):
    """Classified intent from transcribed voice input."""
    SIMPLE_QUERY = "simple_query"    # KB search + quick LLM answer
    COMPLEX_TASK = "complex_task"    # Redirect to full orchestrator
    DICTATION = "dictation"          # Store text to KB, no response needed
    COMMAND = "command"              # System command (timer, reminder, etc.)
    NOISE = "noise"                  # Background noise, no actionable content


@dataclass
class IntentResult:
    """Result of intent classification."""
    intent: VoiceIntent
    confidence: float               # 0.0–1.0
    extracted_query: str             # Cleaned query (without filler words)
    reason: str = ""                # Why this intent was chosen


class VoiceStreamRequest(BaseModel):
    """Request for voice stream processing."""
    text: str                        # Transcribed text (from Whisper)
    source: str = "app_chat"         # Platform: app_chat, watch_chat, siri_watch
    client_id: str | None = None
    project_id: str | None = None
    group_id: str | None = None
    tts: bool = True                 # Generate TTS audio response
    meeting_id: str | None = None    # If set, also store for meeting
    live_assist: bool = False        # Live assist mode (hints only)
    chunk_index: int = 0             # For streaming: which chunk this is
    is_final: bool = True            # True = last chunk, process full text


@dataclass
class VoiceStreamEvent:
    """SSE event from voice pipeline."""
    event: str                       # Event type name
    data: dict = field(default_factory=dict)

    def to_sse(self) -> str:
        import json
        return f"event: {self.event}\ndata: {json.dumps(self.data, ensure_ascii=False)}\n\n"
