package com.jervis.entity.mongo

import com.jervis.domain.client.Anonymization
import com.jervis.domain.client.ClientTools
import com.jervis.domain.client.Formatting
import com.jervis.domain.client.Guidelines
import com.jervis.domain.client.InspirationPolicy
import com.jervis.domain.client.ReviewPolicy
import com.jervis.domain.client.SecretsPolicy
import com.jervis.domain.language.Language
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "clients")
data class ClientDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed(unique = true)
    val name: String,
    val description: String? = null,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val defaultCodingGuidelines: Guidelines = Guidelines(),
    val defaultReviewPolicy: ReviewPolicy = ReviewPolicy(),
    val defaultFormatting: Formatting = Formatting(),
    val defaultSecretsPolicy: SecretsPolicy = SecretsPolicy(),
    val defaultAnonymization: Anonymization = Anonymization(),
    val defaultInspirationPolicy: InspirationPolicy = InspirationPolicy(),
    val tools: ClientTools = ClientTools(),
    val defaultLanguage: Language = Language.getDefault(), // Default communication language for this client
    val dependsOnProjects: List<ObjectId> = emptyList(),
    val isDisabled: Boolean = false,
    val disabledProjects: List<ObjectId> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
