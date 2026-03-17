"""Direct proxy to OpenRouter API for cascade routing.

Converts Ollama-format requests to OpenAI-compatible format and streams back.
Used by cascade_route() when GPU is busy and cloud model is available.
"""

from __future__ import annotations

import json
import logging
import time

import httpx
from starlette.responses import StreamingResponse, JSONResponse, Response

logger = logging.getLogger("ollama-router.openrouter-proxy")

OPENROUTER_BASE = "https://openrouter.ai/api/v1"


async def proxy_to_openrouter(
    api_path: str,
    body: dict,
    cloud_model: str,
    api_key: str,
    request_id: str = "",
) -> Response:
    """Proxy an Ollama-format request to OpenRouter.

    Converts Ollama /api/generate or /api/chat format to OpenAI /chat/completions.
    Streams response back in Ollama-compatible format.
    """
    # Build OpenAI-compatible request
    messages = body.get("messages", [])
    if not messages and body.get("prompt"):
        # /api/generate format → convert to chat
        messages = [{"role": "user", "content": body["prompt"]}]
        if body.get("system"):
            messages.insert(0, {"role": "system", "content": body["system"]})

    openai_body = {
        "model": cloud_model,
        "messages": messages,
        "stream": body.get("stream", True),
    }
    if body.get("options", {}).get("temperature") is not None:
        openai_body["temperature"] = body["options"]["temperature"]
    if body.get("options", {}).get("num_predict"):
        openai_body["max_tokens"] = body["options"]["num_predict"]

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://jervis.damek-soft.eu",
        "X-Title": "Jervis AI Assistant",
    }

    start = time.monotonic()
    is_streaming = openai_body.get("stream", True)

    if is_streaming:
        return await _stream_openrouter(openai_body, headers, request_id, cloud_model, start)
    else:
        return await _blocking_openrouter(openai_body, headers, request_id, cloud_model, start)


async def _stream_openrouter(
    body: dict, headers: dict, request_id: str, model: str, start: float,
) -> StreamingResponse:
    """Stream OpenRouter response, converting OpenAI SSE to Ollama NDJSON format."""

    async def generator():
        client = httpx.AsyncClient(timeout=httpx.Timeout(connect=10, read=300, write=10, pool=30))
        try:
            async with client.stream(
                "POST", f"{OPENROUTER_BASE}/chat/completions",
                json=body, headers=headers,
            ) as upstream:
                if upstream.status_code != 200:
                    error_text = ""
                    async for chunk in upstream.aiter_text():
                        error_text += chunk
                    logger.warning("OPENROUTER_PROXY: %s error %d: %s",
                                   request_id, upstream.status_code, error_text[:200])
                    yield (json.dumps({
                        "model": model,
                        "done": True,
                        "error": f"OpenRouter error {upstream.status_code}: {error_text[:200]}",
                    }) + "\n").encode()
                    return

                # Parse SSE stream → Ollama NDJSON
                async for line in upstream.aiter_lines():
                    line = line.strip()
                    if not line or line == "data: [DONE]":
                        continue
                    if line.startswith("data: "):
                        data_str = line[6:]
                        try:
                            chunk_data = json.loads(data_str)
                            delta = chunk_data.get("choices", [{}])[0].get("delta", {})
                            content = delta.get("content", "")
                            finish = chunk_data.get("choices", [{}])[0].get("finish_reason")

                            # Convert to Ollama format
                            ollama_chunk = {
                                "model": model,
                                "message": {"role": "assistant", "content": content},
                                "done": finish is not None,
                            }
                            if finish:
                                ollama_chunk["done_reason"] = finish
                            yield (json.dumps(ollama_chunk) + "\n").encode()
                        except (json.JSONDecodeError, IndexError, KeyError):
                            continue

                # Final done marker
                duration = time.monotonic() - start
                logger.info("OPENROUTER_PROXY: %s completed in %.2fs (model=%s)",
                            request_id, duration, model)
        except Exception as e:
            logger.error("OPENROUTER_PROXY: %s stream error: %s", request_id, e)
            yield (json.dumps({
                "model": model, "done": True,
                "error": f"OpenRouter stream error: {str(e)[:200]}",
            }) + "\n").encode()
        finally:
            await client.aclose()

    return StreamingResponse(generator(), media_type="application/x-ndjson")


async def _blocking_openrouter(
    body: dict, headers: dict, request_id: str, model: str, start: float,
) -> JSONResponse:
    """Non-streaming OpenRouter call, return Ollama-compatible JSON."""
    body["stream"] = False
    async with httpx.AsyncClient(timeout=httpx.Timeout(connect=10, read=120, write=10)) as client:
        try:
            resp = await client.post(
                f"{OPENROUTER_BASE}/chat/completions",
                json=body, headers=headers,
            )
            if resp.status_code != 200:
                return JSONResponse(
                    {"error": f"OpenRouter error {resp.status_code}: {resp.text[:200]}"},
                    status_code=resp.status_code,
                )
            data = resp.json()
            content = data.get("choices", [{}])[0].get("message", {}).get("content", "")
            duration = time.monotonic() - start
            logger.info("OPENROUTER_PROXY: %s blocking completed in %.2fs (model=%s)",
                        request_id, duration, model)
            return JSONResponse({
                "model": model,
                "message": {"role": "assistant", "content": content},
                "done": True,
            })
        except Exception as e:
            logger.error("OPENROUTER_PROXY: %s error: %s", request_id, e)
            return JSONResponse({"error": str(e)}, status_code=500)
