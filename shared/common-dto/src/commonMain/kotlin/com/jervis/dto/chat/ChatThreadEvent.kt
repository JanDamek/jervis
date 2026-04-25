package com.jervis.dto.chat

import kotlinx.serialization.Serializable

/**
 * Push event from `IChatService.subscribeChatThread`.
 *
 * Replaces the polling pattern (UI calls `getChatHistory` on every render)
 * with a single live stream:
 *  1. First emission is always [HistoryLoaded] — the full thread snapshot
 *     (or the slice newer than the optional `sinceMessageId` cursor).
 *  2. Every subsequent emission is one [MessageAdded] per insert into
 *     `chat_messages` for that conversation.
 *
 * The UI keeps an in-memory `SnapshotStateList<ChatMessage>` keyed on
 * `messageId` — `HistoryLoaded` resets it, `MessageAdded` appends. No
 * mutable updates of existing rows; per `architecture-push-only-streams.md`
 * + `feedback-no-quickfix` the chat bubble is immutable post-insert
 * (Fáze B handles the orchestrator-side persistence rule).
 */
@Serializable
sealed class ChatThreadEvent {
    /**
     * Initial full snapshot — sorted chronologically by
     * `effectiveTimestamp` (responseTime ?: requestTime).
     */
    @Serializable
    data class HistoryLoaded(
        val messages: List<ChatMessageDto>,
    ) : ChatThreadEvent()

    /**
     * One new message persisted post-subscribe. Subscribers append it
     * after the last item; sort key matches the snapshot.
     */
    @Serializable
    data class MessageAdded(
        val message: ChatMessageDto,
    ) : ChatThreadEvent()
}
