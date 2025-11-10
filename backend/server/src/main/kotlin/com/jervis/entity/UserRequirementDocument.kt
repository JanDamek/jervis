package com.jervis.entity

import com.jervis.domain.requirement.RequirementPriorityEnum
import com.jervis.domain.requirement.RequirementStatusEnum
import com.jervis.domain.requirement.UserRequirement
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "user_requirements")
data class UserRequirementDocument(
    @Id val id: ObjectId = ObjectId(),
    val clientId: ObjectId,
    val projectId: ObjectId? = null,
    val title: String,
    val description: String,
    val keywords: List<String> = emptyList(),
    val priority: RequirementPriorityEnum = RequirementPriorityEnum.MEDIUM,
    val status: RequirementStatusEnum = RequirementStatusEnum.ACTIVE,
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    fun toDomain() =
        UserRequirement(
            id = id,
            clientId = clientId,
            projectId = projectId,
            title = title,
            description = description,
            keywords = keywords,
            priority = priority,
            status = status,
            createdAt = createdAt,
            completedAt = completedAt,
            metadata = metadata,
        )

    companion object {
        fun fromDomain(requirement: UserRequirement) =
            UserRequirementDocument(
                id = requirement.id,
                clientId = requirement.clientId,
                projectId = requirement.projectId,
                title = requirement.title,
                description = requirement.description,
                keywords = requirement.keywords,
                priority = requirement.priority,
                status = requirement.status,
                createdAt = requirement.createdAt,
                completedAt = requirement.completedAt,
                metadata = requirement.metadata,
            )
    }
}
