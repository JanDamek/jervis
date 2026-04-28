"""Client tier cache — resolves max OpenRouter tier from MongoDB.

Router queries clients collection to find cloudModelPolicy.maxOpenRouterTier.
Cached with 5min TTL to avoid hitting DB on every route-decision.
"""

from __future__ import annotations

import logging
import time

from motor.motor_asyncio import AsyncIOMotorClient

from app.config import settings

logger = logging.getLogger("ollama-router.tier-cache")

# Cache: {client_id: (tier_string, timestamp)}
_cache: dict[str, tuple[str, float]] = {}
_mongo_client: AsyncIOMotorClient | None = None

# Default tier when client not found or DB unavailable
DEFAULT_TIER = "FREE"


async def _get_db():
    global _mongo_client
    if _mongo_client is None:
        _mongo_client = AsyncIOMotorClient(settings.mongodb_uri)
    return _mongo_client.get_default_database()


async def resolve_client_tier(client_id: str | None) -> str:
    """Resolve OpenRouter tier for a client. Returns tier string (NONE/FREE/PAID/PREMIUM).

    Uses in-memory cache with TTL. Falls back to DEFAULT_TIER on error.
    """
    if not client_id:
        return DEFAULT_TIER

    # Check cache
    cached = _cache.get(client_id)
    if cached:
        tier, ts = cached
        if time.monotonic() - ts < settings.client_tier_cache_ttl_s:
            return tier

    # Cache miss — query MongoDB
    try:
        db = await _get_db()
        from bson import ObjectId
        doc = await db["clients"].find_one(
            {"_id": ObjectId(client_id)},
            {"cloudModelPolicy.maxOpenRouterTier": 1},
        )
        if doc:
            policy = doc.get("cloudModelPolicy", {})
            tier = policy.get("maxOpenRouterTier", DEFAULT_TIER)
            if isinstance(tier, str):
                _cache[client_id] = (tier, time.monotonic())
                logger.info("Client tier resolved: %s → %s", client_id, tier)
                return tier

        # Client not found — default
        _cache[client_id] = (DEFAULT_TIER, time.monotonic())
        return DEFAULT_TIER

    except Exception as e:
        logger.warning("Failed to resolve tier for %s: %s — using default %s", client_id, e, DEFAULT_TIER)
        return DEFAULT_TIER


def invalidate_cache(client_id: str | None = None) -> None:
    """Invalidate cache for a client or all clients."""
    if client_id:
        _cache.pop(client_id, None)
    else:
        _cache.clear()


async def shutdown() -> None:
    """Close MongoDB connection."""
    global _mongo_client
    if _mongo_client:
        _mongo_client.close()
        _mongo_client = None
