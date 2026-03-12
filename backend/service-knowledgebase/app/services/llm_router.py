"""LLM Router — route-decision aware LLM calls for KB write.

Asks ollama-router for routing decision based on client tier.
If OpenRouter → calls OpenRouter API. If local → calls Ollama as before.

NONE tier → always local GPU (no cloud).
FREE+ tier → first call gets GPU (if free), subsequent get OpenRouter (GPU busy).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import httpx

from app.core.config import settings

logger = logging.getLogger(__name__)

# Reuse a single httpx client for route decisions (short timeout)
_route_http = httpx.AsyncClient(timeout=3.0)
# Reuse a single httpx client for LLM calls (long timeout)
_llm_http = httpx.AsyncClient(timeout=settings.LLM_CALL_TIMEOUT)


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
) -> RouteDecision:
    """Ask router for routing decision."""
    if max_tier == "NONE":
        # Skip network call — always local for NONE
        return RouteDecision(
            target="local",
            model=settings.INGEST_MODEL_COMPLEX,
            api_base=_router_base_url(),
        )

    url = f"{_router_base_url()}/route-decision"
    try:
        resp = await _route_http.post(url, json={
            "capability": capability,
            "max_tier": max_tier,
            "estimated_tokens": estimated_tokens,
            "processing_mode": "BACKGROUND",
        })
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
    """Call local Ollama via router."""
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

    resp = await _llm_http.post(url, json=payload, headers=headers)
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
