"""VLM client — calls vision language model via ollama-router.

Uses /route-decision endpoint to let the router choose the model
based on capability and client/project tier.
"""

from __future__ import annotations

import asyncio
import base64
import logging

import httpx

from app.config import settings

logger = logging.getLogger("whatsapp-browser.vlm")

_RETRY_DELAYS = [5, 10, 20]


async def analyze_screenshot(
    image_bytes: bytes,
    prompt: str,
    *,
    processing_mode: str,
    max_tier: str,
) -> str:
    """Send a screenshot to VLM and return the text analysis.

    Routes through ollama-router for model selection.

    Args:
        image_bytes: JPEG/PNG image content.
        prompt: VLM prompt.
        processing_mode: REQUIRED. "FOREGROUND" or "BACKGROUND".
        max_tier: REQUIRED. "NONE", "FREE", "PAID", or "PREMIUM".
            Determined by client/project tier policy on Kotlin server side
            and passed via scrape trigger request body.
    """
    image_b64 = base64.b64encode(image_bytes).decode()
    estimated_tokens = 1500 + len(prompt) // 4  # ~1k for image + prompt tokens

    route = await _get_route_decision(
        capability="visual",
        estimated_tokens=estimated_tokens,
        processing_mode=processing_mode,
        max_tier=max_tier,
    )

    for attempt, delay in enumerate(_RETRY_DELAYS):
        try:
            return await _call_ollama(route, image_b64, prompt)
        except Exception as e:
            logger.warning(
                "VLM call attempt %d failed: %s: %s (route=%s)",
                attempt + 1, type(e).__name__, str(e) or repr(e), route,
            )
            if attempt < len(_RETRY_DELAYS) - 1:
                await asyncio.sleep(delay)

    raise RuntimeError("VLM call failed after all retries")


async def _get_route_decision(
    capability: str,
    estimated_tokens: int,
    processing_mode: str,
    max_tier: str,
) -> dict:
    """Ask ollama-router for routing decision."""
    async with httpx.AsyncClient(timeout=10) as client:
        try:
            resp = await client.post(
                f"{settings.ollama_router_url}/route-decision",
                json={
                    "capability": capability,
                    "estimated_tokens": estimated_tokens,
                    "processing_mode": processing_mode,
                    "max_tier": max_tier,
                },
            )
            if resp.status_code == 200:
                return resp.json()
        except Exception as e:
            logger.warning("Route decision failed: %s", e)

    # Fallback: let router pick via /api/generate (model in request is just a hint)
    return {
        "target": "local",
        "api_base": settings.ollama_router_url,
    }


async def _call_ollama(route: dict, image_b64: str, prompt: str) -> str:
    """Call Ollama VLM via router."""
    api_base = route.get("api_base", settings.ollama_router_url)
    model = route.get("model", "")

    request_body = {
        "prompt": prompt,
        "images": [image_b64],
        "stream": False,
    }
    # Only include model if router specified one
    if model:
        request_body["model"] = model

    # No timeout — router controls concurrency, never hard-timeout VLM calls
    async with httpx.AsyncClient(timeout=httpx.Timeout(None, connect=10.0)) as client:
        resp = await client.post(
            f"{api_base}/api/generate",
            json=request_body,
        )
        resp.raise_for_status()
        data = resp.json()
        result = data.get("response", "")
        logger.info(
            "Ollama VLM: model=%s, response_len=%d",
            route.get("model", "router-selected"), len(result),
        )
        return result
