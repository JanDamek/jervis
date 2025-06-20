package com.jervis.module.memory

import com.jervis.entity.Project
import com.jervis.rag.Document
import com.jervis.rag.DocumentType
import com.jervis.rag.RagMetadata
import java.time.LocalDateTime
import java.util.UUID

/**
 * Enum representing the type of memory item.
 */
enum class MemoryItemType {
    MEETING_TRANSCRIPT,
    NOTE,
    DECISION,
    PLAN,
    HISTORY
}

/**
 * Utility class for working with memory items as Document objects.
 * This replaces the MemoryItem entity, using Qdrant as the sole storage.
 */
object MemoryDocument {
    /**
     * Create a Document representing a memory item.
     *
     * @param project The project the memory item belongs to
     * @param title The title of the memory item
     * @param content The content of the memory item
     * @param type The type of memory item
     * @param importance The importance of the memory item (1-10)
     * @param createdAt The creation timestamp
     * @param updatedAt The last update timestamp
     * @param metadata Additional metadata for the memory item
     * @return A Document representing the memory item
     */
    fun create(
        project: Project,
        title: String,
        content: String,
        type: MemoryItemType,
        importance: Int = 5,
        createdAt: LocalDateTime = LocalDateTime.now(),
        updatedAt: LocalDateTime = LocalDateTime.now(),
        metadata: Map<String, Any>? = null,
        createdBy: String? = null
    ): Document {
        val documentType = when (type) {
            MemoryItemType.MEETING_TRANSCRIPT -> DocumentType.MEETING
            MemoryItemType.NOTE -> DocumentType.NOTE
            MemoryItemType.DECISION, 
            MemoryItemType.PLAN,
            MemoryItemType.HISTORY -> DocumentType.TEXT
        }

        val id = UUID.randomUUID().toString()

        val ragMetadata = RagMetadata(
            type = documentType,
            project = project.id!!.toInt(),
            source = "memory",
            tags = listOf(type.name.lowercase()),
            timestamp = createdAt,
            createdBy = createdBy,
            extra = mapOf(
                "title" to title,
                "importance" to importance,
                "memory_type" to type.name,
                "memory_id" to id,
                "updated_at" to updatedAt
            ) + (metadata ?: emptyMap())
        )

        return Document(content, ragMetadata)
    }

    /**
     * Extract the memory item type from a Document.
     *
     * @param document The Document
     * @return The memory item type
     */
    fun getType(document: Document): MemoryItemType {
        val typeStr = document.metadata["memory_type"] as? String
            ?: return MemoryItemType.NOTE // Default to NOTE if not specified

        return try {
            MemoryItemType.valueOf(typeStr)
        } catch (e: IllegalArgumentException) {
            MemoryItemType.NOTE // Default to NOTE if invalid type
        }
    }

    /**
     * Extract the title from a Document.
     *
     * @param document The Document
     * @return The title
     */
    fun getTitle(document: Document): String {
        return document.metadata["title"] as? String ?: "Untitled"
    }

    /**
     * Extract the importance from a Document.
     *
     * @param document The Document
     * @return The importance
     */
    fun getImportance(document: Document): Int {
        return (document.metadata["importance"] as? Number)?.toInt() ?: 5
    }

    /**
     * Extract the creation timestamp from a Document.
     *
     * @param document The Document
     * @return The creation timestamp
     */
    fun getCreatedAt(document: Document): LocalDateTime {
        return document.metadata["timestamp"] as? LocalDateTime ?: LocalDateTime.now()
    }

    /**
     * Extract the update timestamp from a Document.
     *
     * @param document The Document
     * @return The update timestamp
     */
    fun getUpdatedAt(document: Document): LocalDateTime {
        return document.metadata["updated_at"] as? LocalDateTime ?: LocalDateTime.now()
    }

    /**
     * Extract the memory ID from a Document.
     *
     * @param document The Document
     * @return The memory ID
     */
    fun getId(document: Document): String {
        return document.metadata["memory_id"] as? String ?: ""
    }

    /**
     * Create a filter for searching memory items by project.
     *
     * @param project The project
     * @return A filter map
     */
    fun createProjectFilter(project: Project): Map<String, Any> {
        return mapOf(
            "project" to project.id!!.toInt(),
            "source" to "memory"
        )
    }

    /**
     * Create a filter for searching memory items by project and type.
     *
     * @param project The project
     * @param type The memory item type
     * @return A filter map
     */
    fun createTypeFilter(project: Project, type: MemoryItemType): Map<String, Any> {
        return mapOf(
            "project" to project.id!!.toInt(),
            "source" to "memory",
            "memory_type" to type.name
        )
    }
}
