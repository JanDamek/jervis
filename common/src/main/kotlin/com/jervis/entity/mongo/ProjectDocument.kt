package com.jervis.entity.mongo

import com.jervis.domain.language.Language
import com.jervis.domain.project.IndexingRules
import com.jervis.domain.project.ProjectOverrides
import com.jervis.serialization.InstantSerializer
import com.jervis.serialization.ObjectIdSerializer
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "projects")
@Serializable
data class ProjectDocument(
    @Id
    @Serializable(with = ObjectIdSerializer::class)
    val id: ObjectId = ObjectId.get(),
    @Indexed
    @Serializable(with = ObjectIdSerializer::class)
    val clientId: ObjectId,
    @Indexed(unique = true)
    val name: String,
    val description: String? = null,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val projectPath: String = "",
    val meetingPath: String? = null,
    val audioPath: String? = null,
    val documentationPath: String? = null,
    val documentationUrls: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val communicationLanguage: Language = Language.getDefault(),
    val primaryUrl: String? = null,
    val extraUrls: List<String> = emptyList(),
    val credentialsRef: String? = null,
    val defaultBranch: String = "main",
    val overrides: ProjectOverrides = ProjectOverrides(),
    val inspirationOnly: Boolean = false,
    val indexingRules: IndexingRules = IndexingRules(),
    val dependsOnProjects: List<
        @Serializable(with = ObjectIdSerializer::class)
        ObjectId,
    > = emptyList(),
    val isDisabled: Boolean = false,
    val isActive: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant = Instant.now(),
)
