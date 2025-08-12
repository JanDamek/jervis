package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document representing git history information.
 * This document stores metadata about git commits that are indexed in the vector database.
 * The full content is stored only in the vector database, not in MongoDB.
 */
@Document(collection = "git_history")
data class GitHistoryDocument(
    /**
     * Unique identifier for the git history document, matches the ID in the vector database.
     */
    @Id
    val id: ObjectId? = null,
    /**
     * ID of the project this git history belongs to.
     */
    val projectId: ObjectId,
    /**
     * Brief summary or description of the git history content.
     */
    val contentSummary: String,
    /**
     * Commit hash of the git commit.
     */
    val commitHash: String,
    /**
     * Author of the git commit.
     */
    val author: String,
    /**
     * Email of the author.
     */
    val authorEmail: String? = null,
    /**
     * Timestamp when the commit was created.
     */
    val commitTime: Instant,
    /**
     * Files changed in the commit.
     */
    val filesChanged: List<String> = emptyList(),
    /**
     * Number of lines added in the commit.
     */
    val linesAdded: Int? = null,
    /**
     * Number of lines deleted in the commit.
     */
    val linesDeleted: Int? = null,
    /**
     * Branch where the commit was made.
     */
    val branch: String? = null,
    /**
     * Tags associated with the commit.
     */
    val tags: List<String> = emptyList(),
    /**
     * Timestamp when the document was created.
     */
    val createdAt: Instant = Instant.now(),
    /**
     * Timestamp when the document was last updated.
     */
    val updatedAt: Instant = Instant.now(),
    /**
     * Additional metadata as key-value pairs.
     */
    val metadata: Map<String, Any> = emptyMap(),
)
