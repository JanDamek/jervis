"""Unified voice stream handler.

Entry point for all voice processing. Receives transcribed text
(from Whisper STT done by Kotlin server), classifies intent,
and routes to the appropriate handler:
- SIMPLE_QUERY → quick_responder (KB + FREE LLM, <3s)
- COMPLEX_TASK → full orchestrator chat pipeline
- DICTATION → store to KB, confirm
- COMMAND → execute system command
- NOISE → ignore

Yields VoiceStreamEvent for SSE streaming.
"""

from __future__ import annotations

import json
import logging
from typing import AsyncIterator

import httpx

from app.config import settings
from app.voice.models import VoiceIntent, VoiceStreamEvent, VoiceStreamRequest
from app.voice.intent_classifier import classify_intent
from app.voice.quick_responder import quick_respond

logger = logging.getLogger(__name__)


async def handle_voice_stream(request: VoiceStreamRequest) -> AsyncIterator[VoiceStreamEvent]:
    """Process transcribed voice input and yield SSE events.

    This is called AFTER Whisper STT — receives text, not audio.
    The Kotlin server handles STT and forwards text here.
    """

    text = request.text.strip()
    if not text:
        yield VoiceStreamEvent(event="error", data={"text": "Prázdný vstup"})
        yield VoiceStreamEvent(event="done", data={})
        return

    # Step 1: Classify intent
    intent_result = await classify_intent(text)
    logger.info(
        "VOICE_INTENT | intent=%s | confidence=%.2f | query=%s | reason=%s",
        intent_result.intent.value,
        intent_result.confidence,
        intent_result.extracted_query[:60],
        intent_result.reason,
    )

    # Step 2: Route based on intent
    if intent_result.intent == VoiceIntent.NOISE:
        yield VoiceStreamEvent(event="done", data={})
        return

    if intent_result.intent == VoiceIntent.DICTATION:
        async for event in _handle_dictation(intent_result.extracted_query, request):
            yield event
        return

    if intent_result.intent == VoiceIntent.COMMAND:
        # Commands go through the orchestrator for now (tool execution)
        async for event in _handle_via_orchestrator(text, request):
            yield event
        return

    if intent_result.intent == VoiceIntent.SIMPLE_QUERY:
        # Quick KB + FREE LLM response
        async for event in quick_respond(
            query=intent_result.extracted_query,
            client_id=request.client_id,
            project_id=request.project_id,
            group_id=request.group_id,
        ):
            yield event
        yield VoiceStreamEvent(event="done", data={})
        return

    # COMPLEX_TASK → full orchestrator
    async for event in _handle_via_orchestrator(text, request):
        yield event


async def _handle_dictation(text: str, request: VoiceStreamRequest) -> AsyncIterator[VoiceStreamEvent]:
    """Store dictated text to KB."""
    yield VoiceStreamEvent(event="responding", data={})

    try:
        url = f"{settings.knowledgebase_url.rstrip('/')}/store"
        payload = {
            "content": text,
            "kind": "finding",
            "source_urn": f"agent://voice-dictation/{request.source}",
        }
        if request.client_id:
            payload["client_id"] = request.client_id
        if request.project_id:
            payload["project_id"] = request.project_id

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()

        yield VoiceStreamEvent(event="stored", data={"kind": "finding", "summary": text[:100]})
        yield VoiceStreamEvent(event="response", data={
            "text": f"Uloženo do znalostní báze: {text[:80]}...",
            "complete": True,
        })

    except Exception as e:
        logger.warning("Dictation store failed: %s", e)
        yield VoiceStreamEvent(event="response", data={
            "text": f"Chyba ukládání: {e}",
            "complete": True,
        })

    yield VoiceStreamEvent(event="done", data={})


async def generate_hint(text: str, client_id: str = "", project_id: str = "") -> str | None:
    """Generate a KB-based hint for live assist mode.

    Searches KB for relevant info and returns a concise hint.
    Returns None if nothing relevant found.
    """
    from app.voice.quick_responder import kb_search

    try:
        kb_results = await kb_search(text, client_id, project_id)
        if not kb_results:
            return None

        # Build concise hint from KB results (max 2 sentences)
        from app.llm.router_client import route_request
        import litellm

        route = await route_request(capability="chat", max_tier="FREE", estimated_tokens=300)
        model = f"openrouter/{route.model}" if route.target == "openrouter" and route.model else settings.default_local_model

        kb_context = "\n".join(r.get("content", "")[:200] for r in kb_results[:3])

        resp = await litellm.acompletion(
            model=model,
            messages=[
                {"role": "system", "content": "Jsi stručný asistent. Ze znalostní báze vyber nejrelevantnější info k tématu a odpověz MAX 2 věty česky. Pokud nic relevantního, odpověz prázdně."},
                {"role": "user", "content": f"Téma: {text}\n\nKB:\n{kb_context}"},
            ],
            temperature=0.0,
            max_tokens=150,
            api_base=route.api_base,
            api_key=route.api_key,
        )

        hint = resp.choices[0].message.content.strip()
        if hint and len(hint) > 5:
            return hint
        return None

    except Exception as e:
        logger.warning("Hint generation failed: %s", e)
        return None


async def _handle_via_orchestrator(text: str, request: VoiceStreamRequest) -> AsyncIterator[VoiceStreamEvent]:
    """Forward to full orchestrator chat pipeline.

    Creates a chat request and streams SSE events back.
    """
    yield VoiceStreamEvent(event="responding", data={})

    chat_url = f"http://localhost:{settings.port}/chat"

    chat_request = {
        "session_id": f"voice-{request.source}-{id(request)}",
        "message": text,
        "message_sequence": 1,
        "active_client_id": request.client_id,
        "active_project_id": request.project_id,
        "active_group_id": request.group_id,
        "max_openrouter_tier": "FREE",  # Voice always uses FREE for speed
    }

    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            async with client.stream("POST", chat_url, json=chat_request) as resp:
                resp.raise_for_status()
                current_event = ""
                current_data = ""

                async for line in resp.aiter_lines():
                    if line.startswith("event: "):
                        current_event = line.removeprefix("event: ").strip()
                    elif line.startswith("data: "):
                        current_data = line.removeprefix("data: ").strip()
                    elif not line.strip() and current_data:
                        # Map chat SSE events to voice SSE events
                        try:
                            data = json.loads(current_data)
                        except json.JSONDecodeError:
                            current_event = ""
                            current_data = ""
                            continue

                        content = data.get("content", "")

                        if current_event == "token" and content:
                            yield VoiceStreamEvent(event="token", data={"text": content})
                        elif current_event == "done":
                            yield VoiceStreamEvent(event="response", data={
                                "text": content,
                                "complete": True,
                            })
                        elif current_event == "error":
                            yield VoiceStreamEvent(event="error", data={"text": content})

                        current_event = ""
                        current_data = ""

    except Exception as e:
        logger.warning("Orchestrator forwarding failed: %s", e)
        yield VoiceStreamEvent(event="error", data={"text": f"Chyba orchestrátoru: {e}"})

    yield VoiceStreamEvent(event="done", data={})
