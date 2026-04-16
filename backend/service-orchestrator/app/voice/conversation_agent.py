"""Conversation Context Agent — continuous dialog handler.

Maintains conversation state, searches KB for relevant context,
and responds with grounded, contextual answers.

Key principles:
- ALWAYS search KB before responding — no hallucinations
- If KB has no relevant data, say "Nemám k tomu informace"
- Czech language, natural voice style (short, direct)
- Multi-turn context tracking
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass, field
from typing import AsyncIterator

import httpx

from app.config import settings
from app.voice.models import VoiceStreamEvent

logger = logging.getLogger(__name__)


def _router_chat_url() -> str:
    base = settings.ollama_url.rstrip("/").replace("/v1", "").replace("/api", "")
    return f"{base}/api/chat"


@dataclass
class ConversationTurn:
    """One turn in the conversation."""
    role: str  # "user" or "assistant"
    text: str
    timestamp: float = field(default_factory=time.time)


@dataclass
class SpeakerProfile:
    """Speaking style profile."""
    name: str = "Jervis"
    language: str = "cs"
    style_notes: str = ""
    vocabulary: list[str] = field(default_factory=list)
    sentence_patterns: list[str] = field(default_factory=list)


class ConversationContextAgent:
    """Stateful conversation agent with KB grounding.

    Each voice session gets one agent instance that tracks
    conversation history and provides contextual responses.
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
        self.max_history = 20

        # Current utterance
        self.current_text: str = ""

        # KB context — searched for every query
        self.kb_context: str = ""

    def add_fragment(self, text: str) -> None:
        """Add transcribed text. Server sends complete utterance text."""
        text = text.strip()
        if not text:
            return
        # Server accumulates fragments and sends complete text
        # So we just replace (not append)
        self.current_text = text
        logger.info("CONV_AGENT: text=%s", self.current_text[:100])

    def mark_silence(self) -> None:
        """Mark that speaker has gone silent — ready to respond."""
        pass  # Response is triggered by stream_handler calling generate_response()

    async def generate_response(self) -> AsyncIterator[VoiceStreamEvent]:
        """Generate a contextual response.

        Pipeline:
        1. Search KB for relevant context (ALWAYS)
        2. Build conversation context with history
        3. Generate response grounded in KB data
        """
        text = self.current_text.strip()
        if not text:
            return

        # 1. ALWAYS search KB — this is the foundation
        await self._search_kb(text)

        # 2. Save user turn
        self.history.append(ConversationTurn(role="user", text=text))
        if len(self.history) > self.max_history:
            self.history = self.history[-self.max_history:]

        # 3. Build prompts
        system_prompt = self._build_system_prompt()
        messages = self._build_messages()

        # 4. Stream via router /api/chat — capability + client resolves the
        #    tier and picks a model.
        logger.info("CONV_AGENT: streaming via router, kb_context=%d chars", len(self.kb_context))
        body = {
            "messages": [{"role": "system", "content": system_prompt}] + messages,
            "stream": True,
            "options": {"temperature": 0.3, "num_predict": 250},
        }
        req_headers = {
            "Content-Type": "application/json",
            "X-Capability": "chat",
        }
        if self.client_id:
            req_headers["X-Client-Id"] = self.client_id

        # 5. Stream response
        response_text = ""
        try:
            timeout = httpx.Timeout(connect=5, read=None, write=5, pool=10)
            async with httpx.AsyncClient(timeout=timeout) as client:
                async with client.stream(
                    "POST", _router_chat_url(), json=body, headers=req_headers,
                ) as resp:
                    resp.raise_for_status()
                    async for line in resp.aiter_lines():
                        if not line.strip():
                            continue
                        try:
                            chunk = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        msg = chunk.get("message") or {}
                        token = msg.get("content") or ""
                        if token:
                            response_text += token
                            yield VoiceStreamEvent(event="token", data={"text": token})
                        if chunk.get("done"):
                            break
        except Exception as e:
            logger.error("CONV_AGENT: LLM failed: %s", e)
            response_text = "Omlouvám se, nepodařilo se mi odpovědět."
            yield VoiceStreamEvent(event="token", data={"text": response_text})

        # 6. Save assistant turn
        self.history.append(ConversationTurn(role="assistant", text=response_text))

        yield VoiceStreamEvent(event="response", data={"text": response_text, "complete": True})

        # Reset for next utterance
        self.current_text = ""
        logger.info("CONV_AGENT: response=%s", response_text[:150])

    async def _search_kb(self, query: str) -> None:
        """Search KB for relevant context. ALWAYS runs."""
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
                    content = item.get("content", "")[:500]
                    source = item.get("sourceUrn", "")
                    score = item.get("score", 0)
                    if score > 0.03:
                        parts.append(f"[{source}] (relevance: {score:.0%})\n{content}")
                self.kb_context = "\n---\n".join(parts) if parts else ""
                logger.info("CONV_AGENT: KB found %d items (query: %s)", len(parts), query[:60])
            else:
                self.kb_context = ""
                logger.info("CONV_AGENT: KB returned 0 items for: %s", query[:60])

        except Exception as e:
            logger.warning("CONV_AGENT: KB search failed: %s", e)
            self.kb_context = ""

    def _build_system_prompt(self) -> str:
        """Build system prompt with KB context."""
        prompt = """Jsi Jervis, osobní AI asistent Jana Dameka. Komunikuješ v češtině.

PRAVIDLA PRO HLASOVÝ DIALOG:
- Odpovídej stručně, MAX 2-3 věty. Toto je hlasový dialog, ne chat.
- Mluv přirozeně, jako člověk. Žádné seznamy, markdown, hvězdičky.
- Odpovídej PŘÍMO na otázku. Neodbíhej, neuvádí co "nemůžeš".
- NIKDY neříkej "zpracovávám", "odpovím na telefonu" ani podobné nesmysly.
- Pokud máš kontext ze znalostní báze, POUŽIJ ho. Odpověz na základě faktů.
- Pokud NEMÁŠ relevantní kontext, řekni upřímně: "K tomuto nemám informace."
- NIKDY nevymýšlej fakta. Buď přesný nebo řekni že nevíš.
"""

        if self.kb_context:
            prompt += f"""
KONTEXT ZE ZNALOSTNÍ BÁZE (použij pro odpověď):
{self.kb_context[:2500]}
"""
        else:
            prompt += """
ŽÁDNÝ KONTEXT: Znalostní báze nevrátila relevantní výsledky.
Pokud otázka vyžaduje konkrétní data, řekni že k tomu nemáš informace.
Na obecné otázky a konverzaci můžeš odpovědět přirozeně.
"""

        if self.speaker_profile.style_notes:
            prompt += f"\nStyl: {self.speaker_profile.style_notes}"

        return prompt

    def _build_messages(self) -> list[dict]:
        """Build message list from conversation history."""
        messages = []
        for turn in self.history[-10:]:
            messages.append({"role": turn.role, "content": turn.text})
        return messages
