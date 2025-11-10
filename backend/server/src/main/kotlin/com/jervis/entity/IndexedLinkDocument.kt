package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "indexed_links")
data class IndexedLinkDocument(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed(unique = true)
    val url: String,
    val lastIndexedAt: Instant = Instant.now(),
    val contentHash: String? = null,
)
