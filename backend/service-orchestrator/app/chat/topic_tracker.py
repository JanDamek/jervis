"""EPIC 9-S1: Topic Tracker — LLM-based topic detection for conversations.

Detects and tracks topics within conversations using lightweight LLM classification.
Topics are stored as metadata in chat_messages and chat_summaries.

Integration points:
- Called after final response in agentic loop (handler_agentic.py)
- Uses topic data from existing compression (context.py chat_summaries.topics)
- Updates conversation topic registry in MongoDB
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone

from bson import ObjectId

from app.config import estimate_tokens
from app.models import ModelTier

logger = logging.getLogger(__name__)

# MongoDB collection for conversation topics
_TOPIC_COLLECTION = "conversation_topics"


async def detect_topics(
    user_message: str,
    assistant_response: str,
    used_tools: list[str] | None = None,
) -> list[dict]:
    """Detect topics from user message and assistant response.

    Uses lightweight LLM call (LOCAL_FAST) to extract 1-3 topic labels.
    Falls back to tool-domain heuristic if LLM fails.

    Returns:
        List of {label: str, type: str} dicts.
    """
    # Fast heuristic path for short messages
    if len(user_message) < 50 and not used_tools:
        return []

    try:
        topics = await _llm_detect_topics(user_message, assistant_response)
        if topics:
            return topics
    except Exception as e:
        logger.warning("Topic detection LLM failed: %r (%s)", e, type(e).__name__)
        logger.debug("Topic detection LLM traceback:", exc_info=True)

    # Fallback: tool-domain based topics
    if used_tools:
        return _topics_from_tools(used_tools)

    return []


async def _llm_detect_topics(
    user_message: str,
    assistant_response: str,
) -> list[dict]:
    """LLM-based topic extraction."""
    from app.chat.handler_streaming import call_llm

    prompt = (
        "Z následující konverzační výměny extrahuj 1-3 hlavní témata.\n\n"
        f"UŽIVATEL: {user_message[:500]}\n\n"
        f"ASISTENT: {assistant_response[:500]}\n\n"
        "Odpověz POUZE validním JSON polem:\n"
        '[{"label": "krátký název tématu", "type": "typ"}, ...]\n\n'
        'Typy: "task", "question", "discussion", "bug_report", '
        '"code_review", "planning", "admin", "greeting"\n\n'
        "JSON:"
    )

    response = await call_llm(
        messages=[
            {"role": "system", "content": "Extract conversation topics. Respond with JSON array only."},
            {"role": "user", "content": prompt},
        ],
        tier=ModelTier.LOCAL_FAST,
        max_tokens=256,
        temperature=0.1,
        timeout=5.0,
    )

    content = response.choices[0].message.content or ""
    # Strip markdown fences if present
    content = content.strip()
    if content.startswith("```"):
        content = content.split("\n", 1)[-1].rsplit("```", 1)[0].strip()

    topics = json.loads(content)
    if isinstance(topics, list):
        return [
            {"label": t.get("label", "")[:100], "type": t.get("type", "discussion")}
            for t in topics
            if isinstance(t, dict) and t.get("label")
        ][:3]

    return []


def _topics_from_tools(used_tools: list[str]) -> list[dict]:
    """Derive topics from tool usage patterns."""
    _TOOL_TOPIC_MAP = {
        "kb_search": ("Knowledge Base", "question"),
        "code_search": ("Kód", "code_review"),
        "brain_search": ("Brain JIRA", "task"),
        "brain_create_issue": ("Brain JIRA", "task"),
        "create_background_task": ("Správa úkolů", "planning"),
        "dispatch_coding_agent": ("Coding", "task"),
        "store_knowledge": ("Knowledge Base", "admin"),
        "memory_store": ("Paměť", "admin"),
        "web_search": ("Výzkum", "question"),
    }

    seen = set()
    topics = []
    for tool in used_tools:
        if tool in _TOOL_TOPIC_MAP and tool not in seen:
            label, topic_type = _TOOL_TOPIC_MAP[tool]
            topics.append({"label": label, "type": topic_type})
            seen.add(tool)
    return topics[:3]


async def update_conversation_topics(
    session_id: str,
    detected_topics: list[dict],
) -> None:
    """Update topic registry for a conversation in MongoDB.

    Creates or updates ConversationTopic documents. Tracks first/last mention
    and message count per topic.
    """
    if not detected_topics:
        return

    try:
        from app.tools.kotlin_client import get_mongo_db
        db = await get_mongo_db()
        collection = db[_TOPIC_COLLECTION]
        now = datetime.now(timezone.utc).isoformat()

        for topic in detected_topics:
            label = topic["label"]
            # Upsert: increment messageCount, update lastMentionedAt
            result = await collection.update_one(
                {"conversationId": session_id, "label": label},
                {
                    "$inc": {"messageCount": 1},
                    "$set": {"lastMentionedAt": now, "type": topic.get("type", "discussion")},
                    "$setOnInsert": {
                        "id": str(ObjectId()),
                        "conversationId": session_id,
                        "label": label,
                        "firstMentionedAt": now,
                        "relatedEntities": [],
                    },
                },
                upsert=True,
            )
            logger.debug("Topic upsert: label=%s matched=%d", label, result.matched_count)

    except Exception as e:
        logger.warning("Failed to update conversation topics: %s (non-fatal)", e)


async def get_conversation_topics(session_id: str) -> list[dict]:
    """Retrieve all topics for a conversation (sorted by message count desc)."""
    try:
        from app.tools.kotlin_client import get_mongo_db
        db = await get_mongo_db()
        collection = db[_TOPIC_COLLECTION]
        cursor = collection.find(
            {"conversationId": session_id},
        ).sort("messageCount", -1).limit(20)
        return [doc async for doc in cursor]
    except Exception as e:
        logger.warning("Failed to get conversation topics: %s", e)
        return []


def topic_metadata(topics: list[dict]) -> dict:
    """Build metadata dict for save_assistant_message from detected topics."""
    if not topics:
        return {}
    labels = ",".join(t.get("label", "") for t in topics[:3])
    return {"topics": labels} if labels else {}
