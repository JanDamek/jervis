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


def _parse_rate_limit_headers(error_text: str) -> tuple[int | None, str | None]:
    """Extract `X-RateLimit-Reset` (epoch ms) and the scope string from
    an OpenRouter 429 response body.

    OpenRouter returns 429 with a JSON envelope shaped like:
        {"error":{"message":"Rate limit exceeded: free-models-per-day-high-balance. ",
                   "code":429,
                   "metadata":{"headers":{"X-RateLimit-Limit":"2000",
                                           "X-RateLimit-Remaining":"0",
                                           "X-RateLimit-Reset":"1776902400000"}}}}

    Returns `(reset_epoch_ms, scope)`. Either may be None if absent.
    """
    try:
        data = json.loads(error_text)
    except (json.JSONDecodeError, ValueError):
        return None, None

    err = data.get("error") if isinstance(data, dict) else None
    if not isinstance(err, dict):
        return None, None

    headers = ((err.get("metadata") or {}).get("headers") or {})
    reset_raw = headers.get("X-RateLimit-Reset")
    reset_ms: int | None = None
    if reset_raw is not None:
        try:
            reset_ms = int(str(reset_raw))
        except (TypeError, ValueError):
            reset_ms = None

    # Scope lives in the free-form message ("Rate limit exceeded: <scope>.")
    scope: str | None = None
    msg = err.get("message")
    if isinstance(msg, str):
        lower = msg.lower()
        for marker in (
            "free-models-per-day-high-balance",
            "free-models-per-day",
            "per-day",
            "per-minute",
            "per-hour",
        ):
            if marker in lower:
                scope = marker
                break
    return reset_ms, scope


def _build_openai_body(body: dict, cloud_model: str) -> dict:
    raw_messages = body.get("messages", [])
    if not raw_messages and body.get("prompt"):
        # /api/generate-shape input — lift to a chat message.
        raw_messages = [{"role": "user", "content": body["prompt"]}]
        if body.get("system"):
            raw_messages.insert(0, {"role": "system", "content": body["system"]})

    messages: list[dict] = []
    for m in raw_messages:
        converted = dict(m)
        tc_list = converted.get("tool_calls")
        if isinstance(tc_list, list):
            converted["tool_calls"] = [_ollama_tc_to_openai(tc) for tc in tc_list]
        # Ollama `images` (list of base64 strings) → OpenAI multimodal content.
        images = converted.pop("images", None)
        if images and isinstance(images, list):
            converted["content"] = _compose_openai_multimodal(
                converted.get("content", ""), images,
            )
        messages.append(converted)

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


def _ollama_tc_to_openai(tc: dict) -> dict:
    """Convert an Ollama-shape tool_call (args: dict) to OpenAI (arguments:
    JSON string). Leaves already-stringified arguments alone."""
    fn = tc.get("function") or {}
    raw_args = fn.get("arguments")
    if isinstance(raw_args, (dict, list)):
        args_str = json.dumps(raw_args, ensure_ascii=False)
    else:
        args_str = str(raw_args) if raw_args is not None else ""
    return {
        "id": tc.get("id") or "",
        "type": tc.get("type", "function"),
        "function": {
            "name": fn.get("name", ""),
            "arguments": args_str,
        },
    }


def _compose_openai_multimodal(text: str, images_b64: list[str]) -> list[dict]:
    parts: list[dict] = []
    if text:
        parts.append({"type": "text", "text": text})
    for b64 in images_b64:
        parts.append({
            "type": "image_url",
            "image_url": {"url": f"data:image/jpeg;base64,{b64}"},
        })
    return parts


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
    # Counters help diagnose silent 200-OK streams (cloud accepted but
    # produced nothing / only keepalives / only error events).
    first_chunk_at: float | None = None
    chunks_with_content = 0
    keepalive_count = 0
    non_data_lines = 0
    error_payloads = 0
    total_content_bytes = 0
    finish_reason: str | None = None
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
                    if len(error_text) > 2000:
                        break
                logger.warning(
                    "OPENROUTER_PROXY: %s error %d: %s",
                    request_id, upstream.status_code, error_text[:200],
                )
                reset_ms: int | None = None
                scope: str | None = None
                if upstream.status_code == 429:
                    reset_ms, scope = _parse_rate_limit_headers(error_text)
                raise ProxyError(
                    "upstream_error",
                    status_code=upstream.status_code,
                    message=error_text[:500],
                    rate_limit_reset_epoch_ms=reset_ms,
                    rate_limit_scope=scope,
                )

            logger.info(
                "OPENROUTER_PROXY: %s connected model=%s (200 OK, waiting for stream)",
                request_id, cloud_model,
            )
            async for line in upstream.aiter_lines():
                # Warn once if we've been connected 15s without any content
                # chunk — means cloud accepted but produced nothing so far.
                if first_chunk_at is None and time.monotonic() - start > 15.0 and chunks_with_content == 0:
                    logger.warning(
                        "OPENROUTER_SILENT: %s model=%s no content chunk in %.1fs "
                        "(keepalives=%d non_data=%d)",
                        request_id, cloud_model, time.monotonic() - start,
                        keepalive_count, non_data_lines,
                    )
                raw = line
                line = line.strip()
                if not line:
                    # Blank SSE separator between events — routine, skip silently.
                    continue
                if line == "data: [DONE]":
                    # Normal end-of-stream marker.
                    continue
                # SSE comment line (e.g. `: OPENROUTER PROCESSING` keepalive).
                if line.startswith(":"):
                    keepalive_count += 1
                    if keepalive_count <= 3 or keepalive_count % 20 == 0:
                        logger.info(
                            "OPENROUTER_KEEPALIVE: %s n=%d t=%.1fs line=%r",
                            request_id, keepalive_count,
                            time.monotonic() - start, raw[:200],
                        )
                    continue
                if not line.startswith("data: "):
                    # Anything else — provider-specific preamble, error text,
                    # rate-limit notice, etc. Log so we can see what arrived.
                    non_data_lines += 1
                    logger.warning(
                        "OPENROUTER_NON_DATA_LINE: %s t=%.1fs line=%r",
                        request_id, time.monotonic() - start, raw[:300],
                    )
                    continue
                try:
                    chunk_data = json.loads(line[6:])
                except json.JSONDecodeError:
                    non_data_lines += 1
                    logger.warning(
                        "OPENROUTER_BAD_JSON: %s t=%.1fs line=%r",
                        request_id, time.monotonic() - start, raw[:300],
                    )
                    continue
                # OpenRouter sometimes returns {"error": {...}} inside a
                # 200 OK stream (rate-limit burst, provider downstream error).
                if isinstance(chunk_data, dict) and chunk_data.get("error"):
                    error_payloads += 1
                    logger.warning(
                        "OPENROUTER_STREAM_ERROR: %s t=%.1fs error=%r",
                        request_id, time.monotonic() - start,
                        chunk_data.get("error"),
                    )
                    continue
                choices = chunk_data.get("choices") or [{}]
                delta = choices[0].get("delta") or {}
                finish = choices[0].get("finish_reason")
                content = delta.get("content", "") or ""
                tool_calls = delta.get("tool_calls") or []

                if content or tool_calls:
                    if first_chunk_at is None:
                        first_chunk_at = time.monotonic()
                        logger.info(
                            "OPENROUTER_FIRST_CHUNK: %s model=%s ttft=%.2fs "
                            "content_preview=%r",
                            request_id, cloud_model,
                            first_chunk_at - start, content[:80],
                        )
                    chunks_with_content += 1
                    total_content_bytes += len(content)
                if finish:
                    finish_reason = finish
                    logger.info(
                        "OPENROUTER_FINISH: %s model=%s finish=%s chunks=%d "
                        "bytes=%d t=%.2fs",
                        request_id, cloud_model, finish,
                        chunks_with_content, total_content_bytes,
                        time.monotonic() - start,
                    )

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
            "OPENROUTER_PROXY: %s completed in %.2fs model=%s chunks=%d "
            "bytes=%d keepalives=%d non_data=%d errors=%d finish=%s",
            request_id, duration, cloud_model, chunks_with_content,
            total_content_bytes, keepalive_count, non_data_lines,
            error_payloads, finish_reason,
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
            reset_ms: int | None = None
            scope: str | None = None
            if resp.status_code == 429:
                reset_ms, scope = _parse_rate_limit_headers(resp.text)
            raise ProxyError(
                "upstream_error",
                status_code=resp.status_code,
                message=resp.text[:500],
                rate_limit_reset_epoch_ms=reset_ms,
                rate_limit_scope=scope,
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
