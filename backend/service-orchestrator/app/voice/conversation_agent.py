"""Conversation Context Agent — continuous dialog handler.

Maintains conversation state, accumulates speech fragments,
searches KB for relevant context as topics emerge, prepares
answer candidates, and responds at the right moment.

Designed for natural dialog: the agent continuously listens,
thinks while the speaker talks, and responds when there's
a complete thought AND a good answer ready.

Architecture:
- ConversationBuffer: accumulates speech fragments into coherent text
- ContextTracker: identifies topics and searches KB in parallel
- AnswerBuffer: prepares candidate answers, picks the best one
- ResponseDecider: determines when to respond (complete thought + good answer)
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass, field
from typing import AsyncIterator

import httpx
import litellm

from app.config import settings
from app.llm.router_client import route_request
from app.voice.models import VoiceStreamEvent

logger = logging.getLogger(__name__)

# ── Data structures ─────────────────────────────────────────────────────

@dataclass
class ConversationTurn:
    """One turn in the conversation."""
    role: str  # "user" or "assistant"
    text: str
    timestamp: float = field(default_factory=time.time)
    topic: str = ""  # extracted topic/theme


@dataclass
class AnswerCandidate:
    """A prepared answer candidate."""
    text: str
    confidence: float  # 0.0-1.0 — how relevant/good this answer is
    source: str  # "kb", "llm", "acknowledgment"
    kb_context: str = ""
    topic: str = ""


@dataclass
class SpeakerProfile:
    """Speaking style profile for voice cloning personality."""
    name: str = "Jervis"
    language: str = "cs"
    style_notes: str = ""  # How to speak (formal/informal, word choices, etc.)
    vocabulary: list[str] = field(default_factory=list)  # Characteristic words/phrases
    sentence_patterns: list[str] = field(default_factory=list)  # How sentences are structured


# ── Conversation Agent ──────────────────────────────────────────────────

class ConversationContextAgent:
    """Stateful conversation agent that continuously tracks dialog context.

    Usage:
        agent = ConversationContextAgent(client_id="...", project_id="...")

        # Feed speech fragments as they arrive (streaming)
        agent.add_fragment("Potřebuju projít")
        agent.add_fragment("meeting ze čtvrtku")
        agent.add_fragment("a rozebrat co přesně se tam mělo")

        # Check if we should respond
        if agent.should_respond():
            async for event in agent.generate_response():
                yield event
    """

    def __init__(
        self,
        client_id: str = "",
        project_id: str = "",
        group_id: str = "",
        speaker_profile: SpeakerProfile | None = None,
    ):
        self.client_id = client_id
        self.project_id = project_id
        self.group_id = group_id
        self.speaker_profile = speaker_profile or SpeakerProfile()

        # Conversation history (sliding window)
        self.history: list[ConversationTurn] = []
        self.max_history = 20  # Keep last 20 turns

        # Current utterance buffer (accumulates fragments)
        self.current_fragments: list[str] = []
        self.current_text: str = ""
        self.last_fragment_time: float = 0

        # KB context cache — searched as topics emerge
        self.kb_context: str = ""
        self.kb_search_task: asyncio.Task | None = None
        self.last_kb_query: str = ""

        # Answer buffer — prepared candidates
        self.answer_candidates: list[AnswerCandidate] = []

        # Timing
        self.silence_start: float = 0
        self.speech_active: bool = False

    # ── Fragment accumulation ───────────────────────────────────────────

    def add_fragment(self, text: str) -> None:
        """Add a speech fragment (from streaming transcription).

        Fragments arrive as Whisper transcribes 5s segments.
        We accumulate them into coherent text.
        """
        text = text.strip()
        if not text:
            return

        self.current_fragments.append(text)
        self.current_text = " ".join(self.current_fragments).strip()
        self.last_fragment_time = time.time()
        self.speech_active = True
        self.silence_start = 0

        logger.info("CONV_AGENT: fragment added, current=%s", self.current_text[:100])

        # Trigger async KB search if topic changed significantly
        if len(self.current_text) > 20 and self.current_text != self.last_kb_query:
            self._trigger_kb_search(self.current_text)

    def mark_silence(self) -> None:
        """Mark that speaker has gone silent."""
        if self.speech_active:
            self.speech_active = False
            self.silence_start = time.time()

    # ── Response decision ───────────────────────────────────────────────

    def should_respond(self) -> bool:
        """Determine if we should generate a response now.

        Based on:
        1. Is there a complete thought? (content analysis)
        2. Has the speaker paused long enough?
        3. Do we have relevant KB context?

        NOT based on simple silence threshold — we analyze content.
        """
        text = self.current_text.strip()
        if not text or len(text) < 5:
            return False

        # If speaker is still talking — don't respond yet
        if self.speech_active:
            return False

        # Need at least some silence
        silence_ms = (time.time() - self.silence_start) * 1000 if self.silence_start > 0 else 0
        if silence_ms < 800:  # At least 800ms silence
            return False

        # Content-based analysis
        return self._is_complete_thought(text)

    def _is_complete_thought(self, text: str) -> bool:
        """Analyze if text represents a complete thought worth responding to.

        Uses linguistic heuristics for Czech:
        - Questions (ending with ?)
        - Commands (imperative verbs)
        - Complete sentences (ending with . or !)
        - Sufficient length + silence = probably done
        """
        trimmed = text.strip()
        lower = trimmed.lower()

        # Direct question — always respond
        if trimmed.endswith("?"):
            return True

        # Sentence-ending punctuation
        if len(trimmed) > 15 and (trimmed.endswith(".") or trimmed.endswith("!")):
            return True

        # Command verbs at start
        command_starts = [
            "udělej", "nastav", "spusť", "vytvoř", "napiš", "pošli", "zapiš",
            "najdi", "zobraz", "otevři", "zavři", "restartuj", "smaž",
            "řekni", "odpověz", "vysvětli", "popiš", "shrň", "projdi",
            "potřebuju", "potřebuji", "chci", "chtěl bych",
        ]
        if any(lower.startswith(cmd) for cmd in command_starts) and len(trimmed) > 15:
            return True

        # Long enough text + silence = probably complete
        if len(trimmed) > 40:
            return True

        return False

    # ── KB search ───────────────────────────────────────────────────────

    def _trigger_kb_search(self, query: str) -> None:
        """Trigger async KB search for the current topic.

        Runs in background — results ready when we need to respond.
        """
        self.last_kb_query = query

        # Cancel previous search if still running
        if self.kb_search_task and not self.kb_search_task.done():
            self.kb_search_task.cancel()

        self.kb_search_task = asyncio.ensure_future(self._search_kb(query))

    async def _search_kb(self, query: str) -> None:
        """Search KB for relevant context."""
        url = f"{settings.knowledgebase_url.rstrip('/')}/api/v1/retrieve"
        try:
            payload = {"query": query, "top_k": 5}
            if self.client_id:
                payload["client_id"] = self.client_id
            if self.project_id:
                payload["project_id"] = self.project_id

            async with httpx.AsyncClient(timeout=8.0) as client:
                resp = await client.post(url, json=payload)
                resp.raise_for_status()
                data = resp.json()

            items = data.get("items", [])
            if items:
                parts = []
                for item in items[:5]:
                    content = item.get("content", "")[:400]
                    source = item.get("sourceUrn", "")
                    score = item.get("score", 0)
                    if score > 0.05:  # Only include somewhat relevant results
                        parts.append(f"[{source}] {content}")
                self.kb_context = "\n---\n".join(parts) if parts else ""
                logger.info("CONV_AGENT: KB search found %d relevant items", len(parts))
            else:
                self.kb_context = ""

        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.warning("CONV_AGENT: KB search failed: %s", e)
            self.kb_context = ""

    # ── Response generation ─────────────────────────────────────────────

    async def generate_response(self) -> AsyncIterator[VoiceStreamEvent]:
        """Generate a contextual response to the current utterance.

        Uses:
        1. Conversation history for multi-turn context
        2. KB context (pre-fetched) for factual grounding
        3. Speaker profile for style matching

        Yields SSE events: token, response, done.
        """
        text = self.current_text.strip()
        if not text:
            return

        # Wait for KB search to finish (max 2s)
        if self.kb_search_task and not self.kb_search_task.done():
            try:
                await asyncio.wait_for(asyncio.shield(self.kb_search_task), timeout=2.0)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                pass

        # Save user turn to history
        self.history.append(ConversationTurn(role="user", text=text))
        if len(self.history) > self.max_history:
            self.history = self.history[-self.max_history:]

        # Build conversation context for LLM
        system_prompt = self._build_system_prompt()
        messages = self._build_messages(text)

        # Route to LLM
        route = await route_request(
            capability="chat",
            max_tier="FREE",
            estimated_tokens=500,
            processing_mode="FOREGROUND",
        )
        model = route.model or "openrouter/auto"

        kwargs = {
            "model": model,
            "messages": [{"role": "system", "content": system_prompt}] + messages,
            "temperature": 0.4,
            "max_tokens": 300,
            "stream": True,
        }
        if route.api_base:
            kwargs["api_base"] = route.api_base
        if route.api_key:
            kwargs["api_key"] = route.api_key

        response_text = ""
        try:
            response = await litellm.acompletion(**kwargs)
            async for chunk in response:
                delta = chunk.choices[0].delta
                if delta and delta.content:
                    token = delta.content
                    response_text += token
                    yield VoiceStreamEvent(event="token", data={"text": token})
        except Exception as e:
            logger.warning("CONV_AGENT: LLM response failed: %s", e)
            response_text = "Omlouvám se, nedokázal jsem odpovědět."
            yield VoiceStreamEvent(event="token", data={"text": response_text})

        # Save assistant turn to history
        self.history.append(ConversationTurn(role="assistant", text=response_text))

        yield VoiceStreamEvent(event="response", data={"text": response_text, "complete": True})

        # Reset for next utterance
        self.current_fragments.clear()
        self.current_text = ""

    def _build_system_prompt(self) -> str:
        """Build system prompt with KB context and style."""
        parts = [
            "Jsi Jervis, osobní AI asistent. Odpovídáš v češtině, stručně a k věci.",
            "Toto je hlasový dialog — odpovědi čte TTS, takže:",
            "- MAX 2-3 věty",
            "- Jasně a přímo k tématu",
            "- Žádné formátování (markdown, seznamy) — jen plynulý text",
            "- Pokud nevíš, řekni to upřímně",
            "- Pokud je to jen zdvořilostní konverzace, odpověz přirozeně a krátce",
        ]

        if self.speaker_profile.style_notes:
            parts.append(f"\nStyl odpovědí: {self.speaker_profile.style_notes}")

        if self.kb_context:
            parts.append(f"\nRelevantní kontext ze znalostní báze:\n{self.kb_context[:2000]}")

        return "\n".join(parts)

    def _build_messages(self, current_text: str) -> list[dict]:
        """Build message list from conversation history."""
        messages = []

        # Include recent history for multi-turn context
        for turn in self.history[-10:]:  # Last 10 turns
            if turn.role == "user":
                messages.append({"role": "user", "content": turn.text})
            else:
                messages.append({"role": "assistant", "content": turn.text})

        # Current utterance (if not already in history)
        if not messages or messages[-1]["content"] != current_text:
            messages.append({"role": "user", "content": current_text})

        return messages

    # ── Conversation analysis ───────────────────────────────────────────

    def get_conversation_summary(self) -> str:
        """Get a summary of the conversation so far."""
        if not self.history:
            return "(no conversation yet)"

        lines = []
        for turn in self.history[-10:]:
            role = "Uživatel" if turn.role == "user" else "Asistent"
            lines.append(f"{role}: {turn.text[:100]}")
        return "\n".join(lines)

    def detect_topic_shift(self) -> bool:
        """Detect if the conversation topic has shifted significantly."""
        if len(self.history) < 2:
            return False

        last_user = [t for t in self.history if t.role == "user"]
        if len(last_user) < 2:
            return False

        # Simple heuristic: if new text shares few words with previous
        prev_words = set(last_user[-2].text.lower().split())
        curr_words = set(self.current_text.lower().split())

        if not prev_words or not curr_words:
            return False

        overlap = len(prev_words & curr_words) / max(len(prev_words), len(curr_words))
        return overlap < 0.15  # Less than 15% word overlap = topic shift
