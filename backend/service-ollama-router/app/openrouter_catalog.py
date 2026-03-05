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

# ── Model error tracking ──────────────────────────────────────────────
# model_id → {"count": int, "last_error": float, "disabled": bool,
#              "errors": [{"message": str, "timestamp": float}]}
_model_errors: dict[str, dict] = {}
_MAX_CONSECUTIVE_ERRORS = 3  # Mark as error after 3 consecutive failures
_MAX_ERROR_HISTORY = 5  # Keep last N error messages per model


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
    "PAID": [
        {"modelId": "p40", "isLocal": True, "maxContextTokens": 48_000},
        {"modelId": "anthropic/claude-haiku-4", "isLocal": False, "maxContextTokens": 200_000},
        {"modelId": "openai/gpt-4o-mini", "isLocal": False, "maxContextTokens": 128_000},
    ],
    "PREMIUM": [
        {"modelId": "p40", "isLocal": True, "maxContextTokens": 48_000},
        {"modelId": "anthropic/claude-sonnet-4", "isLocal": False, "maxContextTokens": 200_000},
        {"modelId": "openai/o3-mini", "isLocal": False, "maxContextTokens": 200_000},
    ],
}

# Tier ordering for comparison
TIER_LEVELS = {"NONE": 0, "FREE": 1, "PAID": 2, "PREMIUM": 3}

# Backward compatibility: map old tier names to new ones
_TIER_COMPAT = {"PAID_LOW": "PAID", "PAID_HIGH": "PREMIUM"}


def normalize_tier(tier: str) -> str:
    """Normalize tier name, mapping old names to new ones."""
    return _TIER_COMPAT.get(tier, tier)


async def get_queue(queue_name: str) -> list[dict]:
    """Get model queue by name from settings, falling back to defaults."""
    queue_name = normalize_tier(queue_name)  # backward compat: PAID_LOW→PAID, PAID_HIGH→PREMIUM
    or_settings = await _fetch_openrouter_settings()
    if or_settings:
        for q in or_settings.get("modelQueues", []):
            if q.get("name") == queue_name and q.get("enabled", True):
                models = [m for m in q.get("models", []) if m.get("enabled", True)]
                if models:
                    return models
    return _DEFAULT_QUEUES.get(queue_name, _DEFAULT_QUEUES["FREE"])


async def find_cloud_model_for_context(
    estimated_tokens: int, tier_level: int, skip_models: list[str] | None = None,
) -> str | None:
    """Find first cloud model that fits the context, iterating queues by tier.

    Tries FREE -> PAID -> PREMIUM in order, respecting tier_level limit.
    skip_models: model IDs to skip (already tried and failed in this request).
    Returns modelId or None.
    """
    cloud_model = await _first_cloud_model("FREE", estimated_tokens, skip_models)
    if cloud_model:
        return cloud_model

    if tier_level >= TIER_LEVELS["PAID"]:
        cloud_model = await _first_cloud_model("PAID", estimated_tokens, skip_models)
        if cloud_model:
            return cloud_model

    if tier_level >= TIER_LEVELS["PREMIUM"]:
        cloud_model = await _first_cloud_model("PREMIUM", estimated_tokens, skip_models)
        if cloud_model:
            return cloud_model

    return None


async def _first_cloud_model(
    queue_name: str, estimated_tokens: int, skip_models: list[str] | None = None,
) -> str | None:
    """Get first available cloud model from a queue that can handle the context.

    Skips models marked as error (3+ consecutive failures) and skip_models list.
    """
    skip_set = set(skip_models) if skip_models else set()
    queue_models = await get_queue(queue_name)
    for entry in queue_models:
        if entry.get("isLocal", False):
            continue
        model_id = entry.get("modelId", "")
        # Skip explicitly excluded models (failed in this request)
        if model_id in skip_set:
            logger.debug("Skipping model %s (explicitly excluded)", model_id)
            continue
        # Skip models with too many consecutive errors
        error_info = _model_errors.get(model_id)
        if error_info and error_info.get("disabled"):
            logger.debug("Skipping error model %s (%d consecutive errors)",
                         model_id, error_info.get("count", 0))
            continue
        max_ctx = entry.get("maxContextTokens", 32_000)
        if estimated_tokens <= max_ctx:
            return model_id
    return None


def report_model_error(model_id: str, error_message: str = "") -> bool:
    """Report a model error. Returns True if model was just disabled (3rd strike).

    Called by orchestrator when a cloud model returns a provider error (400/500).
    After 3 consecutive errors, model is disabled until manually re-enabled.
    Stores last _MAX_ERROR_HISTORY error messages for UI display.
    """
    info = _model_errors.get(model_id)
    if not info:
        info = {"count": 0, "last_error": 0.0, "disabled": False, "errors": []}
        _model_errors[model_id] = info

    info["count"] = info.get("count", 0) + 1
    info["last_error"] = time.monotonic()

    # Store error message (keep last N)
    if error_message:
        errors = info.setdefault("errors", [])
        errors.append({"message": error_message[:500], "timestamp": time.time()})
        if len(errors) > _MAX_ERROR_HISTORY:
            info["errors"] = errors[-_MAX_ERROR_HISTORY:]

    if info["count"] >= _MAX_CONSECUTIVE_ERRORS and not info.get("disabled"):
        info["disabled"] = True
        logger.warning("Model %s DISABLED after %d consecutive errors", model_id, info["count"])
        return True
    logger.info("Model %s error count: %d/%d", model_id, info["count"], _MAX_CONSECUTIVE_ERRORS)
    return False


def report_model_success(model_id: str) -> None:
    """Report a successful model call. Resets error counter."""
    if model_id in _model_errors:
        if _model_errors[model_id].get("count", 0) > 0:
            logger.info("Model %s error counter reset (success)", model_id)
        del _model_errors[model_id]


def get_model_errors() -> dict[str, dict]:
    """Get current model error state (for API/UI)."""
    return dict(_model_errors)


def reset_model_error(model_id: str) -> bool:
    """Re-enable a model after manual testing. Returns True if model was disabled."""
    info = _model_errors.pop(model_id, None)
    if info and info.get("disabled"):
        logger.info("Model %s re-enabled by user", model_id)
        return True
    return False


async def get_max_context_tokens(max_tier: str = "NONE") -> int:
    """Get the maximum context tokens available across all allowed queues.

    Used to know when context is too large for any model and needs chunking.
    Returns: max context tokens (e.g. 200_000 for Sonnet, 48_000 for local only).
    """
    max_tier = normalize_tier(max_tier)  # backward compat
    tier_level = TIER_LEVELS.get(max_tier, 0)
    if tier_level == 0:
        return 48_000

    max_ctx = 48_000
    queues_to_check = ["FREE"]
    if tier_level >= TIER_LEVELS["PAID"]:
        queues_to_check.append("PAID")
    if tier_level >= TIER_LEVELS["PREMIUM"]:
        queues_to_check.append("PREMIUM")

    for queue_name in queues_to_check:
        queue_models = await get_queue(queue_name)
        for entry in queue_models:
            if entry.get("isLocal", False):
                continue
            entry_ctx = entry.get("maxContextTokens", 32_000)
            if entry_ctx > max_ctx:
                max_ctx = entry_ctx

    return max_ctx
