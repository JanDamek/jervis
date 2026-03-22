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
#              "disabled_until": float, "errors": [...]}
_model_errors: dict[str, dict] = {}
_MAX_CONSECUTIVE_ERRORS = 3   # Disable after N consecutive failures
_MAX_ERROR_HISTORY = 10       # Keep last N error messages per model
_RATE_LIMIT_PAUSE_S = 30.0    # Initial pause on first 429 (short — proactive limiter should prevent most)
_RATE_LIMIT_MAX_PAUSE_S = 300.0  # Max pause: 5 min (proactive limiter handles the rest)
_AUTO_RECOVERY_S = 300.0      # Auto re-enable disabled model after 5 min

# ── Round-robin state for FREE queue ─────────────────────────────────
# Tracks which model was used last per queue, so we rotate evenly
# queue_name → index of last used model in the filtered candidate list
_round_robin_index: dict[str, int] = {}


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


# Tier ordering for comparison
TIER_LEVELS = {"NONE": 0, "FREE": 1, "PAID": 2, "PREMIUM": 3}

# Backward compatibility: map old tier names to new ones
_TIER_COMPAT = {"PAID_LOW": "PAID", "PAID_HIGH": "PREMIUM"}


def normalize_tier(tier: str) -> str:
    """Normalize tier name, mapping old names to new ones."""
    return _TIER_COMPAT.get(tier, tier)


async def get_queue(queue_name: str) -> list[dict]:
    """Get model queue by name from settings. Returns empty list if unavailable (= local only)."""
    queue_name = normalize_tier(queue_name)  # backward compat: PAID_LOW→PAID, PAID_HIGH→PREMIUM
    or_settings = await _fetch_openrouter_settings()
    if or_settings:
        for q in or_settings.get("modelQueues", []):
            if q.get("name") == queue_name and q.get("enabled", True):
                models = [m for m in q.get("models", []) if m.get("enabled", True)]
                if models:
                    return models
    return []


async def find_cloud_model_for_context(
    estimated_tokens: int, tier_level: int, skip_models: list[str] | None = None,
    capability: str | None = None,
) -> str | None:
    """Find first cloud model that fits the context, iterating queues by tier.

    Tries FREE -> PAID -> PREMIUM in order, respecting tier_level limit.
    capability: if set, only models with matching capability (or empty capabilities = all).
    skip_models: model IDs to skip (already tried and failed in this request).
    Returns modelId or None.
    """
    cloud_model = await _first_cloud_model("FREE", estimated_tokens, skip_models, capability)
    if cloud_model:
        return cloud_model

    if tier_level >= TIER_LEVELS["PAID"]:
        cloud_model = await _first_cloud_model("PAID", estimated_tokens, skip_models, capability)
        if cloud_model:
            return cloud_model

    if tier_level >= TIER_LEVELS["PREMIUM"]:
        cloud_model = await _first_cloud_model("PREMIUM", estimated_tokens, skip_models, capability)
        if cloud_model:
            return cloud_model

    return None


async def _first_cloud_model(
    queue_name: str, estimated_tokens: int, skip_models: list[str] | None = None,
    capability: str | None = None,
) -> str | None:
    """Get next available cloud model from a queue using round-robin rotation.

    For FREE queue: rotates through models evenly to distribute load and avoid
    hitting per-model rate limits. For PAID/PREMIUM: uses first-fit (priority order).

    Skips models marked as error (3+ consecutive failures) and skip_models list.
    capability: if set, model must have matching capability in its capabilities list.
    Models with empty capabilities list are compatible with all capabilities (backward compat).
    """
    skip_set = set(skip_models) if skip_models else set()
    queue_models = await get_queue(queue_name)

    # Build candidate list (all eligible models)
    candidates: list[str] = []
    for entry in queue_models:
        if entry.get("isLocal", False):
            continue
        model_id = entry.get("modelId", "")
        if model_id in skip_set:
            logger.debug("Skipping model %s (explicitly excluded)", model_id)
            continue
        if capability:
            model_caps = entry.get("capabilities", [])
            if model_caps and capability not in model_caps:
                logger.debug("Skipping model %s (no capability '%s', has %s)", model_id, capability, model_caps)
                continue
        # Skip models with too many consecutive errors or temporarily paused
        error_info = _model_errors.get(model_id)
        if error_info:
            now = time.monotonic()
            disabled_until = error_info.get("disabled_until", 0.0)
            if disabled_until > now:
                remaining = int(disabled_until - now)
                logger.debug("Skipping model %s (paused for %ds)", model_id, remaining)
                continue
            if error_info.get("disabled") and disabled_until <= now and disabled_until > 0:
                logger.info("Model %s auto-recovered after cooldown", model_id)
                del _model_errors[model_id]
            elif error_info.get("disabled"):
                logger.debug("Skipping error model %s (%d consecutive errors)",
                             model_id, error_info.get("count", 0))
                continue
        max_ctx = entry.get("maxContextTokens", 32_000)
        if estimated_tokens <= max_ctx:
            candidates.append(model_id)
        else:
            logger.debug("Skipping model %s (context %d > max %d — not an error)", model_id, estimated_tokens, max_ctx)

    if not candidates:
        return None

    # PAID/PREMIUM: always use first-fit (priority order matters for cost)
    if queue_name != "FREE":
        return candidates[0]

    # FREE: round-robin rotation to distribute load evenly
    last_idx = _round_robin_index.get(queue_name, -1)
    next_idx = (last_idx + 1) % len(candidates)
    selected = candidates[next_idx]
    _round_robin_index[queue_name] = next_idx
    logger.info("FREE round-robin: selected %s (index %d/%d)", selected, next_idx, len(candidates))
    return selected


def report_model_error(model_id: str, error_message: str = "") -> bool:
    """Report a model error. Returns True if model was just disabled.

    Error types handled:
    - Rate limit (429 / "rate limit"): pause for _RATE_LIMIT_PAUSE_S (60s), don't increment counter
    - Empty response / provider error: increment counter, disable after _MAX_CONSECUTIVE_ERRORS
    - Disabled models auto-recover after _AUTO_RECOVERY_S (5 min)
    """
    info = _model_errors.get(model_id)
    if not info:
        info = {"count": 0, "last_error": 0.0, "disabled": False,
                "disabled_until": 0.0, "errors": []}
        _model_errors[model_id] = info

    now = time.monotonic()
    info["last_error"] = now
    msg_lower = error_message.lower() if error_message else ""

    # Store error message (keep last N)
    if error_message:
        errors = info.setdefault("errors", [])
        errors.append({"message": error_message[:500], "timestamp": time.time()})
        if len(errors) > _MAX_ERROR_HISTORY:
            info["errors"] = errors[-_MAX_ERROR_HISTORY:]

    # Rate limit detection — exponential backoff, don't count as regular error.
    # First 429: pause 60s. Each consecutive 429 doubles the pause.
    # Sequence: 60s → 2min → 4min → 8min → 16min → 32min → 1h → 2h → 4h → 8h → 12h (cap)
    is_rate_limit = "429" in msg_lower or "rate limit" in msg_lower or "too many" in msg_lower
    if is_rate_limit:
        consecutive_429 = info.get("consecutive_429", 0) + 1
        info["consecutive_429"] = consecutive_429
        pause = min(_RATE_LIMIT_PAUSE_S * (2 ** (consecutive_429 - 1)), _RATE_LIMIT_MAX_PAUSE_S)
        info["disabled_until"] = now + pause
        if pause >= 3600:
            logger.warning("Model %s RATE LIMITED (429 #%d) — paused for %.1fh",
                            model_id, consecutive_429, pause / 3600)
        else:
            logger.warning("Model %s RATE LIMITED (429 #%d) — paused for %ds",
                            model_id, consecutive_429, int(pause))
        return False

    # Context overflow — skip for this request but don't disable the model.
    # Model works fine for smaller contexts, disabling it would be wrong.
    is_context_overflow = any(kw in msg_lower for kw in [
        "context length", "context_length", "too long", "maximum context",
        "token limit", "exceeds", "max_tokens", "prompt is too long",
        "input too long", "request too large",
    ])
    if is_context_overflow:
        logger.warning("Model %s CONTEXT OVERFLOW — not counting as error (model works for smaller requests)",
                        model_id)
        return False

    # Regular error — increment counter
    info["count"] = info.get("count", 0) + 1

    if info["count"] >= _MAX_CONSECUTIVE_ERRORS and not info.get("disabled"):
        info["disabled"] = True
        info["disabled_until"] = now + _AUTO_RECOVERY_S
        logger.warning("Model %s DISABLED after %d consecutive errors (auto-recovery in %ds)",
                        model_id, info["count"], int(_AUTO_RECOVERY_S))
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
