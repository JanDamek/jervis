package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Association document between Client and Project with per-link settings.
 */
@Document(collection = "client_project_links")
@CompoundIndexes(
    CompoundIndex(name = "client_project_unique", def = "{ 'clientId': 1, 'projectId': 1 }", unique = true),
)
data class ClientProjectLinkDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    val clientId: ObjectId,
    val projectId: ObjectId,
    // Per-link flags
    val isDisabled: Boolean = false, // disable project usage for this client
    val anonymizationEnabled: Boolean = true, // apply anonymization when using this project for this client
    val historical: Boolean = false, // if true, do not take from RAG, but keep in awareness
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
