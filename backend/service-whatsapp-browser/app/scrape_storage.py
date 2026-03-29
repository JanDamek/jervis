"""MongoDB storage for WhatsApp VLM scrape results.

Collections:
- whatsapp_scrape_results: latest scrape per client (upsert by clientId)
- whatsapp_scrape_messages: individual messages (deduplicated by hash)
- whatsapp_discovered_resources: discovered chats/groups
"""

from __future__ import annotations

import hashlib
import logging
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase

from app.config import settings

logger = logging.getLogger("whatsapp-browser.storage")


class ScrapeStorage:
    """Stores WhatsApp VLM scrape results in MongoDB."""

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._db: AsyncIOMotorDatabase | None = None

    async def start(self) -> None:
        uri = (
            f"mongodb://{settings.mongodb_user}:{settings.mongodb_password}"
            f"@{settings.mongodb_host}:{settings.mongodb_port}"
            f"/{settings.mongodb_database}?authSource={settings.mongodb_auth_db}"
        )
        self._client = AsyncIOMotorClient(uri)
        self._db = self._client[settings.mongodb_database]

        # Create indexes
        results = self._db["whatsapp_scrape_results"]
        await results.create_index(
            [("clientId", 1)],
            unique=True,
            name="client_unique",
        )
        await results.create_index(
            [("connectionId", 1)],
            name="connection_idx",
        )

        messages = self._db["whatsapp_scrape_messages"]
        await messages.create_index(
            [("connectionId", 1), ("messageHash", 1)],
            unique=True,
            name="connection_msg_unique",
        )
        await messages.create_index(
            [("connectionId", 1), ("state", 1)],
            name="connection_state_idx",
        )

        resources = self._db["whatsapp_discovered_resources"]
        await resources.create_index(
            [("connectionId", 1), ("externalId", 1)],
            unique=True,
            name="connection_resource_unique",
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
        data: dict,
    ) -> None:
        """Upsert latest scrape result for a client."""
        if self._db is None:
            return

        now = datetime.now(timezone.utc)
        await self._db["whatsapp_scrape_results"].update_one(
            {"clientId": client_id},
            {
                "$set": {
                    "connectionId": connection_id,
                    "data": data,
                    "scrapedAt": now,
                    "updatedAt": now,
                },
                "$setOnInsert": {
                    "clientId": client_id,
                    "createdAt": now,
                },
            },
            upsert=True,
        )

    async def store_messages(
        self,
        client_id: str,
        connection_id: str,
        messages: list[dict],
    ) -> int:
        """Store individual messages extracted from VLM scrape.

        Messages are deduplicated by connectionId + messageHash.
        Returns count of newly inserted messages.
        """
        if self._db is None or not messages:
            return 0

        now = datetime.now(timezone.utc)
        inserted = 0

        for msg in messages:
            raw = f"{msg.get('sender', '')}|{msg.get('time', '')}|{msg.get('content', '')}"
            msg_hash = hashlib.sha256(raw.encode()).hexdigest()[:16]

            try:
                await self._db["whatsapp_scrape_messages"].update_one(
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
                            "messageType": "chat",
                            "isGroup": msg.get("is_group", False),
                            "attachmentType": msg.get("attachment_type"),
                            "attachmentDescription": msg.get("attachment_description"),
                            "state": "NEW",
                            "createdAt": now,
                        },
                    },
                    upsert=True,
                )
                inserted += 1
            except Exception:
                pass  # Duplicate, skip

        if inserted:
            logger.info(
                "Stored %d new messages for %s",
                inserted, client_id,
            )

        return inserted

    async def store_discovered_resources(
        self,
        connection_id: str,
        client_id: str,
        resources: list[dict],
    ) -> int:
        """Store discovered WhatsApp chats/groups."""
        if self._db is None or not resources:
            return 0

        now = datetime.now(timezone.utc)
        inserted = 0

        for res in resources:
            external_id = res.get("id", "")
            if not external_id:
                continue

            try:
                result = await self._db["whatsapp_discovered_resources"].update_one(
                    {"connectionId": connection_id, "externalId": external_id},
                    {
                        "$set": {
                            "displayName": res.get("name", ""),
                            "description": res.get("description"),
                            "resourceType": res.get("type", "chat"),
                            "isGroup": res.get("is_group", False),
                            "lastSeenAt": now,
                            "active": True,
                        },
                        "$setOnInsert": {
                            "connectionId": connection_id,
                            "clientId": client_id,
                            "externalId": external_id,
                            "discoveredAt": now,
                        },
                    },
                    upsert=True,
                )
                if result.upserted_id:
                    inserted += 1
            except Exception:
                logger.debug("Failed to store resource %s", external_id)

        if inserted:
            logger.info(
                "Discovered %d new chats for connection %s",
                inserted, connection_id,
            )

        return inserted

    async def get_latest_result(self, client_id: str) -> dict | None:
        """Get latest scrape result for a client."""
        if self._db is None:
            return None

        return await self._db["whatsapp_scrape_results"].find_one(
            {"clientId": client_id},
            {"_id": 0},
        )
