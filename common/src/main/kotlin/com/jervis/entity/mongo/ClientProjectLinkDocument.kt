package com.jervis.entity.mongo

import com.jervis.serialization.InstantSerializer
import com.jervis.serialization.ObjectIdSerializer
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "client_project_links")
@CompoundIndexes(
    CompoundIndex(name = "client_project_unique", def = "{ 'clientId': 1, 'projectId': 1 }", unique = true),
)
@Serializable
data class ClientProjectLinkDocument(
    @Id
    @Serializable(with = ObjectIdSerializer::class)
    val id: ObjectId = ObjectId.get(),
    @Serializable(with = ObjectIdSerializer::class)
    val clientId: ObjectId,
    @Serializable(with = ObjectIdSerializer::class)
    val projectId: ObjectId,
    val isDisabled: Boolean = false,
    val anonymizationEnabled: Boolean = true,
    val historical: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant = Instant.now(),
)
