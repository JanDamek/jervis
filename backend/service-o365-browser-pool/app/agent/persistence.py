"""MongoDB checkpointer for the LangGraph pod agent.

Namespaced separately from the orchestrator (`langgraph_checkpoints_pod`
collection) so concurrent writes never collide. thread_id = connection_id —
agent state survives pod restart and resumes mid-conversation.
"""

from __future__ import annotations

import logging
import os

from langgraph.checkpoint.mongodb import MongoDBSaver
from pymongo import MongoClient

logger = logging.getLogger("o365-browser-pool.persistence")

_CHECKPOINT_DB = "jervis"
_CHECKPOINT_COLLECTION = "langgraph_checkpoints_pod"
_CHECKPOINT_WRITES_COLLECTION = "langgraph_checkpoint_writes_pod"

_mongo_client: MongoClient | None = None
_checkpointer: MongoDBSaver | None = None


def _build_mongo_url() -> str:
    """Synthesize a mongodb:// URL from the same env vars scrape_storage uses."""
    host = os.environ.get("MONGODB_HOST", "nas.lan.mazlusek.com")
    port = int(os.environ.get("MONGODB_PORT", 27017))
    user = os.environ.get("MONGODB_USER", "root")
    password = os.environ.get("MONGODB_PASSWORD", "")
    db = os.environ.get("MONGODB_DATABASE", "jervis")
    auth_db = os.environ.get("MONGODB_AUTH_DB", "admin")
    return (
        f"mongodb://{user}:{password}@{host}:{port}/{db}?authSource={auth_db}"
    )


def init_checkpointer() -> MongoDBSaver:
    """Initialize the MongoDB checkpointer (idempotent)."""
    global _mongo_client, _checkpointer
    if _checkpointer is not None:
        return _checkpointer
    _mongo_client = MongoClient(_build_mongo_url())
    _checkpointer = MongoDBSaver(
        client=_mongo_client,
        db_name=_CHECKPOINT_DB,
        checkpoint_collection_name=_CHECKPOINT_COLLECTION,
        writes_collection_name=_CHECKPOINT_WRITES_COLLECTION,
    )
    logger.info(
        "LangGraph MongoDB checkpointer ready: db=%s col=%s",
        _CHECKPOINT_DB, _CHECKPOINT_COLLECTION,
    )
    return _checkpointer


def get_checkpointer() -> MongoDBSaver:
    if _checkpointer is None:
        return init_checkpointer()
    return _checkpointer


def shutdown_checkpointer() -> None:
    global _mongo_client, _checkpointer
    if _mongo_client is not None:
        try:
            _mongo_client.close()
        except Exception:
            pass
    _mongo_client = None
    _checkpointer = None
