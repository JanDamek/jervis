"""Intent classifier for voice input.

Uses FREE OpenRouter model for sub-500ms classification of transcribed text.
Determines whether the voice input is a simple query (KB lookup),
complex task (full orchestrator), dictation (store), command, or noise.
"""

from __future__ import annotations

import json
import logging

import litellm

from app.config import settings
from app.llm.router_client import route_request
from app.voice.models import VoiceIntent, IntentResult

logger = logging.getLogger(__name__)

CLASSIFY_PROMPT = """You are an intent classifier for a voice assistant.
Classify the user's transcribed speech into exactly one category.

Categories:
- SIMPLE_QUERY: A question that can be answered from knowledge base or general knowledge. Examples: "Kolik dlužíme Alze?", "Jaký je stav projektu X?", "Kdy je schůzka?"
- COMPLEX_TASK: Requires multi-step work, coding, analysis, or creating something. Examples: "Napiš report o prodeji", "Oprav bug v přihlašování", "Naplánuj sprint"
- DICTATION: User is dictating text to store, not asking a question. Examples: "Poznámka: volal klient, chce slevu", "Zápis z jednání: dohodli jsme se na..."
- COMMAND: System command or quick action. Examples: "Nastav timer na 5 minut", "Připomeň mi zítra", "Spusť deployment"
- NOISE: No actionable content, background noise, partial words. Examples: "ehm", "tak", "no", single syllables

Respond with JSON only:
{"intent": "SIMPLE_QUERY|COMPLEX_TASK|DICTATION|COMMAND|NOISE", "confidence": 0.0-1.0, "query": "cleaned query text", "reason": "brief reason"}

User said: {text}"""


async def classify_intent(text: str, client_id: str | None = None) -> IntentResult:
    """Classify voice input intent using FREE OpenRouter model.

    Target: <500ms response time.
    """
    if not text or len(text.strip()) < 3:
        return IntentResult(
            intent=VoiceIntent.NOISE,
            confidence=0.95,
            extracted_query="",
            reason="Too short or empty",
        )

    try:
        route = await route_request(
            capability="chat",
            estimated_tokens=200,
            processing_mode="FOREGROUND",
            client_id=client_id,
        )

        model = route.model or "openrouter/auto"
        api_base = route.api_base
        api_key = route.api_key

        kwargs = {
            "model": model,
            "messages": [{"role": "user", "content": CLASSIFY_PROMPT.format(text=text)}],
            "temperature": 0.0,
            "max_tokens": 150,
        }
        if api_base:
            kwargs["api_base"] = api_base
        if api_key:
            kwargs["api_key"] = api_key

        response = await litellm.acompletion(**kwargs)
        content = response.choices[0].message.content.strip()

        # Parse JSON response
        # Handle markdown code blocks
        if content.startswith("```"):
            content = content.split("```")[1]
            if content.startswith("json"):
                content = content[4:]
            content = content.strip()

        data = json.loads(content)

        intent_str = data.get("intent", "SIMPLE_QUERY").upper()
        try:
            intent = VoiceIntent(intent_str.lower())
        except ValueError:
            intent = VoiceIntent.SIMPLE_QUERY

        return IntentResult(
            intent=intent,
            confidence=float(data.get("confidence", 0.7)),
            extracted_query=data.get("query", text),
            reason=data.get("reason", ""),
        )

    except Exception as e:
        logger.warning("Intent classification failed: %s — defaulting to SIMPLE_QUERY", e)
        return IntentResult(
            intent=VoiceIntent.SIMPLE_QUERY,
            confidence=0.5,
            extracted_query=text,
            reason=f"Classification error: {e}",
        )
