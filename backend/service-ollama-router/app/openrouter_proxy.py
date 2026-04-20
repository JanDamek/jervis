"""Direct proxy to OpenRouter API for cascade routing.

Translates Ollama-style bodies (built by the gRPC inference servicer)
to OpenAI `/chat/completions` format and yields Ollama-shaped chunks
back (or a single dict for non-streaming). The router's input surface
is gRPC — nothing in this module is HTTP-visible outside the pod.
"""

from __future__ import annotations

import json
import logging
import time
from typing import AsyncIterator

import httpx

from .proxy import ProxyError

logger = logging.getLogger("ollama-router.openrouter-proxy")

OPENROUTER_BASE = "https://openrouter.ai/api/v1"


def _build_openai_body(body: dict, cloud_model: str) -> dict:
    messages = body.get("messages", [])
    if not messages and body.get("prompt"):
        # /api/generate-shape input — lift to a chat message.
        messages = [{"role": "user", "content": body["prompt"]}]
        if body.get("system"):
            messages.insert(0, {"role": "system", "content": body["system"]})

    openai_body: dict = {
        "model": cloud_model,
        "messages": messages,
        "stream": body.get("stream", True),
    }
    opts = body.get("options") or {}
    if opts.get("temperature") is not None:
        openai_body["temperature"] = opts["temperature"]
    if opts.get("num_predict"):
        openai_body["max_tokens"] = opts["num_predict"]
    if body.get("tools"):
        openai_body["tools"] = body["tools"]
    return openai_body


def _headers(api_key: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://jervis.damek-soft.eu",
        "X-Title": "Jervis AI Assistant",
    }


async def stream_openrouter(
    body: dict,
    cloud_model: str,
    api_key: str,
    request_id: str = "",
) -> AsyncIterator[dict]:
    """Server-sent events from OpenRouter → Ollama-shape dict chunks."""

    openai_body = _build_openai_body(body, cloud_model)
    openai_body["stream"] = True

    start = time.monotonic()
    client = httpx.AsyncClient(
        timeout=httpx.Timeout(connect=10, read=None, write=10, pool=30),
    )
    try:
        async with client.stream(
            "POST", f"{OPENROUTER_BASE}/chat/completions",
            json=openai_body, headers=_headers(api_key),
        ) as upstream:
            if upstream.status_code != 200:
                error_text = ""
                async for chunk in upstream.aiter_text():
                    error_text += chunk
                    if len(error_text) > 800:
                        break
                logger.warning(
                    "OPENROUTER_PROXY: %s error %d: %s",
                    request_id, upstream.status_code, error_text[:200],
                )
                raise ProxyError(
                    "upstream_error",
                    status_code=upstream.status_code,
                    message=error_text[:500],
                )

            async for line in upstream.aiter_lines():
                line = line.strip()
                if not line or line == "data: [DONE]":
                    continue
                if not line.startswith("data: "):
                    continue
                try:
                    chunk_data = json.loads(line[6:])
                except json.JSONDecodeError:
                    continue
                choices = chunk_data.get("choices") or [{}]
                delta = choices[0].get("delta") or {}
                finish = choices[0].get("finish_reason")
                content = delta.get("content", "") or ""
                tool_calls = delta.get("tool_calls") or []

                out: dict = {
                    "model": cloud_model,
                    "message": {"role": "assistant", "content": content},
                    "done": finish is not None,
                }
                if tool_calls:
                    out["message"]["tool_calls"] = tool_calls
                if finish:
                    out["done_reason"] = finish
                yield out

        duration = time.monotonic() - start
        logger.info(
            "OPENROUTER_PROXY: %s completed in %.2fs (model=%s)",
            request_id, duration, cloud_model,
        )
    finally:
        await client.aclose()


async def call_openrouter(
    body: dict,
    cloud_model: str,
    api_key: str,
    request_id: str = "",
) -> dict:
    """Non-streaming OpenRouter call, return Ollama-shape dict."""

    openai_body = _build_openai_body(body, cloud_model)
    openai_body["stream"] = False

    start = time.monotonic()
    async with httpx.AsyncClient(
        timeout=httpx.Timeout(connect=10, read=120, write=10),
    ) as client:
        resp = await client.post(
            f"{OPENROUTER_BASE}/chat/completions",
            json=openai_body, headers=_headers(api_key),
        )
        if resp.status_code != 200:
            raise ProxyError(
                "upstream_error",
                status_code=resp.status_code,
                message=resp.text[:500],
            )
        data = resp.json()
        choice = (data.get("choices") or [{}])[0]
        message = choice.get("message") or {}
        content = message.get("content", "") or ""
        tool_calls = message.get("tool_calls") or []

        duration = time.monotonic() - start
        logger.info(
            "OPENROUTER_PROXY: %s blocking completed in %.2fs (model=%s)",
            request_id, duration, cloud_model,
        )
        out: dict = {
            "model": cloud_model,
            "message": {"role": "assistant", "content": content},
            "done": True,
        }
        if tool_calls:
            out["message"]["tool_calls"] = tool_calls
        finish = choice.get("finish_reason")
        if finish:
            out["done_reason"] = finish
        usage = data.get("usage") or {}
        if usage:
            if "prompt_tokens" in usage:
                out["prompt_eval_count"] = usage["prompt_tokens"]
            if "completion_tokens" in usage:
                out["eval_count"] = usage["completion_tokens"]
        return out
