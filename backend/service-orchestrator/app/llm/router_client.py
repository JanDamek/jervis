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
    deadline_iso: str | None = None,
    priority: str = "NORMAL",
    min_model_size: int = 0,
    skip_models: list[str] | None = None,
    require_tools: bool = False,
    client_id: str | None = None,
) -> RouteDecision:
    """Ask router for routing decision. Urgency is carried by `deadline_iso` + `priority`;
    the router derives its internal bucket from them. See KB
    `agent://claude-code/task-routing-unified-design`.

    Args:
        capability: "thinking", "coding", "chat", "extraction", "embedding", "visual"
        max_tier: explicit tier override. Prefer client_id.
        estimated_tokens: estimated context size in tokens
        deadline_iso: absolute ISO-8601 deadline (e.g. from TaskDocument.deadline).
            None = no urgency pressure (router treats as BATCH).
        priority: "CASCADE" | "CRITICAL" | "NORMAL" — queue priority + REALTIME override.
        min_model_size: minimum local model size in billions (0 = any, 14, 30)
        skip_models: model IDs to skip (already tried and failed in this request)
        require_tools: if True, only models with supportsTools=True are eligible
        client_id: router resolves tier from client's CloudModelPolicy in DB

    Returns:
        RouteDecision with target, model, and optional api_base/api_key.
    """
    url = f"{_router_base_url()}/route-decision"
    try:
        payload: dict = {
            "capability": capability,
            "estimated_tokens": estimated_tokens,
            "priority": priority,
        }
        if deadline_iso:
            payload["deadline_iso"] = deadline_iso
        if min_model_size:
            payload["min_model_size"] = min_model_size
        if client_id:
            payload["client_id"] = client_id
        if max_tier and max_tier != "NONE":
            payload["max_tier"] = max_tier
        if skip_models:
            payload["skip_models"] = skip_models
        if require_tools:
            payload["require_tools"] = True
        # Timeout 90s: router waits max 65s for rate limit slot + 25s margin
        async with httpx.AsyncClient(timeout=90.0) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()
            target = data.get("target", "local")
            model = data.get("model")
            # Add litellm provider prefix: router returns raw model IDs,
            # litellm needs provider prefix (openrouter/, ollama/, etc.)
            if model and target == "openrouter" and not model.startswith("openrouter/"):
                model = f"openrouter/{model}"
            elif model and target == "local" and not model.startswith("ollama/"):
                model = f"ollama/{model}"
            return RouteDecision(
                target=target,
                model=model,
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


async def wait_for_any_model_recovery(
    max_tier: str = "NONE", max_wait_s: float = 20.0,
) -> dict | None:
    """Block until any cloud model in the tier becomes available again.

    Used as a last-resort step before falling back to local GPU when all
    cloud models in the queue are temporarily paused (429). Polls the
    router every second until a model recovers or max_wait_s elapses.

    Returns: {"model": "...", "waited": float_seconds} on success, None on timeout.
    """
    import asyncio
    import time

    deadline = time.monotonic() + max_wait_s
    poll_interval = 1.0

    while time.monotonic() < deadline:
        # Try a "soft" route request: if any model is available, router returns it.
        try:
            decision = await route_request(
                capability="chat",
                max_tier=max_tier,
                estimated_tokens=1000,  # tiny — just probing availability
            )
            if decision.target == "openrouter" and decision.model:
                waited = max_wait_s - (deadline - time.monotonic())
                return {"model": decision.model, "waited": waited}
        except Exception:
            pass
        await asyncio.sleep(poll_interval)

    return None


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
