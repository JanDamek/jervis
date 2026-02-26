"""EPIC 9-S2: Topic-Aware Memory Consolidation.

When conversation history grows large, this module consolidates rolling
block summaries into topic-aware consolidated memories. Instead of generic
block-level summaries, it groups related summaries by topic/affair and
creates hierarchical topic-level summaries.

Integration points:
- Called by ChatContextAssembler when summary_blocks exceed threshold
- Called during affair parking (affairs.py) for affair-level consolidation
- Can be triggered by BackgroundEngine as periodic maintenance task
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone

from app.config import estimate_tokens
from app.models import ModelTier

logger = logging.getLogger(__name__)

# MongoDB collection for consolidated memory blocks
_CONSOLIDATION_COLLECTION = "consolidated_memories"

# Trigger consolidation when summary blocks exceed this count
CONSOLIDATION_THRESHOLD = 12

# Maximum consolidated blocks to keep per conversation
MAX_CONSOLIDATED_BLOCKS = 8


async def should_consolidate(
    session_id: str,
    summary_block_count: int,
) -> bool:
    """Check if conversation summaries need consolidation.

    Triggers when rolling block summaries exceed CONSOLIDATION_THRESHOLD.
    """
    if summary_block_count < CONSOLIDATION_THRESHOLD:
        return False

    # Check if we already consolidated recently
    try:
        from app.tools.kotlin_client import get_mongo_db
        db = await get_mongo_db()
        collection = db[_CONSOLIDATION_COLLECTION]
        existing = await collection.count_documents({"conversationId": session_id})
        # Don't re-consolidate if we already have enough blocks
        return existing < MAX_CONSOLIDATED_BLOCKS
    except Exception as e:
        logger.warning("Consolidation check failed: %s (skipping)", e)
        return False


async def consolidate_summaries(
    session_id: str,
    summary_blocks: list[dict],
) -> list[dict]:
    """Consolidate rolling block summaries into topic-aware blocks.

    Groups summaries by their topics, then produces a higher-level
    summary per topic group. The result is stored in MongoDB and
    replaces the original blocks in context assembly.

    Args:
        session_id: Conversation ID.
        summary_blocks: List of SummaryBlock-like dicts with keys:
            sequence_range, summary, key_decisions, topics, is_checkpoint

    Returns:
        List of consolidated block dicts.
    """
    if len(summary_blocks) < CONSOLIDATION_THRESHOLD:
        return summary_blocks

    logger.info("Consolidating %d summary blocks for session=%s", len(summary_blocks), session_id)

    # Step 1: Group summaries by topic
    topic_groups = _group_by_topic(summary_blocks)

    # Step 2: Consolidate each topic group via LLM
    consolidated = []
    for topic, blocks in topic_groups.items():
        if len(blocks) < 2:
            # Single block — keep as-is
            consolidated.append(blocks[0])
            continue

        try:
            merged = await _merge_topic_blocks(topic, blocks)
            consolidated.append(merged)
        except Exception as e:
            logger.warning("Failed to consolidate topic '%s': %s (keeping originals)", topic, e)
            # Keep the most recent block as fallback
            consolidated.append(blocks[-1])

    # Step 3: Sort by sequence range start
    consolidated.sort(key=lambda b: _parse_sequence_start(b.get("sequence_range", "0-0")))

    # Step 4: Persist to MongoDB
    await _persist_consolidated(session_id, consolidated)

    logger.info("Consolidated %d blocks → %d blocks for session=%s",
                len(summary_blocks), len(consolidated), session_id)
    return consolidated


def _group_by_topic(blocks: list[dict]) -> dict[str, list[dict]]:
    """Group summary blocks by their primary topic.

    Blocks with multiple topics go to the first topic.
    Blocks with no topics go to "_general".
    """
    groups: dict[str, list[dict]] = {}

    for block in blocks:
        topics = block.get("topics", [])
        if isinstance(topics, str):
            topics = [t.strip() for t in topics.split(",") if t.strip()]

        primary_topic = topics[0] if topics else "_general"

        if primary_topic not in groups:
            groups[primary_topic] = []
        groups[primary_topic].append(block)

    return groups


async def _merge_topic_blocks(topic: str, blocks: list[dict]) -> dict:
    """Merge multiple summary blocks for the same topic into one consolidated block."""
    from app.chat.handler_streaming import call_llm

    # Build context from all blocks
    block_texts = []
    all_decisions = []
    all_topics = set()
    seq_ranges = []

    for block in blocks:
        block_texts.append(block.get("summary", ""))
        all_decisions.extend(block.get("key_decisions", []))
        topics = block.get("topics", [])
        if isinstance(topics, str):
            topics = [t.strip() for t in topics.split(",") if t.strip()]
        all_topics.update(topics)
        seq_ranges.append(block.get("sequence_range", ""))

    combined_text = "\n---\n".join(block_texts)

    prompt = (
        f"Konsoliduj tyto {len(blocks)} souhrnů konverzace pro téma '{topic}' "
        f"do jednoho uceleného shrnutí.\n\n"
        f"SOUHRNY:\n{combined_text[:3000]}\n\n"
        f"KLÍČOVÁ ROZHODNUTÍ: {json.dumps(all_decisions[:10], ensure_ascii=False)}\n\n"
        "Odpověz POUZE validním JSON:\n"
        '{\n'
        '  "summary": "2-4 věty shrnující vývoj tématu",\n'
        '  "key_decisions": ["rozhodnutí 1", "rozhodnutí 2"],\n'
        '  "timeline": ["co se stalo nejdřív", "pak...", "naposledy..."]\n'
        '}'
    )

    response = await call_llm(
        messages=[
            {"role": "system", "content": "You are a memory consolidation assistant. Respond with valid JSON only."},
            {"role": "user", "content": prompt},
        ],
        tier=ModelTier.LOCAL_FAST,
        max_tokens=512,
        temperature=0.1,
        timeout=10.0,
    )

    content = response.choices[0].message.content or ""
    content = content.strip()
    if content.startswith("```"):
        content = content.split("\n", 1)[-1].rsplit("```", 1)[0].strip()

    try:
        result = json.loads(content)
    except json.JSONDecodeError:
        result = {"summary": content[:500]}

    # Build consolidated block
    first_seq = _parse_sequence_start(seq_ranges[0]) if seq_ranges else 0
    last_seq = _parse_sequence_end(seq_ranges[-1]) if seq_ranges else 0

    return {
        "sequence_range": f"{first_seq}-{last_seq}",
        "summary": result.get("summary", combined_text[:500]),
        "key_decisions": result.get("key_decisions", all_decisions[:5]),
        "topics": list(all_topics),
        "is_checkpoint": any(b.get("is_checkpoint") for b in blocks),
        "checkpoint_reason": None,
        "consolidated": True,
        "consolidation_level": 1,
        "source_block_count": len(blocks),
        "timeline": result.get("timeline", []),
    }


async def consolidate_affair_messages(
    affair_messages: list[dict],
    affair_title: str,
    max_tokens: int = 2000,
) -> str:
    """Consolidate affair messages when affair grows too large.

    Used during affair parking to create a compact representation
    of the affair's message history.

    Returns consolidated summary text.
    """
    if not affair_messages:
        return ""

    total_tokens = sum(estimate_tokens(m.get("content", "")) for m in affair_messages)
    if total_tokens <= max_tokens:
        # Messages fit within budget — no consolidation needed
        return ""

    from app.chat.handler_streaming import call_llm

    # Take first 5 and last 5 messages for context
    head = affair_messages[:5]
    tail = affair_messages[-5:] if len(affair_messages) > 5 else []

    messages_text = "\n".join(
        f"[{m.get('role', 'user')}]: {m.get('content', '')[:300]}"
        for m in head + tail
    )

    prompt = (
        f"Záležitost: {affair_title}\n"
        f"Celkem {len(affair_messages)} zpráv ({total_tokens} tokenů).\n\n"
        f"ZPRÁVY (začátek + konec):\n{messages_text}\n\n"
        "Vytvoř konsolidovaný souhrn celé konverzace k této záležitosti. "
        "Zachovej klíčové fakta, rozhodnutí a otevřené otázky. "
        "Max 500 znaků."
    )

    try:
        response = await call_llm(
            messages=[
                {"role": "system", "content": "Summarize the conversation thread concisely."},
                {"role": "user", "content": prompt},
            ],
            tier=ModelTier.LOCAL_FAST,
            max_tokens=256,
            temperature=0.1,
            timeout=8.0,
        )
        return response.choices[0].message.content or ""
    except Exception as e:
        logger.warning("Affair message consolidation failed: %s", e)
        return ""


async def _persist_consolidated(session_id: str, blocks: list[dict]) -> None:
    """Persist consolidated blocks to MongoDB."""
    try:
        from app.tools.kotlin_client import get_mongo_db
        db = await get_mongo_db()
        collection = db[_CONSOLIDATION_COLLECTION]
        now = datetime.now(timezone.utc).isoformat()

        # Replace existing consolidated blocks for this session
        await collection.delete_many({"conversationId": session_id})

        if blocks:
            docs = [
                {
                    "conversationId": session_id,
                    "sequenceRange": b.get("sequence_range", ""),
                    "summary": b.get("summary", ""),
                    "keyDecisions": b.get("key_decisions", []),
                    "topics": b.get("topics", []),
                    "isCheckpoint": b.get("is_checkpoint", False),
                    "consolidated": b.get("consolidated", False),
                    "consolidationLevel": b.get("consolidation_level", 0),
                    "sourceBlockCount": b.get("source_block_count", 1),
                    "timeline": b.get("timeline", []),
                    "createdAt": now,
                }
                for b in blocks
            ]
            await collection.insert_many(docs)

        logger.debug("Persisted %d consolidated blocks for session=%s", len(blocks), session_id)
    except Exception as e:
        logger.warning("Failed to persist consolidated blocks: %s (non-fatal)", e)


def _parse_sequence_start(seq_range: str) -> int:
    """Parse start number from "123-456" format."""
    try:
        return int(seq_range.split("-")[0])
    except (ValueError, IndexError):
        return 0


def _parse_sequence_end(seq_range: str) -> int:
    """Parse end number from "123-456" format."""
    try:
        parts = seq_range.split("-")
        return int(parts[-1]) if len(parts) > 1 else int(parts[0])
    except (ValueError, IndexError):
        return 0
