"""OpenRouter model catalog — fetches settings from Kotlin server, provides model queues.

Moved from orchestrator (openrouter_resolver.py) into the router so routing decisions
are centralized. Cached for 60s to avoid hammering the Kotlin server.
"""

from __future__ import annotations

import logging
import time

import httpx

from .config import settings

logger = logging.getLogger("ollama-router.openrouter")

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

    url = f"{settings.kotlin_server_url.rstrip('/')}/internal/openrouter-settings"

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            _openrouter_settings_cache = resp.json()
            _openrouter_settings_ts = now
            logger.debug("Fetched OpenRouter settings: %d queues",
                         len(_openrouter_settings_cache.get("modelQueues", [])))
            return _openrouter_settings_cache
    except Exception as e:
        logger.warning("Failed to fetch OpenRouter settings: %s", e)
        return _openrouter_settings_cache  # Return stale cache if available


async def get_api_key() -> str | None:
    """Get OpenRouter API key from cached settings."""
    or_settings = await _fetch_openrouter_settings()
    if or_settings:
        key = or_settings.get("apiKey", "")
        if key:
            return key
    return None


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
}

# Tier ordering for comparison
TIER_LEVELS = {"NONE": 0, "FREE": 1, "PAID_LOW": 2, "PAID_HIGH": 3}


async def get_queue(queue_name: str) -> list[dict]:
    """Get model queue by name from settings, falling back to defaults."""
    or_settings = await _fetch_openrouter_settings()
    if or_settings:
        for q in or_settings.get("modelQueues", []):
            if q.get("name") == queue_name and q.get("enabled", True):
                models = [m for m in q.get("models", []) if m.get("enabled", True)]
                if models:
                    return models
    return _DEFAULT_QUEUES.get(queue_name, _DEFAULT_QUEUES["FREE"])


async def find_cloud_model_for_context(estimated_tokens: int, tier_level: int) -> str | None:
    """Find first cloud model that fits the context, iterating queues by tier.

    Tries FREE → PAID_LOW → PAID_HIGH in order, respecting tier_level limit.
    Returns modelId or None.
    """
    cloud_model = await _first_cloud_model("FREE", estimated_tokens)
    if cloud_model:
        return cloud_model

    if tier_level >= TIER_LEVELS["PAID_LOW"]:
        cloud_model = await _first_cloud_model("PAID_LOW", estimated_tokens)
        if cloud_model:
            return cloud_model

    if tier_level >= TIER_LEVELS["PAID_HIGH"]:
        cloud_model = await _first_cloud_model("PAID_HIGH", estimated_tokens)
        if cloud_model:
            return cloud_model

    return None


async def _first_cloud_model(queue_name: str, estimated_tokens: int) -> str | None:
    """Get first available cloud model from a queue that can handle the context."""
    queue_models = await get_queue(queue_name)
    for entry in queue_models:
        if entry.get("isLocal", False):
            continue
        max_ctx = entry.get("maxContextTokens", 32_000)
        if estimated_tokens <= max_ctx:
            return entry.get("modelId")
    return None


async def get_max_context_tokens(max_tier: str = "NONE") -> int:
    """Get the maximum context tokens available across all allowed queues.

    Used to know when context is too large for any model and needs chunking.
    Returns: max context tokens (e.g. 200_000 for Sonnet, 48_000 for local only).
    """
    tier_level = TIER_LEVELS.get(max_tier, 0)
    if tier_level == 0:
        return 48_000

    max_ctx = 48_000
    queues_to_check = ["FREE"]
    if tier_level >= TIER_LEVELS["PAID_LOW"]:
        queues_to_check.append("PAID_LOW")
    if tier_level >= TIER_LEVELS["PAID_HIGH"]:
        queues_to_check.append("PAID_HIGH")

    for queue_name in queues_to_check:
        queue_models = await get_queue(queue_name)
        for entry in queue_models:
            if entry.get("isLocal", False):
                continue
            entry_ctx = entry.get("maxContextTokens", 32_000)
            if entry_ctx > max_ctx:
                max_ctx = entry_ctx

    return max_ctx
