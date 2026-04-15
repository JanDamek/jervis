"""LLM Router — route-decision aware LLM calls for KB write.

Asks ollama-router for routing decision based on client tier.
If OpenRouter → calls OpenRouter API. If local → calls Ollama as before.

NONE tier → always local GPU (no cloud).
FREE+ tier → first call gets GPU (if free), subsequent get OpenRouter (GPU busy).
"""

from __future__ import annotations

import base64
import logging
from dataclasses import dataclass

import httpx

from app.core.config import settings

logger = logging.getLogger(__name__)

# Reuse a single httpx client for route decisions (short timeout)
_route_http = httpx.AsyncClient(timeout=3.0)
# Reuse a single httpx client for LLM calls — žádný read timeout (LLM trvá jak trvá)
_llm_http = httpx.AsyncClient(timeout=httpx.Timeout(connect=10.0, read=None, write=10.0, pool=30.0))


@dataclass
class RouteDecision:
    target: str  # "local" or "openrouter"
    model: str | None = None
    api_base: str | None = None
    api_key: str | None = None


def _router_base_url() -> str:
    """Derive router base URL from OLLAMA_INGEST_BASE_URL."""
    return settings.OLLAMA_INGEST_BASE_URL.rstrip("/")


async def get_route(
    max_tier: str = "NONE",
    estimated_tokens: int = 8000,
    capability: str = "extraction",
    client_id: str | None = None,
) -> RouteDecision:
    """Ask router for routing decision. Router resolves tier from client_id."""
    # If no client_id and tier is NONE → always local, skip network call
    if not client_id and max_tier == "NONE":
        return RouteDecision(
            target="local",
            model=settings.INGEST_MODEL_COMPLEX,
            api_base=_router_base_url(),
        )

    url = f"{_router_base_url()}/route-decision"
    try:
        payload = {
            "capability": capability,
            "estimated_tokens": estimated_tokens,
            "processing_mode": "BACKGROUND",
        }
        if client_id:
            payload["client_id"] = client_id
        if max_tier and max_tier != "NONE":
            payload["max_tier"] = max_tier
        resp = await _route_http.post(url, json=payload)
        resp.raise_for_status()
        data = resp.json()
        return RouteDecision(
            target=data.get("target", "local"),
            model=data.get("model"),
            api_base=data.get("api_base"),
            api_key=data.get("api_key"),
        )
    except Exception as e:
        logger.warning("Route decision failed: %s — defaulting to local", e)
        return RouteDecision(
            target="local",
            model=settings.INGEST_MODEL_COMPLEX,
            api_base=_router_base_url(),
        )


async def llm_generate(
    prompt: str,
    max_tier: str = "NONE",
    model: str | None = None,
    num_ctx: int = 8192,
    priority: int | None = None,
    temperature: float = 0,
    format_json: bool = True,
    client_id: str | None = None,
) -> str:
    """Route-aware LLM generate call.

    1. Ask router for route decision (local vs OpenRouter)
    2. Call appropriate backend
    3. Return raw response text

    Args:
        prompt: The prompt text
        max_tier: Client's OpenRouter tier (NONE/FREE/PAID/PREMIUM)
        model: Override model name (for local Ollama). Ignored for OpenRouter.
        num_ctx: Context window size (for local Ollama)
        priority: X-Ollama-Priority header (0=CRITICAL, None=NORMAL)
        temperature: LLM temperature
        format_json: Request JSON output format
    """
    local_model = model or settings.INGEST_MODEL_COMPLEX

    # Estimate tokens from prompt length
    estimated_tokens = len(prompt) // 3 + num_ctx  # rough estimate

    route = await get_route(
        max_tier=max_tier,
        estimated_tokens=estimated_tokens,
        client_id=client_id,
    )

    if route.target == "openrouter" and route.api_key:
        return await _call_openrouter(prompt, route, temperature, format_json)
    else:
        return await _call_ollama(prompt, local_model, num_ctx, priority, temperature, format_json)


async def _call_ollama(
    prompt: str,
    model: str,
    num_ctx: int,
    priority: int | None,
    temperature: float,
    format_json: bool,
) -> str:
    """Call local Ollama via router with retry on 499 (GPU busy)."""
    import asyncio

    url = f"{_router_base_url()}/api/generate"
    headers = {}
    if priority is not None:
        headers["X-Ollama-Priority"] = str(priority)

    payload = {
        "model": model,
        "prompt": prompt,
        "stream": False,
        "options": {"temperature": temperature, "num_ctx": num_ctx},
    }
    if format_json:
        payload["format"] = "json"

    max_retries = 3
    for attempt in range(1 + max_retries):
        resp = await _llm_http.post(url, json=payload, headers=headers)
        if resp.status_code == 499 and attempt < max_retries:
            wait = 5 * (attempt + 1)  # 5s, 10s, 15s — give GPU time to free up
            logger.warning(
                "LLM_ROUTER: 499 GPU busy (attempt %d/%d, model=%s), retrying in %ds",
                attempt + 1, max_retries + 1, model, wait,
            )
            await asyncio.sleep(wait)
            continue
        resp.raise_for_status()
        return resp.json()["response"]


async def _call_openrouter(
    prompt: str,
    route: RouteDecision,
    temperature: float,
    format_json: bool,
) -> str:
    """Call OpenRouter API (OpenAI-compatible)."""
    url = "https://openrouter.ai/api/v1/chat/completions"
    headers = {
        "Authorization": f"Bearer {route.api_key}",
        "HTTP-Referer": "https://jervis.app",
        "Content-Type": "application/json",
    }

    messages = [{"role": "user", "content": prompt}]
    payload = {
        "model": route.model,
        "messages": messages,
        "temperature": temperature,
        "stream": False,
    }
    if format_json:
        payload["response_format"] = {"type": "json_object"}

    try:
        resp = await _llm_http.post(url, json=payload, headers=headers)
        resp.raise_for_status()
        data = resp.json()
        content = data["choices"][0]["message"]["content"]
        logger.info("OpenRouter LLM call: model=%s, tokens=%d/%d",
                     route.model,
                     data.get("usage", {}).get("prompt_tokens", 0),
                     data.get("usage", {}).get("completion_tokens", 0))
        return content
    except Exception as e:
        logger.warning("OpenRouter call failed (%s): %s — falling back to local", route.model, e)
        # Fallback to local on OpenRouter failure
        return await _call_ollama(
            prompt,
            settings.INGEST_MODEL_COMPLEX,
            8192, None, temperature, format_json,
        )


async def llm_generate_vision(
    image_bytes: bytes,
    prompt: str,
    max_tier: str = "NONE",
    priority: int | None = None,
    client_id: str | None = None,
) -> str:
    """Route-aware VLM call for image understanding.

    1. Ask router for route decision with capability="visual"
    2. Call appropriate backend (Ollama VLM or OpenRouter vision model)
    3. Return raw response text

    FAIL-FAST: retries 3x with exponential backoff (5s, 10s, 20s).
    No fallback to OCR — VLM is the only extraction path for images.
    """
    image_b64 = base64.b64encode(image_bytes).decode("utf-8")

    # Estimate tokens (image ~1k tokens + prompt)
    estimated_tokens = len(prompt) // 3 + 2000

    route = await get_route(
        max_tier=max_tier,
        estimated_tokens=estimated_tokens,
        capability="visual",
        client_id=client_id,
    )

    last_error = None
    for attempt in range(3):
        try:
            if route.target == "openrouter" and route.api_key:
                return await _call_openrouter_vision(image_b64, prompt, route)
            else:
                return await _call_ollama_vision(image_b64, prompt, priority)
        except Exception as e:
            last_error = e
            backoff = 5 * (2 ** attempt)  # 5s, 10s, 20s
            logger.warning(
                "VLM call failed (attempt %d/3): %s — retrying in %ds",
                attempt + 1, e, backoff,
            )
            import asyncio
            await asyncio.sleep(backoff)

    raise RuntimeError(f"VLM failed after 3 attempts: {last_error}")


async def _call_ollama_vision(
    image_b64: str,
    prompt: str,
    priority: int | None,
) -> str:
    """Call local Ollama VLM via router (/api/generate with images)."""
    url = f"{_router_base_url()}/api/generate"
    headers = {}
    if priority is not None:
        headers["X-Ollama-Priority"] = str(priority)

    payload = {
        "model": settings.VISION_MODEL,
        "prompt": prompt,
        "images": [image_b64],
        "stream": False,
    }

    resp = await _llm_http.post(url, json=payload, headers=headers)
    resp.raise_for_status()
    return resp.json()["response"]


async def _call_openrouter_vision(
    image_b64: str,
    prompt: str,
    route: RouteDecision,
) -> str:
    """Call OpenRouter VLM (OpenAI-compatible vision API)."""
    url = "https://openrouter.ai/api/v1/chat/completions"
    headers = {
        "Authorization": f"Bearer {route.api_key}",
        "HTTP-Referer": "https://jervis.app",
        "Content-Type": "application/json",
    }

    messages = [{
        "role": "user",
        "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}},
        ],
    }]
    payload = {
        "model": route.model,
        "messages": messages,
        "temperature": 0,
        "stream": False,
    }

    resp = await _llm_http.post(url, json=payload, headers=headers)
    resp.raise_for_status()
    data = resp.json()
    content = data["choices"][0]["message"]["content"]
    logger.info("OpenRouter VLM call: model=%s, tokens=%d/%d",
                 route.model,
                 data.get("usage", {}).get("prompt_tokens", 0),
                 data.get("usage", {}).get("completion_tokens", 0))
    return content
