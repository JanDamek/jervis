package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document representing a project.
 */
@Document(collection = "projects")
data class ProjectDocument(
    /**
     * Unique identifier for the project.
     */
    @Id
    val id: ObjectId = ObjectId.get(),
    /**
     * Name of the project.
     */
    val name: String,
    /**
     * Path to the project directory.
     */
    val path: String,
    /**
     * Optional description of the project.
     */
    val description: String? = null,
    /**
     * Whether the project is currently active.
     */
    val isActive: Boolean = false,
    /**
     * Timestamp when the project was created.
     */
    val createdAt: Instant = Instant.now(),
    /**
     * Timestamp when the project was last updated.
     */
    val updatedAt: Instant = Instant.now(),
)
