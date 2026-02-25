package com.jervis.dto.chat

import kotlinx.serialization.Serializable

/**
 * EPIC 9: Chat Intelligence & Memory Enhancement DTOs.
 *
 * Supports topic tracking, conversation memory consolidation,
 * intent decomposition, and action memory.
 */

/**
 * A detected topic in a conversation.
 */
@Serializable
data class ConversationTopic(
    val id: String,
    val label: String,
    val firstMentionedAt: String,
    val lastMentionedAt: String,
    val messageCount: Int = 1,
    val relatedEntities: List<String> = emptyList(),
)

/**
 * Topic-aware conversation summary (replaces rolling block summaries).
 */
@Serializable
data class TopicSummary(
    val topicId: String,
    val topicLabel: String,
    val summary: String,
    val keyDecisions: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val lastUpdatedAt: String,
)

/**
 * Action memory entry — records what JERVIS did for the user.
 */
@Serializable
data class ActionLogEntry(
    val id: String,
    val action: String,
    val description: String,
    val result: String,
    val timestamp: String,
    val projectId: String? = null,
    val clientId: String,
    val relatedTaskId: String? = null,
    val artifactIds: List<String> = emptyList(),
)

/**
 * Multi-intent decomposition result.
 */
@Serializable
data class DecomposedIntent(
    val intents: List<SingleIntent>,
    val originalMessage: String,
)

@Serializable
data class SingleIntent(
    val intent: String,
    val action: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val dependsOn: List<Int> = emptyList(),
)
