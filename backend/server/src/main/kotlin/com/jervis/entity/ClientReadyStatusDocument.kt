package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document("client_bootstrap_status")
data class ClientReadyStatusDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val clientId: ObjectId,
    val clientSlug: String,
    val arangoOk: Boolean,
    val weaviateOk: Boolean,
    val arangoDetails: String? = null,
    val weaviateDetails: String? = null,
    val createdCollections: List<String> = emptyList(),
    val createdIndexes: List<String> = emptyList(),
    val createdGraph: Boolean = false,
    val timestamp: Instant = Instant.now(),
)
