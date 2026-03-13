"""MongoDB storage for VLM scrape results.

Stores scraped data directly in MongoDB so Kotlin server / polling handler
can read it without an internal HTTP push.

Collections:
- o365_scrape_results: latest scrape per client+tab (upsert by clientId+tabType)
- o365_scrape_messages: individual chat messages extracted from VLM (for indexing)
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase

from app.config import settings
from app.tab_manager import TabType

logger = logging.getLogger("o365-browser-pool.storage")


class ScrapeStorage:
    """Stores VLM scrape results in MongoDB."""

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._db: AsyncIOMotorDatabase | None = None

    async def start(self) -> None:
        # Build URI from individual env-var settings (configmap + secret)
        uri = (
            f"mongodb://{settings.mongodb_username}:{settings.mongodb_password}"
            f"@{settings.mongodb_host}:{settings.mongodb_port}"
            f"/{settings.mongodb_database}?authSource={settings.mongodb_auth_db}"
        )
        self._client = AsyncIOMotorClient(uri)
        self._db = self._client[settings.mongodb_database]

        # Create indexes
        results = self._db["o365_scrape_results"]
        await results.create_index(
            [("clientId", 1), ("tabType", 1)],
            unique=True,
            name="client_tab_unique",
        )
        await results.create_index(
            [("connectionId", 1), ("tabType", 1)],
            name="connection_tab_idx",
        )

        messages = self._db["o365_scrape_messages"]
        await messages.create_index(
            [("connectionId", 1), ("messageHash", 1)],
            unique=True,
            name="connection_msg_unique",
        )
        await messages.create_index(
            [("connectionId", 1), ("state", 1)],
            name="connection_state_idx",
        )

        logger.info("ScrapeStorage connected to MongoDB")

    async def stop(self) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None
            self._db = None

    async def store_scrape_result(
        self,
        client_id: str,
        connection_id: str,
        tab_type: TabType,
        data: dict,
    ) -> None:
        """Upsert latest scrape result for a client+tab combination."""
        if self._db is None:
            return

        now = datetime.now(timezone.utc)
        await self._db["o365_scrape_results"].update_one(
            {"clientId": client_id, "tabType": tab_type.value},
            {
                "$set": {
                    "connectionId": connection_id,
                    "data": data,
                    "scrapedAt": now,
                    "updatedAt": now,
                },
                "$setOnInsert": {
                    "clientId": client_id,
                    "tabType": tab_type.value,
                    "createdAt": now,
                },
            },
            upsert=True,
        )

    async def store_chat_messages(
        self,
        client_id: str,
        connection_id: str,
        messages: list[dict],
    ) -> int:
        """Store individual chat messages extracted from VLM scrape.

        Messages are deduplicated by connectionId + messageHash.
        Returns count of newly inserted messages.
        """
        if self._db is None or not messages:
            return 0

        import hashlib

        now = datetime.now(timezone.utc)
        inserted = 0

        for msg in messages:
            # Create hash from sender + time + content for dedup
            raw = f"{msg.get('sender', '')}|{msg.get('time', '')}|{msg.get('content', '')}"
            msg_hash = hashlib.sha256(raw.encode()).hexdigest()[:16]

            try:
                await self._db["o365_scrape_messages"].update_one(
                    {"connectionId": connection_id, "messageHash": msg_hash},
                    {
                        "$setOnInsert": {
                            "clientId": client_id,
                            "connectionId": connection_id,
                            "messageHash": msg_hash,
                            "sender": msg.get("sender"),
                            "content": msg.get("content"),
                            "timestamp": msg.get("time"),
                            "chatName": msg.get("chat_name"),
                            "state": "NEW",
                            "createdAt": now,
                        },
                    },
                    upsert=True,
                )
                # Check if it was an insert (not update)
                inserted += 1
            except Exception:
                pass  # Duplicate, skip

        if inserted:
            logger.info(
                "Stored %d new chat messages for %s",
                inserted, client_id,
            )

        return inserted

    async def get_latest_result(
        self,
        client_id: str,
        tab_type: str,
    ) -> dict | None:
        """Get latest scrape result for a client+tab."""
        if self._db is None:
            return None

        return await self._db["o365_scrape_results"].find_one(
            {"clientId": client_id, "tabType": tab_type},
            {"_id": 0},
        )

    async def get_discovery_resources(
        self,
        connection_id: str,
    ) -> list[dict]:
        """Get all discovered resources for a connection (from chat scrape)."""
        if self._db is None:
            return []

        result = await self._db["o365_scrape_results"].find_one(
            {"connectionId": connection_id, "tabType": "chat"},
            {"_id": 0, "data": 1},
        )

        if not result or not result.get("data"):
            return []

        data = result["data"]
        resources = []

        # Extract chats from scrape data
        for chat in data.get("chats", []):
            resources.append({
                "type": "chat",
                "id": f"chat_{chat.get('name', '').lower().replace(' ', '_')}",
                "name": chat.get("name", ""),
                "description": chat.get("preview"),
            })

        return resources
