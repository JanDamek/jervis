package com.jervis.knowledgebase.internal.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Mongo registry for graph entity aliasing.
 * Stores per-client mapping: aliasKey -> canonicalKey.
 */
@Document("graph_entity_registry")
@CompoundIndexes(
    CompoundIndex(name = "client_alias_unique", def = "{'clientId': 1, 'aliasKey': 1}", unique = true),
    CompoundIndex(name = "client_canonical_idx", def = "{'clientId': 1, 'canonicalKey': 1}"),
    CompoundIndex(name = "client_area_idx", def = "{'clientId': 1, 'area': 1}"),
)
internal data class GraphEntityRegistryDocument(
    @Id
    val id: ObjectId? = null,
    @Indexed
    val clientId: String,
    @Indexed
    val aliasKey: String,
    @Indexed
    val canonicalKey: String,
    @Indexed
    val area: String? = null,
    val firstSeenAt: Instant = Instant.now(),
    val lastSeenAt: Instant = Instant.now(),
    val seenCount: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
