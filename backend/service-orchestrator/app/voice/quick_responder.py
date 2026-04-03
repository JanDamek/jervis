"""Quick responder for simple voice queries.

Bypasses the full orchestrator for simple KB lookups.
Uses FREE OpenRouter model for sub-3s total latency.
Pipeline: KB search → FREE LLM answer → optional TTS.
"""

from __future__ import annotations

import logging
from typing import AsyncIterator

import httpx
import litellm

from app.config import settings
from app.llm.router_client import route_request
from app.voice.models import VoiceStreamEvent

logger = logging.getLogger(__name__)

QUICK_ANSWER_PROMPT = """You are Jervis, a Czech AI assistant. Answer the question briefly and directly.
Use the provided knowledge base context if relevant. Always respond in Czech.
Keep the answer under 3 sentences — this is a voice response that will be read aloud.

Knowledge base context:
{context}

Question: {query}"""


async def kb_search(query: str, client_id: str | None, project_id: str | None, group_id: str | None) -> str:
    """Search KB for relevant context. Returns concatenated results."""
    url = f"{settings.knowledgebase_url.rstrip('/')}/api/v1/retrieve"
    try:
        payload = {
            "query": query,
            "top_k": 5,
        }
        if client_id:
            payload["client_id"] = client_id
        if project_id:
            payload["project_id"] = project_id

        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()

        results = data.get("items", data.get("evidence", []))
        if not results:
            return "(no relevant results found)"

        context_parts = []
        for r in results[:5]:
            content = r.get("content", "")[:500]
            source = r.get("sourceUrn", r.get("source_urn", r.get("source", "")))
            score = r.get("score", 0)
            context_parts.append(f"[{score:.0%}] {source}: {content}")

        return "\n---\n".join(context_parts)

    except Exception as e:
        logger.warning("KB search failed: %s", e)
        return "(KB search unavailable)"


async def quick_respond(
    query: str,
    client_id: str | None = None,
    project_id: str | None = None,
    group_id: str | None = None,
) -> AsyncIterator[VoiceStreamEvent]:
    """Generate a quick response using KB + FREE LLM.

    Yields SSE events: preliminary_answer, token, response, done.
    Target: <3s total latency.
    """

    # Step 1: KB search
    yield VoiceStreamEvent(event="preliminary_answer", data={"text": "Hledám v KB...", "confidence": 0.3})

    context = await kb_search(query, client_id, project_id, group_id)

    if "(no relevant results found)" not in context:
        # Show preliminary answer from first KB result
        first_result = context.split("\n---\n")[0] if context else ""
        if first_result:
            yield VoiceStreamEvent(event="preliminary_answer", data={
                "text": first_result[:200],
                "confidence": 0.6,
            })

    # Step 2: Route to FREE model
    route = await route_request(
        capability="chat",
        max_tier="FREE",
        estimated_tokens=500,
        processing_mode="FOREGROUND",
    )

    model = route.model or "openrouter/auto"

    kwargs = {
        "model": model,
        "messages": [{"role": "user", "content": QUICK_ANSWER_PROMPT.format(context=context, query=query)}],
        "temperature": 0.3,
        "max_tokens": 300,
        "stream": True,
    }
    if route.api_base:
        kwargs["api_base"] = route.api_base
    if route.api_key:
        kwargs["api_key"] = route.api_key

    # Step 3: Stream response tokens
    yield VoiceStreamEvent(event="responding", data={})

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
        logger.warning("Quick LLM response failed: %s", e)
        response_text = f"Omlouvám se, nepodařilo se vygenerovat odpověď: {e}"
        yield VoiceStreamEvent(event="token", data={"text": response_text})

    yield VoiceStreamEvent(event="response", data={"text": response_text, "complete": True})
