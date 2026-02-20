"""ChatContextAssembler — builds chat context for LLM from MongoDB directly.

Replaces Kotlin ChatHistoryService.prepareChatHistoryPayload() + compressIfNeeded().
Python owns the full pipeline: load → count tokens → budget → compress → assemble.

Architecture:
    MongoDB (chat_messages + chat_summaries collections)
      → ChatContextAssembler.assemble_context(task_id)
        → list[dict] messages ready for LLM

Why Python owns this:
    - Token counting: tiktoken (precise) vs Kotlin chars/4 (wrong for Czech)
    - LLM calls: Python calls Ollama — knows exact model, context window
    - Compression: Python calls LLM directly — no round-trip through Kotlin
    - Principle: who calls LLM, builds context
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase
from bson import ObjectId

from app.config import settings
from app.models import ModelTier

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Token budget for chat context within LLM call
# Total context window ~32k (local_standard) → reserve for system prompt + response
SYSTEM_PROMPT_RESERVE = 4000  # tokens reserved for system prompt
RESPONSE_RESERVE = 4000       # tokens reserved for LLM response
TOTAL_BUDGET = 40000          # target total context window

CHAT_CONTEXT_BUDGET = TOTAL_BUDGET - SYSTEM_PROMPT_RESERVE - RESPONSE_RESERVE
# = 32000 tokens for chat context (summaries + recent messages)

RECENT_MESSAGE_COUNT = 20     # max recent messages to consider
MAX_SUMMARY_BLOCKS = 15       # max compressed summary blocks to include
COMPRESS_THRESHOLD = 20       # compress when this many unsummarized messages exist

# Rough token estimation: ~1 token per 3.5 chars for Czech/mixed text
# More precise than Kotlin's chars/4, but we'll use tiktoken when available
CHARS_PER_TOKEN = 3.5


# ---------------------------------------------------------------------------
# Token counting
# ---------------------------------------------------------------------------

_tokenizer = None


def _get_tokenizer():
    """Lazy-load tiktoken tokenizer. Falls back to char estimation."""
    global _tokenizer
    if _tokenizer is not None:
        return _tokenizer
    try:
        import tiktoken
        # cl100k_base works well for Qwen models too
        _tokenizer = tiktoken.get_encoding("cl100k_base")
        logger.info("CHAT_CONTEXT: Using tiktoken (cl100k_base) for token counting")
    except ImportError:
        logger.warning("CHAT_CONTEXT: tiktoken not available, using char estimation")
        _tokenizer = False  # Sentinel: tried but failed
    return _tokenizer


def count_tokens(text: str) -> int:
    """Count tokens in text. Uses tiktoken if available, else char estimation."""
    tokenizer = _get_tokenizer()
    if tokenizer and tokenizer is not False:
        return len(tokenizer.encode(text))
    return int(len(text) / CHARS_PER_TOKEN)


# ---------------------------------------------------------------------------
# Error message detection (mirrors Kotlin ChatHistoryService logic)
# ---------------------------------------------------------------------------

def _is_error_message(content: str) -> bool:
    """Check if message content is an error that should be filtered out."""
    lower = content.strip().lower()
    if lower.startswith("{") and '"error"' in lower:
        return True
    if lower.startswith("error:") or lower.startswith("chyba:"):
        return True
    if "llm_call_failed" in lower:
        return True
    if "operation not allowed" in lower:
        return True
    return False


# ---------------------------------------------------------------------------
# ChatContextAssembler
# ---------------------------------------------------------------------------

class ChatContextAssembler:
    """Assembles chat context for LLM directly from MongoDB.

    Usage:
        assembler = ChatContextAssembler()
        await assembler.init()

        # In graph node:
        messages = await assembler.assemble_context(task_id)
        # → list[dict] with role/content for LLM

        # After each exchange:
        await assembler.maybe_compress(task_id)
    """

    def __init__(self):
        self._client: AsyncIOMotorClient | None = None
        self._db: AsyncIOMotorDatabase | None = None

    async def init(self):
        """Initialize MongoDB connection (motor async driver)."""
        self._client = AsyncIOMotorClient(settings.mongodb_url)
        self._db = self._client.get_default_database()
        logger.info("ChatContextAssembler: MongoDB connected (database=%s)", self._db.name)

    async def close(self):
        """Close MongoDB connection."""
        if self._client:
            self._client.close()
            self._client = None
            self._db = None

    @property
    def _messages_col(self):
        return self._db["chat_messages"]

    @property
    def _summaries_col(self):
        return self._db["chat_summaries"]

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def assemble_context(self, task_id: str) -> list[dict]:
        """Build chat context messages for LLM from MongoDB.

        Returns list[dict] with {"role": ..., "content": ...} entries
        ready to insert into LLM messages array.

        Strategy (newest-first budget filling):
        1. Load summary blocks (compressed older history)
        2. Load recent messages (last RECENT_MESSAGE_COUNT)
        3. Fill budget: recent messages first (most relevant), then summaries
        4. Return as formatted context block
        """
        oid = self._to_object_id(task_id)
        if not oid:
            return []

        # 1. Load summary blocks (compressed older history)
        summaries = await self._load_summaries(oid)

        # 2. Load recent messages
        recent = await self._load_recent_messages(oid)

        if not recent and not summaries:
            return []

        # 3. Build context within token budget
        used_tokens = 0

        # 3a. Recent messages (highest priority — newest context)
        recent_parts: list[str] = []
        for msg in recent:
            content = msg["content"]
            if _is_error_message(content):
                continue
            label = {"user": "Uživatel", "assistant": "Jervis", "system": "Systém"}.get(
                msg["role"], msg["role"]
            )
            line = f"{label}: {content}"
            line_tokens = count_tokens(line)

            if used_tokens + line_tokens > CHAT_CONTEXT_BUDGET:
                logger.info(
                    "CHAT_CONTEXT: Budget reached at %d tokens (recent messages), "
                    "skipping remaining",
                    used_tokens,
                )
                break
            recent_parts.append(line)
            used_tokens += line_tokens

        # 3b. Summary blocks (fill remaining budget)
        summary_parts: list[str] = []
        for block in summaries:
            prefix = "[CHECKPOINT] " if block.get("isCheckpoint", False) else ""
            seq_range = f"{block['sequenceStart']}-{block['sequenceEnd']}"
            line = f"{prefix}Messages {seq_range}: {block['summary']}"
            line_tokens = count_tokens(line)

            if used_tokens + line_tokens > CHAT_CONTEXT_BUDGET:
                logger.info(
                    "CHAT_CONTEXT: Budget reached at %d tokens (summaries), "
                    "skipping remaining",
                    used_tokens,
                )
                break
            summary_parts.append(line)
            used_tokens += line_tokens

        # 4. Combine: summaries first (chronological), then recent messages
        result_parts = []
        if summary_parts:
            result_parts.append("## Conversation History (compressed)\n" + "\n".join(summary_parts))
        if recent_parts:
            result_parts.append("## Recent Messages\n" + "\n\n".join(recent_parts))

        if not result_parts:
            return []

        total_msgs = await self._count_messages(oid)

        logger.info(
            "CHAT_CONTEXT_ASSEMBLED | task_id=%s | recent=%d | summaries=%d | "
            "tokens=%d/%d | total_messages=%d",
            task_id, len(recent_parts), len(summary_parts),
            used_tokens, CHAT_CONTEXT_BUDGET, total_msgs,
        )

        # Return as a single system-context message for the LLM
        return [{
            "role": "system",
            "content": (
                "Conversation context (from previous messages in this task):\n\n"
                + "\n\n".join(result_parts)
            ),
        }]

    async def assemble_raw(self, task_id: str) -> dict:
        """Build raw chat history payload (backward-compatible with Kotlin format).

        Returns dict matching ChatHistoryPayload structure:
        {
            "recent_messages": [...],
            "summary_blocks": [...],
            "total_message_count": int
        }

        Used by graph nodes that still read state["chat_history"] directly.
        """
        oid = self._to_object_id(task_id)
        if not oid:
            return {"recent_messages": [], "summary_blocks": [], "total_message_count": 0}

        summaries = await self._load_summaries(oid)
        recent = await self._load_recent_messages(oid)
        total = await self._count_messages(oid)

        # Format to match Kotlin ChatHistoryPayloadDto
        recent_msgs = [
            {
                "role": msg["role"],
                "content": msg["content"],
                "timestamp": msg["timestamp"].isoformat() if isinstance(msg["timestamp"], datetime) else str(msg["timestamp"]),
                "sequence": msg["sequence"],
            }
            for msg in recent
            if not _is_error_message(msg["content"])
        ]

        summary_blocks = [
            {
                "sequence_range": f"{s['sequenceStart']}-{s['sequenceEnd']}",
                "summary": s["summary"],
                "key_decisions": s.get("keyDecisions", []),
                "topics": s.get("topics", []),
                "is_checkpoint": s.get("isCheckpoint", False),
                "checkpoint_reason": s.get("checkpointReason"),
            }
            for s in summaries
        ]

        logger.info(
            "CHAT_CONTEXT_RAW | task_id=%s | recent=%d | summaries=%d | total=%d",
            task_id, len(recent_msgs), len(summary_blocks), total,
        )

        return {
            "recent_messages": recent_msgs,
            "summary_blocks": summary_blocks,
            "total_message_count": total,
        }

    async def maybe_compress(self, task_id: str):
        """Check if compression is needed and trigger it (fire-and-forget).

        Called after each orchestration exchange. If ≥COMPRESS_THRESHOLD
        unsummarized messages exist before the recent window, compresses
        them via local LLM.

        Non-blocking: launches compression as asyncio task.
        """
        oid = self._to_object_id(task_id)
        if not oid:
            return

        total = await self._count_messages(oid)
        if total <= RECENT_MESSAGE_COUNT:
            return

        # Find last summarized sequence
        last_summary = await self._summaries_col.find_one(
            {"taskId": oid},
            sort=[("sequenceEnd", -1)],
        )
        last_summarized_seq = last_summary["sequenceEnd"] if last_summary else 0

        # Count unsummarized messages before recent window
        all_msgs = await self._messages_col.find(
            {"taskId": oid},
            {"sequence": 1},
        ).sort("sequence", 1).to_list(length=None)

        if len(all_msgs) <= RECENT_MESSAGE_COUNT:
            return

        recent_start_seq = all_msgs[-RECENT_MESSAGE_COUNT]["sequence"]
        unsummarized_count = sum(
            1 for m in all_msgs
            if m["sequence"] > last_summarized_seq and m["sequence"] < recent_start_seq
        )

        if unsummarized_count < COMPRESS_THRESHOLD:
            logger.debug(
                "COMPRESS_SKIP | task_id=%s | unsummarized=%d (<%d)",
                task_id, unsummarized_count, COMPRESS_THRESHOLD,
            )
            return

        logger.info(
            "COMPRESS_TRIGGER | task_id=%s | unsummarized=%d",
            task_id, unsummarized_count,
        )

        # Fire-and-forget compression
        asyncio.create_task(
            self._compress_block(oid, last_summarized_seq, recent_start_seq)
        )

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _to_object_id(self, task_id: str) -> ObjectId | None:
        """Convert string task_id to ObjectId for MongoDB queries."""
        try:
            return ObjectId(task_id)
        except Exception:
            logger.warning("CHAT_CONTEXT: Invalid task_id format: %s", task_id)
            return None

    async def _load_recent_messages(self, task_oid: ObjectId) -> list[dict]:
        """Load last RECENT_MESSAGE_COUNT messages from MongoDB."""
        cursor = self._messages_col.find(
            {"taskId": task_oid},
        ).sort("sequence", -1).limit(RECENT_MESSAGE_COUNT)

        messages = await cursor.to_list(length=RECENT_MESSAGE_COUNT)
        messages.reverse()  # Oldest first
        return messages

    async def _load_summaries(self, task_oid: ObjectId) -> list[dict]:
        """Load last MAX_SUMMARY_BLOCKS summaries from MongoDB."""
        cursor = self._summaries_col.find(
            {"taskId": task_oid},
        ).sort("sequenceEnd", -1).limit(MAX_SUMMARY_BLOCKS)

        summaries = await cursor.to_list(length=MAX_SUMMARY_BLOCKS)
        summaries.reverse()  # Oldest first (chronological)
        return summaries

    async def _count_messages(self, task_oid: ObjectId) -> int:
        """Count total messages for a task."""
        return await self._messages_col.count_documents({"taskId": task_oid})

    async def _compress_block(
        self,
        task_oid: ObjectId,
        after_sequence: int,
        before_sequence: int,
    ):
        """Compress messages between after_sequence and before_sequence.

        Calls local LLM to generate summary, stores ChatSummaryDocument.
        """
        try:
            from app.llm.provider import llm_provider

            # Load messages to compress
            messages = await self._messages_col.find(
                {
                    "taskId": task_oid,
                    "sequence": {"$gt": after_sequence, "$lt": before_sequence},
                },
            ).sort("sequence", 1).to_list(length=None)

            if not messages:
                return

            # Format for LLM
            formatted = []
            for m in messages:
                label = {"USER": "Uživatel", "ASSISTANT": "Jervis"}.get(
                    m.get("role", ""), m.get("role", "?")
                )
                formatted.append(f"[{label}]: {m['content'][:500]}")
            conversation_text = "\n".join(formatted)

            # Get previous summary for context
            prev_summary = await self._summaries_col.find_one(
                {"taskId": task_oid, "sequenceEnd": {"$lte": after_sequence}},
                sort=[("sequenceEnd", -1)],
            )
            previous_context = ""
            if prev_summary:
                previous_context = (
                    f"\n\nPředchozí kontext konverzace:\n{prev_summary['summary']}"
                )

            llm_messages = [
                {
                    "role": "system",
                    "content": (
                        "Jsi analytik konverzací. Tvůj úkol je shrnout blok konverzace "
                        "do stručného souhrnu.\n\n"
                        "Pravidla:\n"
                        "- Piš česky\n"
                        "- Souhrn: 2-3 věty shrnující hlavní téma a průběh (max 500 znaků)\n"
                        "- Klíčová rozhodnutí: důležitá rozhodnutí učiněná v konverzaci\n"
                        "- Témata: hlavní témata diskuze (stručné štítky)\n"
                        "- Pokud se směr konverzace ZÁSADNĚ změnil oproti předchozímu kontextu, "
                        "nastav is_checkpoint=true a uveď důvod\n\n"
                        "Odpověz JSON:\n"
                        "{\n"
                        '  "summary": "...",\n'
                        '  "key_decisions": ["rozhodnutí 1", "rozhodnutí 2"],\n'
                        '  "topics": ["téma 1", "téma 2"],\n'
                        '  "is_checkpoint": false,\n'
                        '  "checkpoint_reason": null\n'
                        "}"
                    ),
                },
                {
                    "role": "user",
                    "content": (
                        f"Shrň tento blok konverzace:{previous_context}\n\n"
                        f"Konverzace k shrnutí:\n{conversation_text}"
                    ),
                },
            ]

            response = await llm_provider.completion(
                messages=llm_messages,
                tier=ModelTier.LOCAL_FAST,
                max_tokens=2048,
                temperature=0.1,
            )
            content = response.choices[0].message.content

            # Parse JSON response (robust)
            parsed = self._parse_json_response(content)

            # Store summary document
            first_seq = messages[0]["sequence"]
            last_seq = messages[-1]["sequence"]

            await self._summaries_col.insert_one({
                "taskId": task_oid,
                "sequenceStart": first_seq,
                "sequenceEnd": last_seq,
                "summary": parsed.get("summary", content[:500]),
                "keyDecisions": parsed.get("key_decisions", []),
                "topics": parsed.get("topics", []),
                "isCheckpoint": parsed.get("is_checkpoint", False),
                "checkpointReason": parsed.get("checkpoint_reason"),
                "messageCount": len(messages),
                "createdAt": datetime.now(timezone.utc),
            })

            logger.info(
                "COMPRESS_DONE | task_id=%s | range=%d-%d | messages=%d | "
                "summary_len=%d",
                str(task_oid), first_seq, last_seq, len(messages),
                len(parsed.get("summary", "")),
            )

        except Exception as e:
            logger.warning(
                "COMPRESS_FAILED | task_id=%s | error=%s",
                str(task_oid), str(e),
            )

    @staticmethod
    def _parse_json_response(content: str) -> dict:
        """Parse LLM JSON response robustly."""
        try:
            return json.loads(content)
        except (json.JSONDecodeError, TypeError):
            pass

        # Try extracting from markdown code block
        match = re.search(r"```(?:json)?\s*\n?(.*?)\n?\s*```", content, re.DOTALL)
        if match:
            try:
                return json.loads(match.group(1))
            except (json.JSONDecodeError, TypeError):
                pass

        # Try extracting bare JSON object
        brace_start = content.find("{")
        brace_end = content.rfind("}")
        if brace_start != -1 and brace_end > brace_start:
            try:
                return json.loads(content[brace_start : brace_end + 1])
            except (json.JSONDecodeError, TypeError):
                pass

        return {"summary": content[:500]}


# ---------------------------------------------------------------------------
# Singleton instance
# ---------------------------------------------------------------------------

chat_context_assembler = ChatContextAssembler()
