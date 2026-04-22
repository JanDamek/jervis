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

from bson import ObjectId
from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase

from app.config import settings

logger = logging.getLogger("o365-browser-pool.storage")


def _oid(value: str | None):
    """Convert a hex string to ObjectId when possible — Kotlin expects ObjectId.

    Stored IDs (connectionId, clientId) must match the converter on the Kotlin
    side (MongoValueClassConverters). If the string isn't a valid ObjectId, we
    fall back to the raw string so legacy data still works.
    """
    if not value:
        return value
    try:
        return ObjectId(value)
    except Exception:
        return value


class ScrapeStorage:
    """Stores VLM scrape results in MongoDB."""

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._db: AsyncIOMotorDatabase | None = None

    async def start(self) -> None:
        # Build URI from individual env-var settings (configmap + secret)
        uri = (
            f"mongodb://{settings.mongodb_user}:{settings.mongodb_password}"
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

        resources = self._db["o365_discovered_resources"]
        await resources.create_index(
            [("connectionId", 1), ("externalId", 1)],
            unique=True,
            name="connection_resource_unique",
        )
        await resources.create_index(
            [("connectionId", 1), ("resourceType", 1)],
            name="connection_type_idx",
        )

        ledger = self._db["o365_message_ledger"]
        await ledger.create_index(
            [("connectionId", 1), ("chatId", 1)],
            unique=True,
            name="connection_chat_unique",
        )
        await ledger.create_index(
            [("connectionId", 1), ("unreadCount", -1)],
            name="connection_unread_idx",
        )

        calendar = self._db["scraped_calendar"]
        await calendar.create_index(
            [("connectionId", 1), ("externalId", 1)],
            unique=True,
            name="connection_event_unique",
        )

        mail = self._db["scraped_mail"]
        await mail.create_index(
            [("connectionId", 1), ("externalId", 1)],
            unique=True,
            name="connection_mail_unique",
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
        tab_name: str,
        data: dict,
    ) -> None:
        """Upsert latest scrape result keyed by free-form tab name chosen by
        the agent (`chat`, `mail`, `calendar`, …)."""
        if self._db is None:
            return

        now = datetime.now(timezone.utc)
        await self._db["o365_scrape_results"].update_one(
            {"clientId": _oid(client_id), "tabType": tab_name},
            {
                "$set": {
                    "connectionId": _oid(connection_id),
                    "data": data,
                    "scrapedAt": now,
                    "updatedAt": now,
                },
                "$setOnInsert": {
                    "clientId": _oid(client_id),
                    "tabType": tab_name,
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
        message_type: str = "chat",
    ) -> int:
        """Store individual messages extracted from VLM scrape.

        Messages are deduplicated by connectionId + messageHash.
        Returns count of newly inserted messages.

        Args:
            message_type: "chat", "email", or "calendar"
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
                    {"connectionId": _oid(connection_id), "messageHash": msg_hash},
                    {
                        "$setOnInsert": {
                            "clientId": _oid(client_id),
                            "connectionId": _oid(connection_id),
                            "messageHash": msg_hash,
                            "sender": msg.get("sender"),
                            "content": msg.get("content"),
                            "timestamp": msg.get("time"),
                            "chatName": msg.get("chat_name"),
                            "messageType": message_type,
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
                "Stored %d new %s messages for %s",
                inserted, message_type, client_id,
            )

        return inserted

    async def store_chat_messages(
        self,
        client_id: str,
        connection_id: str,
        messages: list[dict],
    ) -> int:
        """Backward-compatible wrapper for store_messages with type=chat."""
        return await self.store_messages(client_id, connection_id, messages, "chat")

    async def store_message_row(
        self,
        *,
        connection_id: str,
        client_id: str,
        chat_id: str,
        chat_name: str,
        message_id: str,
        sender: str,
        content: str,
        timestamp: str | None,
        is_mention: bool,
        is_self: bool = False,
        attachment_kind: str | None,
    ) -> bool:
        """Upsert a single observed message (agent-composed scraping).

        Dedup key: (connectionId, messageHash) — matches the Kotlin
        indexer's unique index. `messageHash` is set to `message_id` when
        present, otherwise a content hash is computed.
        """
        if self._db is None:
            return False
        import hashlib as _hashlib
        now = datetime.now(timezone.utc)
        msg_hash = message_id or _hashlib.sha256(
            f"{sender}|{timestamp}|{content}".encode(),
        ).hexdigest()[:16]
        try:
            result = await self._db["o365_scrape_messages"].update_one(
                {"connectionId": _oid(connection_id), "messageHash": msg_hash},
                {
                    "$setOnInsert": {
                        "connectionId": _oid(connection_id),
                        "clientId": _oid(client_id),
                        "messageHash": msg_hash,
                        "chatId": chat_id,
                        "chatName": chat_name,
                        "sender": sender,
                        "content": content,
                        "timestamp": timestamp,
                        "isMention": is_mention,
                        "isSelf": is_self,
                        "attachmentKind": attachment_kind,
                        "messageType": "chat",
                        "state": "NEW",
                        "createdAt": now,
                    },
                },
                upsert=True,
            )
            return bool(result.upserted_id)
        except Exception:
            return False

    async def store_discovered_resource(
        self,
        *,
        connection_id: str,
        client_id: str,
        resource_type: str,
        external_id: str,
        display_name: str,
        team_name: str | None = None,
        description: str | None = None,
    ) -> bool:
        """Upsert a single discovered resource (chat, channel, team)."""
        return (await self.store_discovered_resources(
            connection_id, client_id,
            [{
                "id": external_id,
                "name": display_name,
                "description": description,
                "type": resource_type,
                "team_name": team_name,
            }],
        )) > 0

    async def store_calendar_event(
        self,
        *,
        connection_id: str,
        client_id: str,
        external_id: str,
        title: str,
        start: str | None,
        end: str | None,
        organizer: str | None,
        join_url: str | None,
    ) -> bool:
        """Upsert an observed calendar event."""
        if self._db is None:
            return False
        now = datetime.now(timezone.utc)
        try:
            result = await self._db["scraped_calendar"].update_one(
                {"connectionId": _oid(connection_id), "externalId": external_id},
                {
                    "$set": {
                        "clientId": _oid(client_id),
                        "title": title,
                        "startAt": start,
                        "endAt": end,
                        "organizer": organizer,
                        "joinUrl": join_url,
                        "updatedAt": now,
                    },
                    "$setOnInsert": {
                        "connectionId": _oid(connection_id),
                        "externalId": external_id,
                        "createdAt": now,
                    },
                },
                upsert=True,
            )
            return bool(result.upserted_id)
        except Exception:
            return False

    async def store_mail_header(
        self,
        *,
        connection_id: str,
        client_id: str,
        external_id: str,
        sender: str,
        subject: str,
        received_at: str | None,
        preview: str,
        is_unread: bool,
    ) -> bool:
        """Upsert an observed mail header (metadata only, no body)."""
        if self._db is None:
            return False
        now = datetime.now(timezone.utc)
        try:
            result = await self._db["scraped_mail"].update_one(
                {"connectionId": _oid(connection_id), "externalId": external_id},
                {
                    "$set": {
                        "clientId": _oid(client_id),
                        "sender": sender,
                        "subject": subject,
                        "receivedAt": received_at,
                        "preview": preview,
                        "isUnread": is_unread,
                        "updatedAt": now,
                    },
                    "$setOnInsert": {
                        "connectionId": _oid(connection_id),
                        "externalId": external_id,
                        "createdAt": now,
                    },
                },
                upsert=True,
            )
            return bool(result.upserted_id)
        except Exception:
            return False

    async def store_discovered_resources(
        self,
        connection_id: str,
        client_id: str,
        resources: list[dict],
    ) -> int:
        """Store discovered Teams resources (chats, channels, teams) in MongoDB.

        Upserts by connectionId + externalId. Returns count of new resources.
        """
        if self._db is None or not resources:
            return 0

        now = datetime.now(timezone.utc)
        inserted = 0

        for res in resources:
            external_id = res.get("id", "")
            if not external_id:
                continue

            try:
                result = await self._db["o365_discovered_resources"].update_one(
                    {"connectionId": _oid(connection_id), "externalId": external_id},
                    {
                        "$set": {
                            "displayName": res.get("name", ""),
                            "description": res.get("description"),
                            "resourceType": res.get("type", "chat"),
                            "teamName": res.get("team_name"),
                            "lastSeenAt": now,
                            "active": True,
                        },
                        "$setOnInsert": {
                            "connectionId": _oid(connection_id),
                            "clientId": _oid(client_id),
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
                "Discovered %d new resources for connection %s",
                inserted, connection_id,
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

    async def ledger_upsert(
        self,
        connection_id: str,
        client_id: str,
        chat_id: str,
        chat_name: str,
        *,
        is_direct: bool,
        is_group: bool,
        last_message_at: datetime | None,
        unread_count: int,
        unread_direct_count: int,
    ) -> None:
        """Upsert per-chat state. Pod is the sole writer."""
        if self._db is None:
            return
        now = datetime.now(timezone.utc)
        await self._db["o365_message_ledger"].update_one(
            {"connectionId": _oid(connection_id), "chatId": chat_id},
            {
                "$set": {
                    "clientId": _oid(client_id),
                    "chatName": chat_name,
                    "isDirect": is_direct,
                    "isGroup": is_group,
                    "lastMessageAt": last_message_at,
                    "unreadCount": unread_count,
                    "unreadDirectCount": unread_direct_count,
                    "updatedAt": now,
                },
                "$setOnInsert": {
                    "connectionId": _oid(connection_id),
                    "chatId": chat_id,
                    "createdAt": now,
                },
            },
            upsert=True,
        )

    async def ledger_mark_seen(
        self,
        connection_id: str,
        chat_id: str,
    ) -> None:
        """Reset unread counters for a chat after the pod finished reading it."""
        if self._db is None:
            return
        now = datetime.now(timezone.utc)
        await self._db["o365_message_ledger"].update_one(
            {"connectionId": _oid(connection_id), "chatId": chat_id},
            {"$set": {
                "lastSeenAt": now,
                "unreadCount": 0,
                "unreadDirectCount": 0,
            }},
        )

    async def ledger_mark_urgent_sent(
        self,
        connection_id: str,
        chat_id: str,
    ) -> None:
        """Record that a push was emitted for this chat (dedup anchor)."""
        if self._db is None:
            return
        now = datetime.now(timezone.utc)
        await self._db["o365_message_ledger"].update_one(
            {"connectionId": _oid(connection_id), "chatId": chat_id},
            {"$set": {"lastUrgentAt": now, "lastNotifiedAt": now}},
        )

    async def ledger_get(
        self,
        connection_id: str,
        chat_id: str,
    ) -> dict | None:
        if self._db is None:
            return None
        return await self._db["o365_message_ledger"].find_one(
            {"connectionId": _oid(connection_id), "chatId": chat_id},
            {"_id": 0},
        )

    async def chat_sync_state(
        self,
        connection_id: str,
        chat_id: str,
        sample_size: int = 20,
    ) -> dict:
        """Summary of what we already scraped for a given chat.

        Returned to the agent so it can skip messages that are already in
        Mongo. Caller scrolls the chat UI until it hits one of the known
        message hashes (or the known timestamp) and stops — everything
        newer is stored via `store_message`. On first scrape
        `message_count` is 0 and the agent paginates back as deep as
        configured.

        - `message_count`: total stored messages for this (connection, chat).
        - `last_message_timestamp`: ISO timestamp of the newest stored row.
        - `known_message_hashes`: up to `sample_size` of the most recent
          messageHash values; the agent matches these against DOM
          `data-mid` attributes to detect the resume point.
        """
        empty = {
            "message_count": 0,
            "last_message_timestamp": None,
            "known_message_hashes": [],
        }
        if self._db is None:
            return empty
        conn_oid = _oid(connection_id)
        collection = self._db["o365_scrape_messages"]
        count = await collection.count_documents({
            "connectionId": conn_oid,
            "chatName": chat_id,
        })
        if count == 0:
            return empty
        cursor = collection.find(
            {"connectionId": conn_oid, "chatName": chat_id},
            {"messageHash": 1, "timestamp": 1, "_id": 0},
        ).sort("timestamp", -1).limit(sample_size)
        rows = [r async for r in cursor]
        return {
            "message_count": count,
            "last_message_timestamp": rows[0].get("timestamp") if rows else None,
            "known_message_hashes": [
                r["messageHash"] for r in rows if r.get("messageHash")
            ],
        }

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
