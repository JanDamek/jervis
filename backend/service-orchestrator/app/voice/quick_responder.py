"""Quick responder for simple voice queries.

Bypasses the full orchestrator for simple KB lookups.
Uses FREE OpenRouter model for sub-3s total latency.
Pipeline: KB search → FREE LLM answer → optional TTS.
"""

from __future__ import annotations

import json
import logging
from typing import AsyncIterator

import httpx

from app.config import settings
from app.voice.models import VoiceStreamEvent

logger = logging.getLogger(__name__)


def _router_chat_url() -> str:
    base = settings.ollama_url.rstrip("/").replace("/v1", "").replace("/api", "")
    return f"{base}/api/chat"

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

    # Step 2: Stream response via router /api/chat — capability + client
    # is the whole routing signal; router resolves tier and picks a model.
    yield VoiceStreamEvent(event="responding", data={})

    body = {
        "messages": [{"role": "user", "content": QUICK_ANSWER_PROMPT.format(context=context, query=query)}],
        "stream": True,
        "options": {"temperature": 0.3, "num_predict": 300},
    }
    headers = {
        "Content-Type": "application/json",
        "X-Capability": "chat",
    }
    if client_id:
        headers["X-Client-Id"] = client_id

    response_text = ""
    try:
        timeout = httpx.Timeout(connect=5, read=None, write=5, pool=10)
        async with httpx.AsyncClient(timeout=timeout) as client:
            async with client.stream(
                "POST", _router_chat_url(), json=body, headers=headers,
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
        logger.warning("Quick LLM response failed: %s", e)
        response_text = f"Omlouvám se, nepodařilo se vygenerovat odpověď: {e}"
        yield VoiceStreamEvent(event="token", data={"text": response_text})

    yield VoiceStreamEvent(event="response", data={"text": response_text, "complete": True})
