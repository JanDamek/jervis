"""ChatContextAssembler — builds token-budgeted LLM context from MongoDB.

Replaces Kotlin ChatHistoryService with direct MongoDB access (motor async).
No round-trip through Kotlin — reads chat_messages + chat_summaries directly.

Two main operations:
1. assemble_context() — load messages + summaries, build LLM-ready message list
2. maybe_compress()   — compress old messages into summary blocks via LLM

Used by:
- ChatSession handler (foreground chat via local Ollama)
- Background orchestrator (replaces Kotlin prepareChatHistoryPayload)

MongoDB collections:
- chat_messages:   {conversationId, role, content, timestamp, sequence, correlationId, ...}
- chat_summaries:  {conversationId, sequenceStart, sequenceEnd, summary, keyDecisions, topics, ...}

Note: 'conversationId' is the ObjectId linking messages to a conversation thread.
For ChatSession, this is ChatSession._id. For background tasks, it's TaskDocument._id.

Hardening:
- W-15: Compression error handling — retry with marker, callback on done
- W-20: Sequence number race — atomic findOneAndUpdate counter
- W-10: Checkpoint message growth — truncate tool results in stored messages
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase

from app.config import settings

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

TOTAL_CONTEXT_WINDOW = 32_768          # Qwen3-30B default
SYSTEM_PROMPT_RESERVE = 2_000          # System prompt + tools
RESPONSE_RESERVE = 4_000               # Leave room for LLM response
CONTEXT_BUDGET = TOTAL_CONTEXT_WINDOW - SYSTEM_PROMPT_RESERVE - RESPONSE_RESERVE

RECENT_MESSAGE_COUNT = 20              # Max recent verbatim messages
MAX_SUMMARY_BLOCKS = 15                # Max compressed summary blocks to load
COMPRESS_THRESHOLD = 20                # Compress when >=N unsummarized messages
COMPRESS_MAX_RETRIES = 2               # W-15: Max compression retries on LLM failure
MAX_TOOL_RESULT_IN_MSG = 2000          # W-10: Max chars for tool results stored in messages

# Token estimation: chars/4 is rough but works for mixed cs/en text.
# TODO: Replace with tiktoken when model-specific tokenizer is available.
TOKEN_ESTIMATE_RATIO = 4


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

@dataclass
class ChatMessage:
    """Single message from chat_messages collection."""
    role: str          # "user" | "assistant" | "system"
    content: str
    timestamp: str     # ISO datetime
    sequence: int


@dataclass
class SummaryBlock:
    """Compressed summary from chat_summaries collection."""
    sequence_range: str       # "1-20"
    summary: str
    key_decisions: list[str] = field(default_factory=list)
    topics: list[str] = field(default_factory=list)
    is_checkpoint: bool = False
    checkpoint_reason: str | None = None


@dataclass
class AssembledContext:
    """Result of context assembly — ready for LLM consumption."""
    messages: list[dict]               # [{"role": ..., "content": ...}]
    total_tokens_estimate: int         # Estimated token count
    recent_message_count: int          # How many verbatim messages included
    summary_block_count: int           # How many summary blocks included
    total_db_messages: int             # Total messages in DB for this thread


# ---------------------------------------------------------------------------
# Error message filter (same logic as Kotlin ChatHistoryService)
# ---------------------------------------------------------------------------

def _is_error_message(content: str) -> bool:
    """Check if message content is an error that should be filtered out."""
    cl = content.strip().lower()
    if cl.startswith("{") and '"error"' in cl:
        return True
    if cl.startswith("error:") or cl.startswith("chyba:"):
        return True
    if "llm_call_failed" in cl or "operation not allowed" in cl:
        return True
    return False


# ---------------------------------------------------------------------------
# Token estimation
# ---------------------------------------------------------------------------

def estimate_tokens(text: str) -> int:
    """Estimate token count. chars/4 is rough but consistent."""
    return max(1, len(text) // TOKEN_ESTIMATE_RATIO)


# ---------------------------------------------------------------------------
# ChatContextAssembler
# ---------------------------------------------------------------------------

class ChatContextAssembler:
    """Builds token-budgeted LLM context from MongoDB chat history.

    Lifecycle:
    - Created once at app startup (singleton pattern via init/close)
    - assemble_context() called per-request
    - maybe_compress() called after each exchange (fire-and-forget)

    Thread safety: motor is async-safe, no shared mutable state.
    """

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._db: AsyncIOMotorDatabase | None = None

    async def init(self) -> None:
        """Initialize MongoDB connection."""
        self._client = AsyncIOMotorClient(settings.mongodb_url)
        self._db = self._client.get_database("jervis")
        logger.info("ChatContextAssembler initialized (MongoDB: jervis)")

    async def close(self) -> None:
        """Close MongoDB connection."""
        if self._client:
            self._client.close()
            self._client = None
            self._db = None
        logger.info("ChatContextAssembler closed")

    @property
    def db(self) -> AsyncIOMotorDatabase:
        if self._db is None:
            raise RuntimeError("ChatContextAssembler not initialized. Call init() first.")
        return self._db

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def assemble_context(
        self,
        conversation_id: str,
        *,
        context_budget: int = CONTEXT_BUDGET,
        memory_context: str = "",
    ) -> AssembledContext:
        """Build token-budgeted context for LLM.

        Priority order (highest to lowest):
        1. Memory context (user knowledge, preferences — always included)
        2. Compressed summaries (oldest first chronologically, fit budget)
        3. Recent messages (newest first selection, chronological output, fit remaining budget)

        Args:
            conversation_id: Conversation thread ID (ChatSession._id or TaskDocument._id as string)
            context_budget: Available tokens for context (default: CONTEXT_BUDGET)
            memory_context: Pre-built memory text (from KB/affairs, optional)

        Returns:
            AssembledContext with LLM-ready messages and metadata.
        """
        recent_messages = await self._load_recent_messages(conversation_id)
        summary_blocks = await self._load_summaries(conversation_id)
        total_count = await self._count_messages(conversation_id)

        remaining_budget = context_budget

        # Memory context (always included, deducted first)
        memory_tokens = estimate_tokens(memory_context) if memory_context else 0
        remaining_budget -= memory_tokens

        # Summaries — newest-first selection, fit max 60% of remaining
        included_summaries: list[SummaryBlock] = []
        summary_tokens = 0
        summary_budget = int(remaining_budget * 0.6)

        for block in reversed(summary_blocks):  # newest first
            block_tokens = estimate_tokens(block.summary)
            if summary_tokens + block_tokens > summary_budget:
                break
            included_summaries.insert(0, block)  # restore chronological order
            summary_tokens += block_tokens
        remaining_budget -= summary_tokens

        # Recent messages — newest-first selection, fit remaining budget
        included_messages: list[ChatMessage] = []
        message_tokens = 0

        for msg in reversed(recent_messages):  # newest first
            msg_tokens = estimate_tokens(msg.content) + 10  # +10 for role/formatting
            if message_tokens + msg_tokens > remaining_budget:
                break
            included_messages.insert(0, msg)  # restore chronological order
            message_tokens += msg_tokens

        # Build LLM message list
        llm_messages: list[dict] = []

        if included_summaries or memory_context:
            context_parts = []
            if memory_context:
                context_parts.append(f"=== Kontext uživatele ===\n{memory_context}")
            if included_summaries:
                context_parts.append("=== Historie konverzace (shrnutí) ===")
                for block in included_summaries:
                    parts = [f"[Zprávy {block.sequence_range}]: {block.summary}"]
                    if block.key_decisions:
                        parts.append(f"  Rozhodnutí: {', '.join(block.key_decisions)}")
                    if block.is_checkpoint:
                        parts.append(f"  ⚡ Změna směru: {block.checkpoint_reason or 'N/A'}")
                    context_parts.append("\n".join(parts))

            llm_messages.append({
                "role": "system",
                "content": "\n\n".join(context_parts),
            })

        for msg in included_messages:
            llm_messages.append({
                "role": msg.role,
                "content": msg.content,
            })

        total_tokens = memory_tokens + summary_tokens + message_tokens

        logger.info(
            "CONTEXT_ASSEMBLED | conversationId=%s | messages=%d/%d | summaries=%d/%d "
            "| tokens~%d/%d | totalDbMessages=%d",
            conversation_id,
            len(included_messages), len(recent_messages),
            len(included_summaries), len(summary_blocks),
            total_tokens, context_budget,
            total_count,
        )

        return AssembledContext(
            messages=llm_messages,
            total_tokens_estimate=total_tokens,
            recent_message_count=len(included_messages),
            summary_block_count=len(included_summaries),
            total_db_messages=total_count,
        )

    async def maybe_compress(
        self,
        conversation_id: str,
        done_callback: callable | None = None,
    ) -> bool:
        """Check if compression is needed and trigger it (fire-and-forget).

        Algorithm (same as Kotlin ChatHistoryService):
        1. If total messages <= RECENT_MESSAGE_COUNT -> skip
        2. Find last summarized sequence
        3. Count unsummarized messages before recent window
        4. If >= COMPRESS_THRESHOLD -> compress via LLM, save to MongoDB

        W-15: Added done_callback for completion notification and retry logic.

        Args:
            conversation_id: Conversation thread ID.
            done_callback: Optional async callable(conversation_id, success, error) invoked on completion/failure.

        Returns True if compression was triggered, False if skipped.
        """
        try:
            total = await self._count_messages(conversation_id)
            if total <= RECENT_MESSAGE_COUNT:
                return False

            last_summary = await self._get_last_summary(conversation_id)
            last_summarized_seq = last_summary["sequenceEnd"] if last_summary else 0

            all_messages = await self._load_all_messages(conversation_id)
            if not all_messages:
                return False

            recent_start = max(0, len(all_messages) - RECENT_MESSAGE_COUNT)
            unsummarized = [
                m for m in all_messages[:recent_start]
                if m.sequence > last_summarized_seq
            ]

            if len(unsummarized) < COMPRESS_THRESHOLD:
                return False

            logger.info("COMPRESS_START | conversationId=%s | unsummarized=%d", conversation_id, len(unsummarized))

            async def _compress_with_callback():
                try:
                    await self._compress_block(conversation_id, unsummarized, last_summary)
                    if done_callback:
                        await done_callback(conversation_id, True, None)
                except Exception as cb_err:
                    logger.warning("COMPRESS_CALLBACK_ERROR | conversationId=%s | %s", conversation_id, cb_err)
                    if done_callback:
                        await done_callback(conversation_id, False, str(cb_err))

            asyncio.create_task(_compress_with_callback())
            return True

        except Exception as e:
            logger.warning("COMPRESS_CHECK_FAILED | conversationId=%s | %s", conversation_id, e)
            return False

    async def prepare_payload_for_kotlin(self, conversation_id: str) -> dict | None:
        """Build ChatHistoryPayload compatible with existing Kotlin/Python interface.

        Transitional method — same JSON structure as ChatHistoryPayloadDto.
        Returns None if no messages exist.
        """
        recent_messages = await self._load_recent_messages(conversation_id)
        summary_blocks = await self._load_summaries(conversation_id)
        total_count = await self._count_messages(conversation_id)

        if not recent_messages and not summary_blocks:
            return None

        return {
            "recent_messages": [
                {
                    "role": m.role,
                    "content": m.content,
                    "timestamp": m.timestamp,
                    "sequence": m.sequence,
                }
                for m in recent_messages
            ],
            "summary_blocks": [
                {
                    "sequence_range": s.sequence_range,
                    "summary": s.summary,
                    "key_decisions": s.key_decisions,
                    "topics": s.topics,
                    "is_checkpoint": s.is_checkpoint,
                    "checkpoint_reason": s.checkpoint_reason,
                }
                for s in summary_blocks
            ],
            "total_message_count": total_count,
        }

    # ------------------------------------------------------------------
    # MongoDB reads
    # ------------------------------------------------------------------

    async def _load_recent_messages(
        self, conversation_id: str, limit: int = RECENT_MESSAGE_COUNT,
    ) -> list[ChatMessage]:
        """Load last N messages, filter out errors, ordered by sequence ASC."""
        from bson import ObjectId

        cursor = (
            self.db["chat_messages"]
            .find({"conversationId": ObjectId(conversation_id)})
            .sort("sequence", -1)
            .limit(limit)
        )

        docs = await cursor.to_list(length=limit)
        docs.reverse()  # newest-first -> chronological

        messages = []
        for doc in docs:
            content = doc.get("content", "")
            if _is_error_message(content):
                continue
            messages.append(ChatMessage(
                role=doc["role"].lower(),
                content=content,
                timestamp=doc.get("timestamp", datetime.now(timezone.utc)).isoformat(),
                sequence=doc.get("sequence", 0),
            ))

        return messages

    async def _load_all_messages(self, conversation_id: str) -> list[ChatMessage]:
        """Load ALL messages ordered by sequence ASC. Used for compression."""
        from bson import ObjectId

        cursor = (
            self.db["chat_messages"]
            .find({"conversationId": ObjectId(conversation_id)})
            .sort("sequence", 1)
        )

        messages = []
        async for doc in cursor:
            messages.append(ChatMessage(
                role=doc["role"].lower(),
                content=doc.get("content", ""),
                timestamp=doc.get("timestamp", datetime.now(timezone.utc)).isoformat(),
                sequence=doc.get("sequence", 0),
            ))

        return messages

    async def _load_summaries(
        self, conversation_id: str, limit: int = MAX_SUMMARY_BLOCKS,
    ) -> list[SummaryBlock]:
        """Load compressed summaries ordered by sequenceEnd ASC (oldest first)."""
        from bson import ObjectId

        cursor = (
            self.db["chat_summaries"]
            .find({"conversationId": ObjectId(conversation_id)})
            .sort("sequenceEnd", 1)
        )

        docs = await cursor.to_list(length=100)
        docs = docs[-limit:]  # take last N (most recent summaries)

        return [
            SummaryBlock(
                sequence_range=f"{doc['sequenceStart']}-{doc['sequenceEnd']}",
                summary=doc.get("summary", ""),
                key_decisions=doc.get("keyDecisions", []),
                topics=doc.get("topics", []),
                is_checkpoint=doc.get("isCheckpoint", False),
                checkpoint_reason=doc.get("checkpointReason"),
            )
            for doc in docs
        ]

    async def _count_messages(self, conversation_id: str) -> int:
        """Count total messages for a conversation thread."""
        from bson import ObjectId
        return await self.db["chat_messages"].count_documents({"conversationId": ObjectId(conversation_id)})

    async def _get_last_summary(self, conversation_id: str) -> dict | None:
        """Get the most recent summary (highest sequenceEnd)."""
        from bson import ObjectId

        return await self.db["chat_summaries"].find_one(
            {"conversationId": ObjectId(conversation_id)},
            sort=[("sequenceEnd", -1)],
        )

    # ------------------------------------------------------------------
    # Compression (LLM call + MongoDB write)
    # ------------------------------------------------------------------

    async def _compress_block(
        self,
        conversation_id: str,
        messages: list[ChatMessage],
        last_summary: dict | None,
    ) -> None:
        """Compress a block of messages into a summary via LLM.

        Called as fire-and-forget asyncio task. Errors are logged, not raised.
        W-15: Added retry logic — retries COMPRESS_MAX_RETRIES times on failure.
        On exhausted retries, saves a placeholder marker so the block isn't re-attempted.
        """
        from app.llm.provider import llm_provider
        from app.models import ModelTier

        formatted = []
        for m in messages:
            label = {"user": "Uživatel", "assistant": "Jervis"}.get(m.role, m.role)
            formatted.append(f"[{label}]: {m.content[:500]}")
        conversation_text = "\n".join(formatted)

        previous_context = ""
        if last_summary:
            previous_context = f"\n\nPředchozí kontext konverzace:\n{last_summary.get('summary', '')}"

        llm_messages = [
            {
                "role": "system",
                "content": (
                    "Jsi analytik konverzací. Tvůj úkol je shrnout blok konverzace do stručného souhrnu.\n\n"
                    "Pravidla:\n"
                    "- Piš česky\n"
                    "- Souhrn: 2-3 věty shrnující hlavní téma a průběh (max 500 znaků)\n"
                    "- Klíčová rozhodnutí: důležitá rozhodnutí učiněná v konverzaci\n"
                    "- Témata: hlavní témata diskuze (stručné štítky)\n"
                    "- Pokud se směr konverzace ZÁSADNĚ změnil oproti předchozímu kontextu, "
                    "nastav is_checkpoint=true a uveď důvod\n\n"
                    "Odpověz POUZE validním JSON (bez markdown backticks):\n"
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

        last_error = None
        for attempt in range(1, COMPRESS_MAX_RETRIES + 1):
            try:
                response = await llm_provider.completion(
                    messages=llm_messages,
                    tier=ModelTier.LOCAL_FAST,
                    max_tokens=2048,
                    temperature=0.1,
                )
                content = response.choices[0].message.content

                parsed = _parse_json_response(content)
                if not parsed:
                    logger.warning("COMPRESS_PARSE_FAILED | conversationId=%s | attempt=%d", conversation_id, attempt)
                    parsed = {"summary": content[:500]}

                await self._save_summary(
                    conversation_id=conversation_id,
                    sequence_start=messages[0].sequence,
                    sequence_end=messages[-1].sequence,
                    summary=parsed.get("summary", content[:500]),
                    key_decisions=parsed.get("key_decisions", []),
                    topics=parsed.get("topics", []),
                    is_checkpoint=parsed.get("is_checkpoint", False),
                    checkpoint_reason=parsed.get("checkpoint_reason"),
                    message_count=len(messages),
                )

                logger.info(
                    "COMPRESS_DONE | conversationId=%s | range=%d-%d | messages=%d | attempt=%d",
                    conversation_id,
                    messages[0].sequence, messages[-1].sequence,
                    len(messages), attempt,
                )
                return  # Success

            except Exception as e:
                last_error = e
                logger.warning(
                    "COMPRESS_RETRY | conversationId=%s | attempt=%d/%d | %s",
                    conversation_id, attempt, COMPRESS_MAX_RETRIES, e,
                )
                if attempt < COMPRESS_MAX_RETRIES:
                    await asyncio.sleep(2 ** attempt)  # Exponential backoff

        # W-15: All retries exhausted — save placeholder marker
        logger.error(
            "COMPRESS_EXHAUSTED | conversationId=%s | range=%d-%d | last_error=%s",
            conversation_id, messages[0].sequence, messages[-1].sequence, last_error,
        )
        await self._save_summary(
            conversation_id=conversation_id,
            sequence_start=messages[0].sequence,
            sequence_end=messages[-1].sequence,
            summary=f"[Compression failed after {COMPRESS_MAX_RETRIES} retries: {str(last_error)[:200]}]",
            key_decisions=[],
            topics=[],
            is_checkpoint=False,
            checkpoint_reason=None,
            message_count=len(messages),
        )

    async def _save_summary(
        self,
        conversation_id: str,
        sequence_start: int,
        sequence_end: int,
        summary: str,
        key_decisions: list[str],
        topics: list[str],
        is_checkpoint: bool,
        checkpoint_reason: str | None,
        message_count: int,
    ) -> None:
        """Save a compressed summary block to MongoDB."""
        from bson import ObjectId

        doc = {
            "conversationId": ObjectId(conversation_id),
            "sequenceStart": sequence_start,
            "sequenceEnd": sequence_end,
            "summary": summary,
            "keyDecisions": key_decisions,
            "topics": topics,
            "isCheckpoint": is_checkpoint,
            "checkpointReason": checkpoint_reason,
            "messageCount": message_count,
            "createdAt": datetime.now(timezone.utc),
        }

        await self.db["chat_summaries"].insert_one(doc)

    # ------------------------------------------------------------------
    # Message saving (for ChatSession handler)
    # ------------------------------------------------------------------

    async def save_message(
        self,
        conversation_id: str,
        role: str,
        content: str,
        correlation_id: str,
        sequence: int,
        metadata: dict[str, str] | None = None,
    ) -> None:
        """Save a chat message to MongoDB.

        W-10: Tool role messages have content truncated to MAX_TOOL_RESULT_IN_MSG
        to prevent unbounded checkpoint growth.
        """
        from bson import ObjectId

        # W-10: Truncate tool results to prevent checkpoint bloat
        saved_content = content
        if role.upper() == "TOOL" and len(content) > MAX_TOOL_RESULT_IN_MSG:
            saved_content = (
                content[:MAX_TOOL_RESULT_IN_MSG]
                + f"\n[... truncated {len(content) - MAX_TOOL_RESULT_IN_MSG} chars for storage]"
            )
            logger.debug(
                "TOOL_MSG_TRUNCATED | conversationId=%s | original=%d | stored=%d",
                conversation_id, len(content), len(saved_content),
            )

        doc = {
            "conversationId": ObjectId(conversation_id),
            "correlationId": correlation_id,
            "role": role.upper(),
            "content": saved_content,
            "timestamp": datetime.now(timezone.utc),
            "sequence": sequence,
            "metadata": metadata or {},
        }

        await self.db["chat_messages"].insert_one(doc)

    async def get_next_sequence(self, conversation_id: str) -> int:
        """Get the next sequence number for a conversation thread.

        W-20: Uses atomic findOneAndUpdate on a counter document
        to prevent race conditions with parallel writes.
        """
        from pymongo import ReturnDocument

        result = await self.db["chat_sequence_counters"].find_one_and_update(
            {"_id": f"seq_{conversation_id}"},
            {"$inc": {"counter": 1}},
            upsert=True,
            return_document=ReturnDocument.AFTER,
        )
        return result["counter"]


# ---------------------------------------------------------------------------
# JSON parsing helper
# ---------------------------------------------------------------------------

def _parse_json_response(content: str) -> dict | None:
    """Robustly parse JSON from LLM response."""
    try:
        return json.loads(content)
    except (json.JSONDecodeError, TypeError):
        pass

    match = re.search(r"```(?:json)?\s*\n?(.*?)\n?\s*```", content, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except (json.JSONDecodeError, TypeError):
            pass

    brace_start = content.find("{")
    brace_end = content.rfind("}")
    if brace_start != -1 and brace_end > brace_start:
        try:
            return json.loads(content[brace_start:brace_end + 1])
        except (json.JSONDecodeError, TypeError):
            pass

    return None


# ---------------------------------------------------------------------------
# Singleton
# ---------------------------------------------------------------------------

chat_context_assembler = ChatContextAssembler()
