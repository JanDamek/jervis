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
_RATE_LIMIT_PAUSE_S = 60.0    # Each 429 → pause model for 60s
_RATE_LIMIT_DISABLE_AFTER = 3  # After N consecutive 429s → disable (needs probe to re-enable)
_PROBE_COOLDOWN_S = 300.0     # First probe after 5 min
_PROBE_COOLDOWN_ESCALATION = 6.0  # Each failed probe multiplies cooldown (5m → 30m → 3h)
_PROBE_MAX_FAILURES = 3       # After N failed probes → permanently disabled (manual reset only)
_AUTO_RECOVERY_S = 300.0      # Non-429 errors: auto re-enable after 5 min

# ── Model usage statistics ───────────────────────────────────────────
# model_id → {"call_count": int, "total_time_s": float, "last_call": float}
_model_stats: dict[str, dict] = {}


def record_model_call(
    model_id: str, duration_s: float,
    input_tokens: int = 0, output_tokens: int = 0,
) -> None:
    """Record a model call for statistics (tokens from litellm usage)."""
    stats = _model_stats.get(model_id)
    if not stats:
        stats = {
            "call_count": 0, "total_time_s": 0.0, "last_call": 0.0,
            "total_input_tokens": 0, "total_output_tokens": 0,
        }
        _model_stats[model_id] = stats
    stats["call_count"] += 1
    stats["total_time_s"] += duration_s
    stats["total_input_tokens"] += input_tokens
    stats["total_output_tokens"] += output_tokens
    stats["last_call"] = time.time()


def get_model_stats() -> dict[str, dict]:
    """Get usage statistics for all models (for API/UI)."""
    result = {}
    for model_id, stats in _model_stats.items():
        count = stats["call_count"]
        total_out = stats.get("total_output_tokens", 0)
        total_time = stats["total_time_s"]
        result[model_id] = {
            "call_count": count,
            "avg_response_s": round(total_time / count, 2) if count > 0 else 0,
            "total_time_s": round(total_time, 1),
            "total_input_tokens": stats.get("total_input_tokens", 0),
            "total_output_tokens": total_out,
            "tokens_per_s": round(total_out / total_time, 1) if total_time > 0 else 0,
            "last_call": stats["last_call"],
        }
    return result


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
    require_tools: bool = False,
) -> str | None:
    """Find best cloud model that fits the context, iterating queues by tier.

    Tries highest allowed tier first, falls back to lower tiers.
    PREMIUM -> PAID -> FREE order ensures best quality when higher tier is allowed.
    capability: if set, only models with matching capability (or empty capabilities = all).
    require_tools: if True, only models with supportsTools=True are eligible.
    skip_models: model IDs to skip (already tried and failed in this request).
    Returns modelId or None.
    """
    # Try highest tier first for best quality, fall back to lower tiers
    if tier_level >= TIER_LEVELS["PREMIUM"]:
        cloud_model = await _first_cloud_model("PREMIUM", estimated_tokens, skip_models, capability, require_tools)
        if cloud_model:
            return cloud_model

    if tier_level >= TIER_LEVELS["PAID"]:
        cloud_model = await _first_cloud_model("PAID", estimated_tokens, skip_models, capability, require_tools)
        if cloud_model:
            return cloud_model

    if tier_level >= TIER_LEVELS["FREE"]:
        cloud_model = await _first_cloud_model("FREE", estimated_tokens, skip_models, capability, require_tools)
        if cloud_model:
            return cloud_model

    return None


async def _first_cloud_model(
    queue_name: str, estimated_tokens: int, skip_models: list[str] | None = None,
    capability: str | None = None,
    require_tools: bool = False,
) -> str | None:
    """Get next available cloud model from a queue using round-robin rotation.

    For FREE queue: rotates through models evenly to distribute load and avoid
    hitting per-model rate limits. For PAID/PREMIUM: uses first-fit (priority order).

    Skips models marked as error (3+ consecutive failures) and skip_models list.
    capability: if set, model must have matching capability in its capabilities list.
    require_tools: if True, only models with supportsTools=True are eligible.
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
        if require_tools and not entry.get("supportsTools", False):
            logger.debug("Skipping model %s (require_tools=True but supportsTools=False)", model_id)
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

            # Permanently disabled (failed too many probes) → manual reset only
            if error_info.get("permanently_disabled"):
                logger.debug("Skipping model %s (permanently disabled — manual reset required)", model_id)
                continue

            # Still in cooldown
            if disabled_until > now:
                remaining = int(disabled_until - now)
                logger.debug("Skipping model %s (paused for %ds)", model_id, remaining)
                continue

            # Cooldown expired for non-429 errors → auto-recover
            if error_info.get("disabled") and not error_info.get("needs_probe"):
                logger.info("Model %s auto-recovered after cooldown", model_id)
                del _model_errors[model_id]
            # Cooldown expired for 429 errors → needs probe before re-enabling
            elif error_info.get("needs_probe") and disabled_until <= now and disabled_until > 0:
                error_info["probe_ready"] = True
                logger.debug("Skipping model %s (probe ready, awaiting test)", model_id)
                continue
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

    # All queues: first-fit (priority order). Next model only if first is disabled/skipped.
    selected = candidates[0]
    logger.info("Queue %s: selected %s (first of %d candidates)", queue_name, selected, len(candidates))
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

    # Rate limit (429): pause for 60s each time, disable after N consecutive 429s.
    # Upstream provider rate limits can't be controlled — fast disable is better
    # than wasting time retrying a model that's going to 429 again.
    is_rate_limit = "429" in msg_lower or "rate limit" in msg_lower or "too many" in msg_lower
    if is_rate_limit:
        consecutive_429 = info.get("consecutive_429", 0) + 1
        info["consecutive_429"] = consecutive_429

        if consecutive_429 >= _RATE_LIMIT_DISABLE_AFTER:
            # 3rd consecutive 429 → disable, needs probe to re-enable
            info["count"] = _MAX_CONSECUTIVE_ERRORS
            info["disabled"] = True
            info["needs_probe"] = True
            info["probe_failures"] = info.get("probe_failures", 0)
            cooldown = _PROBE_COOLDOWN_S * (_PROBE_COOLDOWN_ESCALATION ** info["probe_failures"])
            info["disabled_until"] = now + cooldown
            logger.warning("Model %s DISABLED after %d consecutive 429s (probe in %ds)",
                            model_id, consecutive_429, int(cooldown))
            return True

        # Pause for 60s
        info["disabled_until"] = now + _RATE_LIMIT_PAUSE_S
        logger.warning("Model %s RATE LIMITED (429 #%d/%d) — paused for %ds",
                        model_id, consecutive_429, _RATE_LIMIT_DISABLE_AFTER, int(_RATE_LIMIT_PAUSE_S))
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


def get_models_needing_probe() -> list[str]:
    """Get model IDs that have expired cooldown and need a probe test."""
    return [
        model_id for model_id, info in _model_errors.items()
        if info.get("probe_ready") and not info.get("permanently_disabled")
    ]


def handle_probe_success(model_id: str) -> None:
    """Probe test succeeded — re-enable the model."""
    if model_id in _model_errors:
        logger.info("Model %s PROBE OK — re-enabled", model_id)
        del _model_errors[model_id]


def handle_probe_failure(model_id: str) -> None:
    """Probe test failed (still 429) — escalate cooldown or permanently disable."""
    info = _model_errors.get(model_id)
    if not info:
        return

    info["probe_ready"] = False
    probe_failures = info.get("probe_failures", 0) + 1
    info["probe_failures"] = probe_failures

    if probe_failures >= _PROBE_MAX_FAILURES:
        info["permanently_disabled"] = True
        logger.warning("Model %s PERMANENTLY DISABLED after %d failed probes (manual reset required)",
                        model_id, probe_failures)
        return

    # Escalate cooldown: 5min → 30min → 3h
    cooldown = _PROBE_COOLDOWN_S * (_PROBE_COOLDOWN_ESCALATION ** probe_failures)
    info["disabled_until"] = time.monotonic() + cooldown
    logger.warning("Model %s PROBE FAILED (#%d/%d) — next probe in %ds",
                    model_id, probe_failures, _PROBE_MAX_FAILURES, int(cooldown))


async def load_persisted_stats() -> None:
    """Load stats from settings on startup (stats are embedded in queue model entries)."""
    or_settings = await _fetch_openrouter_settings()
    if not or_settings:
        return
    count = 0
    for queue in or_settings.get("modelQueues", []):
        for entry in queue.get("models", []):
            model_id = entry.get("modelId", "")
            stats = entry.get("stats")
            if stats and stats.get("callCount", 0) > 0:
                _model_stats[model_id] = {
                    "call_count": stats.get("callCount", 0),
                    "total_time_s": stats.get("totalTimeS", 0.0),
                    "total_input_tokens": stats.get("totalInputTokens", 0),
                    "total_output_tokens": stats.get("totalOutputTokens", 0),
                    "last_call": stats.get("lastCall", 0.0),
                }
                count += 1
    if count > 0:
        logger.info("Loaded persisted stats for %d models", count)


async def persist_stats() -> None:
    """Send in-memory stats to Kotlin server for MongoDB persistence."""
    stats = get_model_stats()
    if not stats:
        return
    url = f"{settings.kotlin_server_url.rstrip('/')}/internal/openrouter-model-stats"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, json=stats)
            if resp.status_code == 200:
                logger.info("Persisted stats for %d models to MongoDB", len(stats))
            else:
                logger.warning("Failed to persist stats: HTTP %d", resp.status_code)
    except Exception as e:
        logger.warning("Failed to persist stats: %s", e)


async def get_max_context_tokens(max_tier: str = "NONE") -> int:
    """Get the maximum context tokens available across all allowed queues.

    Used to know when context is too large for any model and needs chunking.
    Returns: max context tokens (e.g. 200_000 for Sonnet, 48_000 for local only).
    """
    # Local long-context GPU model can stream up to ~250k tokens
    # (40k VRAM but kv-cache + offload extends effective window).
    LOCAL_MAX_CTX = 250_000

    max_tier = normalize_tier(max_tier)  # backward compat
    tier_level = TIER_LEVELS.get(max_tier, 0)
    if tier_level == 0:
        return LOCAL_MAX_CTX

    max_ctx = LOCAL_MAX_CTX
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
