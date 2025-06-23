package com.jervis.rag

import java.time.LocalDateTime

/**
 * Metadata for documents in the RAG system.
 * Contains information needed for search and filtering.
 */
data class RagMetadata(
    val type: DocumentType,
    val project: Int,  // project id or "global"

    // For chat messages
    val chatId: String? = null,
    val source: String,
    val sourceId: SourceType? = null,

    // Common attributes
    val timestamp: LocalDateTime? = null,
    val tags: List<String> = emptyList(),

    // For code
    val filePath: String? = null,
    val symbol: String? = null,
    val language: String? = null,

    // For chunking - tracking positions in the original document
    val chunkStart: Int? = null,  // Starting character index in the original document
    val chunkEnd: Int? = null,    // Ending character index in the original document
    val chunkIndex: Int? = null,  // Chunk sequence number

    // For tools and actions
    val actionType: ActionType? = null,
    val target: String? = null,  // email, slack username, etc.
    val triggerTime: LocalDateTime? = null,
    val status: ActionStatus? = null,

    // Author/owner
    val createdBy: String? = null,

    // Extra metadata - flexible dictionary for additional data
    val extra: Map<String, Any> = emptyMap()
) {
    /**
     * Converts metadata to a map, omitting null values.
     */
    fun toMap(): Map<String, Any> {
        return mapOf(
            "type" to type.name.lowercase(),
            "project" to project,
            "source" to source
        ) + listOfNotNull(
            chatId?.let { "chat_id" to it },
            sourceId?.let { "source_id" to it.name.lowercase() },
            timestamp?.let { "timestamp" to it },
            if (tags.isNotEmpty()) "tags" to tags else null,
            filePath?.let { "file_path" to it },
            symbol?.let { "symbol" to it },
            language?.let { "language" to it },
            chunkStart?.let { "chunk_start" to it },
            chunkEnd?.let { "chunk_end" to it },
            chunkIndex?.let { "chunk_index" to it },
            actionType?.let { "action_type" to it.name.lowercase() },
            target?.let { "target" to it },
            triggerTime?.let { "trigger_time" to it },
            status?.let { "status" to it.name.lowercase() },
            createdBy?.let { "created_by" to it }
        ).toMap() + if (extra.isNotEmpty()) extra else emptyMap()
    }
}

/**
 * Types of documents in the RAG system.
 */
enum class DocumentType {
    CHAT, CODE, TEXT, NOTE, MEETING, RULE, ACTION, SYSTEM, GIT_HISTORY, DEPENDENCY, TODO
}

/**
 * Types of sources for documents.
 */
enum class SourceType {
    USER, LLM, GUI, WHISPER, EMAIL, FILE, GIT, ANALYSIS
}

/**
 * Types of actions.
 */
enum class ActionType {
    NOTIFY, SEND_EMAIL, OPEN_CHAT
}

/**
 * Statuses for actions.
 */
enum class ActionStatus {
    SCHEDULED, COMPLETED, CANCELLED
}
