package com.jervis.entity.mongo

import com.jervis.domain.client.Anonymization
import com.jervis.domain.client.ClientTools
import com.jervis.domain.client.Formatting
import com.jervis.domain.client.Guidelines
import com.jervis.domain.client.InspirationPolicy
import com.jervis.domain.client.ReviewPolicy
import com.jervis.domain.client.SecretsPolicy
import com.jervis.domain.language.Language
import com.jervis.serialization.InstantSerializer
import com.jervis.serialization.ObjectIdSerializer
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "clients")
@Serializable
data class ClientDocument(
    @Id
    @Serializable(with = ObjectIdSerializer::class)
    val id: ObjectId = ObjectId.get(),
    @Indexed(unique = true)
    val name: String,
    val description: String? = null,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val audioPath: String? = null,
    val defaultCodingGuidelines: Guidelines = Guidelines(),
    val defaultReviewPolicy: ReviewPolicy = ReviewPolicy(),
    val defaultFormatting: Formatting = Formatting(),
    val defaultSecretsPolicy: SecretsPolicy = SecretsPolicy(),
    val defaultAnonymization: Anonymization = Anonymization(),
    val defaultInspirationPolicy: InspirationPolicy = InspirationPolicy(),
    val tools: ClientTools = ClientTools(),
    val defaultLanguage: Language = Language.getDefault(),
    val dependsOnProjects: List<
        @Serializable(with = ObjectIdSerializer::class)
        ObjectId,
    > = emptyList(),
    val isDisabled: Boolean = false,
    val disabledProjects: List<
        @Serializable(with = ObjectIdSerializer::class)
        ObjectId,
    > = emptyList(),
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant = Instant.now(),
)
