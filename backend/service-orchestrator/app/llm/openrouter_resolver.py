"""OpenRouter queue-based model resolver + GPU queue status.

4 named queues:
- FREE: local GPU + free cloud models (automatic fallback)
- PAID_LOW: standard paid models (Haiku, GPT-4o-mini)
- PAID_HIGH: thinking/reasoning models (Sonnet, o3-mini)
- LARGE_CONTEXT: models with >48k context (Gemini, Sonnet)

Routing logic:
1. max_tier == "NONE" → always local (wait for GPU)
2. estimated_tokens > 48k → LARGE_CONTEXT queue (if max_tier >= "PAID_LOW")
3. GPU free → local (LOCAL_STANDARD, 48k)
4. GPU busy → iterate queues by max_tier level
5. Fallback: wait for local GPU
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass

import httpx

from app.config import settings
from app.models import ModelTier

logger = logging.getLogger(__name__)

# Tier ordering for comparison
_TIER_LEVELS = {"NONE": 0, "FREE": 1, "PAID_LOW": 2, "PAID_HIGH": 3}

# ── GPU queue status cache ──────────────────────────────────────────────

_queue_status_cache: dict | None = None
_queue_status_ts: float = 0.0
_QUEUE_STATUS_TTL = 2.0  # seconds


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
        return {"critical_queue": 0, "normal_queue": 0, "gpu_free": [], "gpu_busy": ["unknown"]}


# ── OpenRouter settings cache ──────────────────────────────────────────

_openrouter_settings_cache: dict | None = None
_openrouter_settings_ts: float = 0.0
_OPENROUTER_SETTINGS_TTL = 60.0  # 1 minute


async def _fetch_openrouter_settings() -> dict | None:
    """Fetch OpenRouter settings from Kotlin server (cached 60s)."""
    global _openrouter_settings_cache, _openrouter_settings_ts

    now = time.monotonic()
    if _openrouter_settings_cache is not None and (now - _openrouter_settings_ts) < _OPENROUTER_SETTINGS_TTL:
        return _openrouter_settings_cache

    kotlin_url = settings.kotlin_server_url.rstrip("/")
    url = f"{kotlin_url}/internal/openrouter-settings"

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            _openrouter_settings_cache = resp.json()
            _openrouter_settings_ts = now
            logger.debug("Fetched OpenRouter settings: %d queues, %d models",
                         len(_openrouter_settings_cache.get("modelQueues", [])),
                         len(_openrouter_settings_cache.get("models", [])))
            return _openrouter_settings_cache
    except Exception as e:
        logger.warning("Failed to fetch OpenRouter settings from %s: %s", url, e)
        return _openrouter_settings_cache  # Return stale cache if available


# ── Default queues (used when Kotlin settings are not available) ────────

_DEFAULT_QUEUES: dict[str, list[dict]] = {
    "FREE": [
        {"modelId": "p40", "isLocal": True, "maxContextTokens": 48_000},
        {"modelId": "qwen/qwen3-30b-a3b:free", "isLocal": False, "maxContextTokens": 32_000},
    ],
    "PAID_LOW": [
        {"modelId": "p40", "isLocal": True, "maxContextTokens": 48_000},
        {"modelId": "anthropic/claude-haiku-4", "isLocal": False, "maxContextTokens": 200_000},
        {"modelId": "openai/gpt-4o-mini", "isLocal": False, "maxContextTokens": 128_000},
    ],
    "PAID_HIGH": [
        {"modelId": "p40", "isLocal": True, "maxContextTokens": 48_000},
        {"modelId": "anthropic/claude-sonnet-4", "isLocal": False, "maxContextTokens": 200_000},
        {"modelId": "openai/o3-mini", "isLocal": False, "maxContextTokens": 200_000},
    ],
    "LARGE_CONTEXT": [
        {"modelId": "google/gemini-2.5-pro", "isLocal": False, "maxContextTokens": 1_000_000},
        {"modelId": "anthropic/claude-sonnet-4", "isLocal": False, "maxContextTokens": 200_000},
    ],
}


async def _get_queue(queue_name: str) -> list[dict]:
    """Get model queue by name from settings, falling back to defaults."""
    or_settings = await _fetch_openrouter_settings()
    if or_settings:
        for q in or_settings.get("modelQueues", []):
            if q.get("name") == queue_name and q.get("enabled", True):
                models = [m for m in q.get("models", []) if m.get("enabled", True)]
                if models:
                    return models
    return _DEFAULT_QUEUES.get(queue_name, _DEFAULT_QUEUES["FREE"])


# ── Route dataclass ────────────────────────────────────────────────────

@dataclass
class Route:
    """Routing decision result."""
    target: str          # "local" or "openrouter"
    tier: ModelTier | None = None    # For local target
    model: str | None = None         # For openrouter target


# ── Main routing decision ──────────────────────────────────────────────

async def select_route(
    estimated_tokens: int,
    max_tier: str = "NONE",
    priority: str = "CRITICAL",
) -> Route:
    """Queue-based routing decision with tiered OpenRouter fallback.

    Args:
        estimated_tokens: Estimated context size in tokens.
        max_tier: Maximum OpenRouter tier allowed.
            "NONE" = local only (wait for GPU).
            "FREE" = free cloud models only.
            "PAID_LOW" = standard paid models.
            "PAID_HIGH" = thinking/reasoning models.
        priority: "CRITICAL" (chat) or "NORMAL" (background).

    Returns:
        Route with target ("local" or "openrouter") and model/tier info.
    """
    tier_level = _TIER_LEVELS.get(max_tier, 0)

    # Rule 1: No OpenRouter → always local
    if tier_level == 0:
        return Route(target="local", tier=ModelTier.LOCAL_STANDARD)

    # Rule 2: Large context → LARGE_CONTEXT queue (requires at least PAID_LOW)
    if estimated_tokens > 48_000:
        if tier_level >= _TIER_LEVELS["PAID_LOW"]:
            cloud_model = await _first_cloud_model("LARGE_CONTEXT", estimated_tokens)
            if cloud_model:
                logger.info("Route: large context (%d tokens) → LARGE_CONTEXT queue → %s",
                            estimated_tokens, cloud_model)
                return Route(target="openrouter", model=cloud_model)
        # Large context but no paid tier → local (will be trimmed to 48k)
        logger.info("Route: large context (%d tokens) but max_tier=%s → local fallback",
                     estimated_tokens, max_tier)
        return Route(target="local", tier=ModelTier.LOCAL_STANDARD)

    # Rule 3: Check GPU availability (cached 2s)
    queue_status = await get_router_queue_status()
    gpu_free = len(queue_status.get("gpu_free", [])) > 0

    # Rule 4: GPU free → always local
    if gpu_free:
        return Route(target="local", tier=ModelTier.LOCAL_STANDARD)

    # Rule 5: GPU busy → try queues by tier level
    # Always try FREE first
    cloud_model = await _first_cloud_model("FREE", estimated_tokens)
    if cloud_model:
        logger.info("Route: GPU busy → FREE queue → %s", cloud_model)
        return Route(target="openrouter", model=cloud_model)

    # Try PAID_LOW if allowed
    if tier_level >= _TIER_LEVELS["PAID_LOW"]:
        cloud_model = await _first_cloud_model("PAID_LOW", estimated_tokens)
        if cloud_model:
            logger.info("Route: GPU busy → PAID_LOW queue → %s", cloud_model)
            return Route(target="openrouter", model=cloud_model)

    # Try PAID_HIGH if allowed
    if tier_level >= _TIER_LEVELS["PAID_HIGH"]:
        cloud_model = await _first_cloud_model("PAID_HIGH", estimated_tokens)
        if cloud_model:
            logger.info("Route: GPU busy → PAID_HIGH queue → %s", cloud_model)
            return Route(target="openrouter", model=cloud_model)

    # Rule 6: Fallback → wait for local GPU
    logger.info("Route: no queue match → local GPU fallback (will wait), tokens=%d", estimated_tokens)
    return Route(target="local", tier=ModelTier.LOCAL_STANDARD)


async def _first_cloud_model(queue_name: str, estimated_tokens: int) -> str | None:
    """Get first available cloud model from a queue that can handle the context."""
    queue_models = await _get_queue(queue_name)
    for entry in queue_models:
        if entry.get("isLocal", False):
            continue  # Skip local entries (GPU already checked by caller)
        max_ctx = entry.get("maxContextTokens", 32_000)
        if estimated_tokens <= max_ctx:
            return entry.get("modelId")
    return None
