package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Cached UNSAFE link classifications to avoid redundant LLM calls.
 * When LLM marks a link as UNSAFE, we store it here to skip future qualifications.
 */
@Document(collection = "unsafe_links")
data class UnsafeLinkDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val url: String,
    val pattern: String,
    val reason: String,
)
