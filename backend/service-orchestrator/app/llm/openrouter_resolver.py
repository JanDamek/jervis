"""OpenRouter model resolver + GPU queue status for hybrid routing.

Implements Variant E routing decision:
1. autoUseOpenrouter=false → always local P40
2. estimated_tokens > 48k → OpenRouter directly (P40 would spill)
3. P40 free → use it (free, fast)
4. CRITICAL + P40 busy → OpenRouter (chat never waits)
5. NORMAL + P40 busy + free model → OpenRouter free tier
6. NORMAL + P40 busy → queue on P40 (background waits)
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass

import httpx

from app.config import settings
from app.models import ModelTier

logger = logging.getLogger(__name__)

# Cache for queue status (short TTL — routing decisions need fresh data)
_queue_status_cache: dict | None = None
_queue_status_ts: float = 0.0
_QUEUE_STATUS_TTL = 2.0  # seconds


@dataclass
class Route:
    """Routing decision result."""
    target: str          # "local" or "openrouter"
    tier: ModelTier | None = None    # For local target
    model: str | None = None         # For openrouter target (e.g. "anthropic/claude-sonnet-4")


async def get_router_queue_status() -> dict:
    """Fetch queue status from ollama-router /queue-status endpoint.

    Returns: {"critical_queue": int, "normal_queue": int, "gpu_free": [...], "gpu_busy": [...]}
    Cached for 2 seconds to avoid hammering the router.
    """
    global _queue_status_cache, _queue_status_ts

    now = time.monotonic()
    if _queue_status_cache is not None and (now - _queue_status_ts) < _QUEUE_STATUS_TTL:
        return _queue_status_cache

    router_base = settings.ollama_url.rstrip("/")
    # Router runs on same host as Ollama proxy; /queue-status is on the router port
    queue_url = router_base.replace("/v1", "").replace("/api", "") + "/queue-status"

    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.get(queue_url)
            resp.raise_for_status()
            _queue_status_cache = resp.json()
            _queue_status_ts = now
            return _queue_status_cache
    except Exception as e:
        logger.warning("Failed to fetch queue status from %s: %s", queue_url, e)
        # Fallback: assume GPU is busy (safer — will route to OpenRouter if available)
        return {"critical_queue": 0, "normal_queue": 0, "gpu_free": [], "gpu_busy": ["unknown"]}


async def resolve_openrouter_model(
    use_case: str = "chat",
    free_only: bool = False,
) -> str | None:
    """Resolve OpenRouter model from settings.

    For now uses the configured OpenRouter settings.
    TODO: In a future iteration, fetch per-client/project settings via kotlin_client.
    """
    # Check if OpenRouter API key is configured
    if not settings.openrouter_api_key:
        return None

    if free_only:
        # Free tier models for background tasks
        return "qwen/qwen3-30b-a3b:free"

    # Use case based model selection
    if use_case == "chat":
        return settings.default_cloud_model or "anthropic/claude-sonnet-4"
    elif use_case == "coding":
        return settings.default_openai_model or "openai/gpt-4o"
    elif use_case == "large_context":
        return settings.default_large_context_model or "google/gemini-2.5-flash"

    return settings.default_cloud_model or "anthropic/claude-sonnet-4"


def _select_local_tier(estimated_tokens: int) -> ModelTier:
    """Select local tier based on context size (same logic as EscalationPolicy)."""
    vram_boundary = settings.gpu_vram_token_boundary
    if estimated_tokens > 128_000:
        return ModelTier.LOCAL_XXLARGE
    if estimated_tokens > vram_boundary:
        return ModelTier.LOCAL_XLARGE
    if estimated_tokens > 32_000:
        return ModelTier.LOCAL_LARGE
    if estimated_tokens > 8_000:
        return ModelTier.LOCAL_STANDARD
    return ModelTier.LOCAL_FAST


async def select_route(
    estimated_tokens: int,
    priority: str = "CRITICAL",
    has_openrouter: bool = False,
    use_case: str = "chat",
) -> Route:
    """Hybrid routing decision (Variant E).

    Args:
        estimated_tokens: Estimated context size in tokens.
        priority: "CRITICAL" (chat) or "NORMAL" (background) or "IDLE".
        has_openrouter: Whether client/project has autoUseOpenrouter enabled.
        use_case: "chat", "coding", "large_context", "orchestrator".

    Returns:
        Route with target ("local" or "openrouter") and model/tier info.
    """
    # Rule 1: No OpenRouter → always local
    if not has_openrouter:
        return Route(target="local", tier=_select_local_tier(estimated_tokens))

    # Rule 2: Large context → OpenRouter directly (P40 would spill to CPU, slow)
    if estimated_tokens > 48_000:
        model = await resolve_openrouter_model(use_case)
        if model:
            logger.info("Route: large context (%d tokens) → OpenRouter %s", estimated_tokens, model)
            return Route(target="openrouter", model=model)

    # Rule 3: Check GPU availability
    queue_status = await get_router_queue_status()
    gpu_free = len(queue_status.get("gpu_free", [])) > 0

    # Rule 4: GPU free → use it (free, fast)
    if gpu_free:
        return Route(target="local", tier=_select_local_tier(estimated_tokens))

    # Rule 5: CRITICAL + GPU busy → OpenRouter (chat never waits)
    if priority == "CRITICAL":
        model = await resolve_openrouter_model(use_case)
        if model:
            logger.info("Route: CRITICAL + GPU busy → OpenRouter %s", model)
            return Route(target="openrouter", model=model)

    # Rule 6: NORMAL + GPU busy → try free tier first
    if priority == "NORMAL":
        free_model = await resolve_openrouter_model(use_case, free_only=True)
        if free_model:
            logger.info("Route: NORMAL + GPU busy → OpenRouter free %s", free_model)
            return Route(target="openrouter", model=free_model)

    # Rule 7: Fallback → queue on local GPU (background waits)
    return Route(target="local", tier=_select_local_tier(estimated_tokens))
