package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document representing a class summary.
 */
@Document(collection = "class_summaries")
data class ClassSummaryDocument(
    /**
     * Unique identifier for the document.
     */
    @Id
    val id: ObjectId? = null,
    /**
     * Project identifier this class belongs to.
     */
    val projectId: ObjectId,
    /**
     * Brief content summary of the class.
     */
    val contentSummary: String,
    /**
     * Name of the class.
     */
    val className: String,
    /**
     * File path where the class is located.
     */
    val filePath: String,
    /**
     * Package or namespace of the class.
     */
    val packageName: String,
    /**
     * List of methods in the class.
     */
    val methods: List<String>,
    /**
     * List of fields in the class.
     */
    val fields: List<String>,
    /**
     * List of interfaces implemented by the class.
     */
    val implementedInterfaces: List<String>,
    /**
     * Parent class if the class extends another class.
     */
    val parentClass: String? = null,
    /**
     * Visibility of the class (public, private, etc.).
     */
    val visibility: VisibilityEnum,
    /**
     * List of modifiers for the class (abstract, final, etc.).
     */
    val modifiers: List<String>,
    /**
     * Timestamp when the document was created.
     */
    val createdAt: Instant = Instant.now(),
    /**
     * Timestamp when the document was last updated.
     */
    val updatedAt: Instant = Instant.now(),
    /**
     * Additional metadata for the class summary.
     */
    val metadata: Map<String, Any> = emptyMap(),
) {
    enum class VisibilityEnum {
        PUBLIC,
        PRIVATE,
    }
}
