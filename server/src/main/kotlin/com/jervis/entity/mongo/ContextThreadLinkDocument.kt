package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Persistence document that links an external threadKey (email/slack/etc.) to a TaskContext.id.
 * This allows actionable notification threads to reuse the same TaskContext across events.
 */
@Document(collection = "context_thread_links")
data class ContextThreadLinkDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed(unique = true)
    val threadKey: String,
    @Indexed
    val contextId: ObjectId,
)
