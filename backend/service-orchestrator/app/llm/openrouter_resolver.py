"""OpenRouter queue-based model resolver + GPU queue status.

Implements named queue routing (FRONTY):
- Each queue (CHAT, FREE, ORCHESTRATOR, LARGE_CONTEXT, CODING) holds
  an ordered list of models (local P40 + cloud OpenRouter).
- Router iterates the queue top-to-bottom, skipping models that can't
  handle the context or whose local backend is busy.
- Queues are loaded from Kotlin server's OpenRouter settings (cached).
- Fallback to hardcoded defaults if settings are unavailable.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field

import httpx

from app.config import settings
from app.models import ModelTier

logger = logging.getLogger(__name__)

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
    "CHAT": [
        {"modelId": "p40", "isLocal": True, "maxContextTokens": 48_000},
        {"modelId": "anthropic/claude-sonnet-4", "isLocal": False, "maxContextTokens": 200_000},
        {"modelId": "openai/gpt-4o", "isLocal": False, "maxContextTokens": 128_000},
    ],
    "FREE": [
        {"modelId": "p40", "isLocal": True, "maxContextTokens": 48_000},
        {"modelId": "qwen/qwen3-30b-a3b:free", "isLocal": False, "maxContextTokens": 32_000},
    ],
    "ORCHESTRATOR": [
        {"modelId": "p40", "isLocal": True, "maxContextTokens": 48_000},
        {"modelId": "qwen/qwen3-30b-a3b:free", "isLocal": False, "maxContextTokens": 32_000},
        {"modelId": "anthropic/claude-haiku-4", "isLocal": False, "maxContextTokens": 200_000},
    ],
    "LARGE_CONTEXT": [
        {"modelId": "google/gemini-2.5-flash", "isLocal": False, "maxContextTokens": 1_000_000},
        {"modelId": "anthropic/claude-sonnet-4", "isLocal": False, "maxContextTokens": 200_000},
    ],
    "CODING": [
        {"modelId": "p40", "isLocal": True, "maxContextTokens": 48_000},
        {"modelId": "anthropic/claude-sonnet-4", "isLocal": False, "maxContextTokens": 200_000},
    ],
    "CHAT_CLOUD": [
        {"modelId": "anthropic/claude-sonnet-4", "isLocal": False, "maxContextTokens": 200_000},
        {"modelId": "openai/gpt-4o", "isLocal": False, "maxContextTokens": 128_000},
        {"modelId": "p40", "isLocal": True, "maxContextTokens": 48_000},
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
    return _DEFAULT_QUEUES.get(queue_name, _DEFAULT_QUEUES["CHAT"])


# ── Queue name resolution ──────────────────────────────────────────────

# Maps use_case + priority to queue name
_QUEUE_MAP: dict[tuple[str, str], str] = {
    ("chat", "CRITICAL"): "CHAT",
    ("chat", "NORMAL"): "CHAT",
    ("coding", "CRITICAL"): "CODING",
    ("coding", "NORMAL"): "CODING",
    ("large_context", "CRITICAL"): "LARGE_CONTEXT",
    ("large_context", "NORMAL"): "LARGE_CONTEXT",
    ("orchestrator", "CRITICAL"): "ORCHESTRATOR",
    ("orchestrator", "NORMAL"): "ORCHESTRATOR",
    ("chat_cloud", "CRITICAL"): "CHAT_CLOUD",
    ("chat_cloud", "NORMAL"): "CHAT_CLOUD",
}


def _resolve_queue_name(
    use_case: str,
    priority: str,
    estimated_tokens: int,
) -> str:
    """Determine which queue to use based on context."""
    # Large context override
    if estimated_tokens > 48_000:
        return "LARGE_CONTEXT"
    # NORMAL priority with GPU busy can use FREE queue
    # (checked later in select_route — start with the standard queue)
    return _QUEUE_MAP.get((use_case, priority), "CHAT")


# ── Route dataclass ────────────────────────────────────────────────────

@dataclass
class Route:
    """Routing decision result."""
    target: str          # "local" or "openrouter"
    tier: ModelTier | None = None    # For local target
    model: str | None = None         # For openrouter target


def _select_local_tier(estimated_tokens: int) -> ModelTier:
    """Select local tier based on context size."""
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


# ── Main routing decision ──────────────────────────────────────────────

async def select_route(
    estimated_tokens: int,
    priority: str = "CRITICAL",
    has_openrouter: bool = False,
    use_case: str = "chat",
) -> Route:
    """Queue-based routing decision.

    Iterates the named queue for the given use_case/priority.
    For each model: if local, checks GPU availability; if cloud, uses it.
    Falls back to local GPU queue if no cloud model is available.

    Args:
        estimated_tokens: Estimated context size in tokens.
        priority: "CRITICAL" (chat) or "NORMAL" (background).
        has_openrouter: Whether client/project allows OpenRouter.
        use_case: "chat", "coding", "large_context", "orchestrator".

    Returns:
        Route with target ("local" or "openrouter") and model/tier info.
    """
    # Rule 1: No OpenRouter → always local
    if not has_openrouter:
        return Route(target="local", tier=_select_local_tier(estimated_tokens))

    # Determine queue name
    queue_name = _resolve_queue_name(use_case, priority, estimated_tokens)
    queue_models = await _get_queue(queue_name)

    # Get GPU availability (once, cached 2s)
    queue_status = await get_router_queue_status()
    gpu_free = len(queue_status.get("gpu_free", [])) > 0

    # Iterate queue: first available model wins
    for entry in queue_models:
        model_id = entry.get("modelId", "")
        is_local = entry.get("isLocal", False)
        max_ctx = entry.get("maxContextTokens", 32_000)

        # Skip if model can't handle the context
        if estimated_tokens > max_ctx:
            continue

        if is_local:
            # Local GPU — only use if free
            if gpu_free:
                logger.info(
                    "Route[%s]: queue=%s → local GPU (free), tokens=%d",
                    use_case, queue_name, estimated_tokens,
                )
                return Route(target="local", tier=_select_local_tier(estimated_tokens))
            # GPU busy — skip to next model in queue
            continue
        else:
            # Cloud model — always available
            logger.info(
                "Route[%s]: queue=%s → OpenRouter %s, tokens=%d",
                use_case, queue_name, model_id, estimated_tokens,
            )
            return Route(target="openrouter", model=model_id)

    # No queue model worked — try FREE queue for NORMAL priority
    if priority == "NORMAL" and queue_name != "FREE":
        free_models = await _get_queue("FREE")
        for entry in free_models:
            if entry.get("isLocal", False):
                continue  # Already checked GPU
            if estimated_tokens > entry.get("maxContextTokens", 32_000):
                continue
            model_id = entry.get("modelId", "")
            logger.info("Route[%s]: fallback FREE queue → OpenRouter %s", use_case, model_id)
            return Route(target="openrouter", model=model_id)

    # Ultimate fallback → queue on local GPU (will wait)
    logger.info("Route[%s]: no queue match → local GPU fallback, tokens=%d", use_case, estimated_tokens)
    return Route(target="local", tier=_select_local_tier(estimated_tokens))


async def resolve_openrouter_model(
    use_case: str = "chat",
    free_only: bool = False,
) -> str | None:
    """Legacy model resolver (used by callers that don't use select_route yet).

    Delegates to queue-based lookup when settings are available.
    """
    if not settings.openrouter_api_key:
        return None

    if free_only:
        queue = await _get_queue("FREE")
        for entry in queue:
            if not entry.get("isLocal", False):
                return entry.get("modelId")
        return "qwen/qwen3-30b-a3b:free"

    queue_name = {"chat": "CHAT", "coding": "CODING", "large_context": "LARGE_CONTEXT"}.get(use_case, "CHAT")
    queue = await _get_queue(queue_name)
    for entry in queue:
        if not entry.get("isLocal", False):
            return entry.get("modelId")

    return settings.default_cloud_model or "anthropic/claude-sonnet-4"
