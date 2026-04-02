"""Router client — asks the ollama-router for routing decisions.

Replaces openrouter_resolver.py. All routing logic is now in the router;
the orchestrator only declares what capability it needs and the router
decides local vs cloud.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


@dataclass
class RouteDecision:
    """Routing decision from the router."""
    target: str          # "local" or "openrouter"
    model: str | None = None
    api_base: str | None = None
    api_key: str | None = None  # OpenRouter API key (from DB settings via router)


def _router_base_url() -> str:
    """Derive router base URL from settings.ollama_url."""
    return settings.ollama_url.rstrip("/").replace("/v1", "").replace("/api", "")


async def route_request(
    capability: str = "chat",
    max_tier: str = "NONE",
    estimated_tokens: int = 0,
    processing_mode: str = "FOREGROUND",
    skip_models: list[str] | None = None,
    require_tools: bool = False,
) -> RouteDecision:
    """Ask router for routing decision based on capability.

    Args:
        capability: "thinking", "coding", "chat", "embedding", "visual"
        max_tier: "NONE", "FREE", "PAID", "PREMIUM"
        estimated_tokens: estimated context size in tokens
        processing_mode: "FOREGROUND" (chat → always cloud) or "BACKGROUND" (local, cloud >48k)
        skip_models: model IDs to skip (already tried and failed in this request)
        require_tools: if True, only models with supportsTools=True are eligible

    Returns:
        RouteDecision with target, model, and optional api_base.
    """
    url = f"{_router_base_url()}/route-decision"
    try:
        payload = {
            "capability": capability,
            "max_tier": max_tier,
            "estimated_tokens": estimated_tokens,
            "processing_mode": processing_mode,
        }
        if skip_models:
            payload["skip_models"] = skip_models
        if require_tools:
            payload["require_tools"] = True
        # Timeout 90s: router waits max 65s for rate limit slot + 25s margin
        async with httpx.AsyncClient(timeout=90.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
            return RouteDecision(
                target=data.get("target", "local"),
                model=data.get("model"),
                api_base=data.get("api_base"),
                api_key=data.get("api_key"),
            )
    except Exception as e:
        logger.warning("Failed to get route decision from %s: %s — defaulting to local", url, e)
        return RouteDecision(
            target="local",
            model=settings.default_local_model,
            api_base=settings.ollama_url,
        )


async def report_model_error(model_id: str, error_message: str = "") -> dict:
    """Report a model error to router. Returns error state."""
    url = f"{_router_base_url()}/route-decision/model-error"
    try:
        payload = {"model_id": model_id}
        if error_message:
            payload["error_message"] = error_message[:500]
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            return resp.json()
    except Exception as e:
        logger.warning("Failed to report model error: %s", e)
        return {}


async def report_model_success(
    model_id: str, duration_s: float = 0.0,
    input_tokens: int = 0, output_tokens: int = 0,
) -> None:
    """Report successful model call to router (resets error counter + records stats)."""
    url = f"{_router_base_url()}/route-decision/model-success"
    try:
        payload: dict = {"model_id": model_id}
        if duration_s > 0:
            payload["duration_s"] = round(duration_s, 2)
        if input_tokens > 0:
            payload["input_tokens"] = input_tokens
        if output_tokens > 0:
            payload["output_tokens"] = output_tokens
        async with httpx.AsyncClient(timeout=3.0) as client:
            await client.post(url, json=payload)
    except Exception as e:
        logger.warning("Failed to report model success: %s", e)


async def get_max_context_tokens(max_tier: str = "NONE") -> int:
    """Ask router for max context tokens available for a given tier.

    Used to know when context needs chunking (via Gemini direct call).
    """
    url = f"{_router_base_url()}/route-decision/max-context?max_tier={max_tier}"
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            return resp.json().get("max_context_tokens", 48_000)
    except Exception as e:
        logger.warning("Failed to get max context tokens: %s — defaulting to 48k", e)
        return 48_000
