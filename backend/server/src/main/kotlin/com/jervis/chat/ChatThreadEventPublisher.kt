package com.jervis.chat

import com.jervis.dto.chat.ChatMessageDto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-thread broadcast hub for chat messages.
 *
 * Consumed by `IChatService.subscribeChatThread` and produced by every
 * insert into `chat_messages` (via `ChatMessageService.addMessage` /
 * `ChatMessageService.save`). The first event a subscriber sees is
 * [com.jervis.dto.chat.ChatThreadEvent.HistoryLoaded] from the loader
 * path; everything after is a single
 * [com.jervis.dto.chat.ChatThreadEvent.MessageAdded] per insert that
 * lands while the subscriber is connected.
 *
 * `threadId` is the `conversationId` field on `ChatMessageDocument`:
 * `ChatSessionDocument._id` for the foreground chat, `TaskDocument._id`
 * for task-scoped conversations.
 *
 * The flow uses `replay = 0` because the loader already provides the
 * snapshot — replaying buffered messages on subscribe would mean the
 * UI sees them twice (once from `HistoryLoaded`, once from the buffer).
 *
 * Per `architecture-push-only-streams.md` rule #9: every live UI surface
 * subscribes a single Flow<Snapshot or Delta> and never round-trips the
 * server with refresh / event→reload.
 */
@Component
class ChatThreadEventPublisher {
    private val logger = KotlinLogging.logger {}
    private val threadFlows = ConcurrentHashMap<ObjectId, MutableSharedFlow<ChatMessageDto>>()

    private fun flowFor(threadId: ObjectId): MutableSharedFlow<ChatMessageDto> =
        threadFlows.computeIfAbsent(threadId) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }

    /**
     * Emit a freshly-persisted chat message to subscribers of its thread.
     * No-op if no subscriber is currently connected (the lazy flow gets
     * GC-eligible once subscriberCount returns to 0 — currently retained
     * in the map; the leak is bounded by active conversation count).
     */
    fun broadcast(threadId: ObjectId, message: ChatMessageDto) {
        val flow = flowFor(threadId)
        val emitted = flow.tryEmit(message)
        if (!emitted) {
            // tryEmit returns false only when the buffer is full AND
            // BufferOverflow can't drop. We use DROP_OLDEST so this is
            // unreachable in practice — log defensively.
            logger.warn { "ChatThread broadcast dropped | threadId=$threadId messageId=${message.messageId}" }
        }
    }

    /**
     * Live message stream for a single thread. The caller is responsible
     * for first loading the snapshot via the repository, then merging
     * with this flow (filtering out anything older than `sinceMessageId`).
     */
    fun subscribe(threadId: ObjectId): SharedFlow<ChatMessageDto> = flowFor(threadId).asSharedFlow()

    /** Number of live subscribers — used by tests / metrics. */
    fun subscriberCount(threadId: ObjectId): Int =
        threadFlows[threadId]?.subscriptionCount?.value ?: 0
}
