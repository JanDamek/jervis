package com.jervis.mapper

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.dto.ProjectGroupDto
import com.jervis.entity.CloudModelPolicy
import com.jervis.entity.OpenRouterTier
import com.jervis.entity.ProjectGroupDocument
import org.bson.types.ObjectId

fun ProjectGroupDocument.toDto(): ProjectGroupDto =
    ProjectGroupDto(
        id = this.id.toString(),
        clientId = this.clientId.toString(),
        name = this.name,
        description = this.description,
        connectionCapabilities = this.connectionCapabilities.map { it.toDto() },
        resources = this.resources.map { it.toDto() },
        resourceLinks = this.resourceLinks.map { it.toDto() },
        autoUseAnthropic = this.cloudModelPolicy?.autoUseAnthropic,
        autoUseOpenai = this.cloudModelPolicy?.autoUseOpenai,
        autoUseGemini = this.cloudModelPolicy?.autoUseGemini,
        maxOpenRouterTier = this.cloudModelPolicy?.maxOpenRouterTier?.name,
        reviewLanguage = this.reviewLanguage,
    )

fun ProjectGroupDto.toDocument(): ProjectGroupDocument {
    val resolvedId = if (ObjectId.isValid(this.id)) ObjectId(this.id) else ObjectId.get()

    // Build CloudModelPolicy if any override field is set
    val hasCloudPolicy = this.autoUseAnthropic != null || this.autoUseOpenai != null ||
        this.autoUseGemini != null || this.maxOpenRouterTier != null
    val cloudModelPolicy = if (hasCloudPolicy) {
        CloudModelPolicy(
            autoUseAnthropic = this.autoUseAnthropic ?: false,
            autoUseOpenai = this.autoUseOpenai ?: false,
            autoUseGemini = this.autoUseGemini ?: false,
            maxOpenRouterTier = this.maxOpenRouterTier?.let {
                try { OpenRouterTier.valueOf(it) } catch (_: Exception) { OpenRouterTier.FREE }
            } ?: OpenRouterTier.FREE,
        )
    } else null

    return ProjectGroupDocument(
        id = ProjectGroupId(resolvedId),
        clientId = ClientId(ObjectId(this.clientId)),
        name = this.name,
        description = this.description,
        connectionCapabilities = this.connectionCapabilities.map { it.toEntity() },
        resources = this.resources.map { it.toEntity() },
        resourceLinks = this.resourceLinks.map { it.toEntity() },
        cloudModelPolicy = cloudModelPolicy,
        reviewLanguage = this.reviewLanguage,
    )
}
