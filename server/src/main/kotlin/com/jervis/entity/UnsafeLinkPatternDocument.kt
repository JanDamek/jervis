package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Learned regex patterns for UNSAFE link detection.
 * When LLM marks a link as UNSAFE and suggests regex, we store it here
 * and apply it in future classifications (before calling LLM).
 */
@Document(collection = "unsafe_link_patterns")
data class UnsafeLinkPatternDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val pattern: String, // Regex pattern (e.g., "/kod/[A-Za-z0-9]+")
    val description: String, // Human-readable description (e.g., "Action code links")
    val exampleUrl: String, // First URL that triggered this pattern
    val matchCount: Int = 1, // How many times this pattern has matched
    val createdAt: Instant = Instant.now(),
    val lastMatchedAt: Instant = Instant.now(),
    val enabled: Boolean = true, // Can be disabled if pattern causes false positives
)
