"""MongoDB connection management for Jervis MCP Server."""

from __future__ import annotations

import logging

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase

from app.config import settings

logger = logging.getLogger(__name__)

_client: AsyncIOMotorClient | None = None
_db: AsyncIOMotorDatabase | None = None


async def get_db() -> AsyncIOMotorDatabase:
    """Get or create MongoDB connection."""
    global _client, _db
    if _db is None:
        _client = AsyncIOMotorClient(settings.mongodb_url)
        _db = _client[settings.mongodb_database]
        logger.info("Connected to MongoDB: %s", settings.mongodb_database)
    return _db


async def close_db() -> None:
    """Close MongoDB connection."""
    global _client, _db
    if _client:
        _client.close()
        _client = None
        _db = None
        logger.info("MongoDB connection closed")
