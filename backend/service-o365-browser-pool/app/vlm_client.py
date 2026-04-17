"""VLM client — calls vision language model via ollama-router or OpenRouter.

Same routing pattern as document extractor in service-knowledgebase.
"""

from __future__ import annotations

import asyncio
import base64
import logging

import httpx

from app.config import settings

logger = logging.getLogger("o365-browser-pool.vlm")

# Retry backoff: 5s, 10s, 20s
_RETRY_DELAYS = [5, 10, 20]


async def analyze_screenshot(
    image_bytes: bytes,
    prompt: str,
    *,
    processing_mode: str = "BACKGROUND",
    max_tier: str = "FREE",
) -> str:
    """Send a screenshot to VLM and return the text analysis.

    Routes through ollama-router — router decides local GPU vs OpenRouter
    based on GPU availability, queue depth, and client tier.
    """
    image_b64 = base64.b64encode(image_bytes).decode()
    estimated_tokens = 1500 + len(prompt) // 4  # ~1k for image + prompt tokens

    route = await _get_route_decision(
        capability="vision",
        estimated_tokens=estimated_tokens,
        processing_mode=processing_mode,
        max_tier=max_tier,
    )

    target = route.get("target", "local")
    call_fn = _call_openrouter if target == "openrouter" else _call_ollama

    for attempt, delay in enumerate(_RETRY_DELAYS):
        try:
            return await call_fn(route, image_b64, prompt)
        except Exception as e:
            logger.warning("VLM attempt %d failed (%s): %s", attempt + 1, target, e)
            if attempt < len(_RETRY_DELAYS) - 1:
                await asyncio.sleep(delay)

    raise RuntimeError("VLM call failed after all retries")


async def _get_route_decision(
    capability: str,
    estimated_tokens: int,
    processing_mode: str,
    max_tier: str,
) -> dict:
    """Return the local-ollama route.

    The legacy `/router/admin/decide` HTTP endpoint was removed from the
    ollama-router. Pods now default to the local VLM; any cloud/OpenRouter
    routing happens transparently via the router's `/api/generate` proxy
    based on the model name and RequestContext (capability, max_tier).
    """
    return {
        "target": "local",
        "model": "qwen3-vl-tool:latest",
        "api_base": settings.ollama_router_url,
    }


async def _call_ollama(route: dict, image_b64: str, prompt: str) -> str:
    """Call local Ollama VLM."""
    api_base = route.get("api_base", settings.ollama_router_url)
    model = route.get("model", "qwen3-vl-tool:latest")

    async with httpx.AsyncClient(timeout=httpx.Timeout(connect=10.0, read=None, write=10.0, pool=30.0)) as client:
        resp = await client.post(
            f"{api_base}/api/generate",
            json={
                "model": model,
                "prompt": prompt,
                "images": [image_b64],
                "stream": False,
            },
        )
        resp.raise_for_status()
        data = resp.json()
        result = data.get("response", "")
        logger.info(
            "Ollama VLM: model=%s, response_len=%d",
            model, len(result),
        )
        return result


async def _call_openrouter(route: dict, image_b64: str, prompt: str) -> str:
    """Call OpenRouter VLM."""
    model = route.get("model", "")
    api_key = route.get("api_key", settings.openrouter_api_key)

    if not api_key:
        raise RuntimeError("No OpenRouter API key available")

    async with httpx.AsyncClient(timeout=httpx.Timeout(connect=10.0, read=None, write=10.0, pool=30.0)) as client:
        resp = await client.post(
            "https://openrouter.ai/api/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {api_key}",
                "HTTP-Referer": "https://jervis.app",
                "Content-Type": "application/json",
            },
            json={
                "model": model,
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": prompt},
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:image/jpeg;base64,{image_b64}",
                                },
                            },
                        ],
                    }
                ],
                "temperature": 0,
                "stream": False,
            },
        )
        resp.raise_for_status()
        data = resp.json()
        result = data["choices"][0]["message"]["content"]
        logger.info(
            "OpenRouter VLM: model=%s, tokens=%d/%d",
            model,
            data.get("usage", {}).get("prompt_tokens", 0),
            data.get("usage", {}).get("completion_tokens", 0),
        )
        return result
