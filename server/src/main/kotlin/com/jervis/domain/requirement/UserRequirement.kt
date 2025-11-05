package com.jervis.domain.requirement

import org.bson.types.ObjectId
import java.time.Instant

/**
 * User requirement/wish - something user wants to track or be notified about.
 * Example: "Find vacation in Spain under 500€", "Notify me about GPU RTX 4090 sales"
 */
data class UserRequirement(
    val id: ObjectId = ObjectId(),
    val clientId: ObjectId,
    val projectId: ObjectId? = null,
    val title: String,
    val description: String,
    val keywords: List<String> = emptyList(), // For matching (e.g., ["spain", "vacation", "beach"])
    val priority: RequirementPriorityEnum = RequirementPriorityEnum.MEDIUM,
    val status: RequirementStatusEnum = RequirementStatusEnum.ACTIVE,
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
)
