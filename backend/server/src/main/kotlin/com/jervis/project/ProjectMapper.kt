package com.jervis.project

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.common.types.ProjectId
import com.jervis.dto.project.ProjectConnectionCapabilityDto
import com.jervis.dto.project.ProjectDto
import com.jervis.dto.project.ProjectResourceDto
import com.jervis.dto.project.ResourceLinkDto
import com.jervis.infrastructure.llm.CloudModelPolicy
import com.jervis.project.GitCommitConfig
import com.jervis.project.ProjectConnectionCapability
import com.jervis.project.ProjectDocument
import com.jervis.project.ProjectResource
import com.jervis.project.ResourceLink
import org.bson.types.ObjectId

fun ProjectDocument.toDto(): ProjectDto =
    ProjectDto(
        id = this.id.toString(),
        clientId = this.clientId.toString(),
        groupId = this.groupId?.toString(),
        name = this.name,
        description = this.description,
        communicationLanguageEnum = this.communicationLanguageEnum,
        gitCommitMessageFormat = this.gitCommitConfig?.messageFormat,
        gitCommitMessagePattern = this.gitCommitConfig?.messagePattern,
        gitCommitAuthorName = this.gitCommitConfig?.authorName,
        gitCommitAuthorEmail = this.gitCommitConfig?.authorEmail,
        gitCommitCommitterName = this.gitCommitConfig?.committerName,
        gitCommitCommitterEmail = this.gitCommitConfig?.committerEmail,
        gitCommitGpgSign = this.gitCommitConfig?.gpgSign,
        gitCommitGpgKeyId = this.gitCommitConfig?.gpgKeyId,
        connectionCapabilities = this.connectionCapabilities.map { it.toDto() },
        resources = this.resources.map { it.toDto() },
        resourceLinks = this.resourceLinks.map { it.toDto() },
        autoUseAnthropic = this.cloudModelPolicy?.autoUseAnthropic,
        autoUseOpenai = this.cloudModelPolicy?.autoUseOpenai,
        autoUseGemini = this.cloudModelPolicy?.autoUseGemini,
        maxOpenRouterTier = this.cloudModelPolicy?.maxOpenRouterTier?.name,
        reviewLanguage = this.reviewLanguage,
        isJervisInternal = this.isJervisInternal,
        active = this.active,
        workspaceStatus = this.workspaceStatus?.name,
        workspaceError = this.lastWorkspaceError,
        workspaceRetryCount = this.workspaceRetryCount,
        nextWorkspaceRetryAt = this.nextWorkspaceRetryAt?.toString(),
    )

fun ProjectDto.toDocument(): ProjectDocument {
    val resolvedId = if (ObjectId.isValid(this.id)) ObjectId(this.id) else ObjectId.get()
    val resolvedClientId = requireNotNull(this.clientId) { "clientId is required" }

    val gitCommitConfig =
        if (gitCommitMessageFormat != null || gitCommitMessagePattern != null ||
            gitCommitAuthorName != null || gitCommitAuthorEmail != null || gitCommitGpgSign != null
        ) {
            GitCommitConfig(
                messageFormat = gitCommitMessageFormat,
                messagePattern = gitCommitMessagePattern,
                authorName = gitCommitAuthorName,
                authorEmail = gitCommitAuthorEmail,
                committerName = gitCommitCommitterName,
                committerEmail = gitCommitCommitterEmail,
                gpgSign = gitCommitGpgSign ?: false,
                gpgKeyId = gitCommitGpgKeyId,
            )
        } else {
            null
        }

    return ProjectDocument(
        id = ProjectId(resolvedId),
        clientId = ClientId(ObjectId(resolvedClientId)),
        groupId = this.groupId?.let { ProjectGroupId(ObjectId(it)) },
        name = this.name,
        description = this.description,
        communicationLanguageEnum = this.communicationLanguageEnum,
        buildConfig = null, // TODO: Map buildConfig
        cloudModelPolicy = if (autoUseAnthropic != null || autoUseOpenai != null || autoUseGemini != null || maxOpenRouterTier != null) {
            CloudModelPolicy(
                autoUseAnthropic = autoUseAnthropic ?: false,
                autoUseOpenai = autoUseOpenai ?: false,
                autoUseGemini = autoUseGemini ?: false,
                maxOpenRouterTier = maxOpenRouterTier?.let {
                    try { com.jervis.infrastructure.llm.OpenRouterTier.valueOf(it) } catch (_: Exception) { com.jervis.infrastructure.llm.OpenRouterTier.NONE }
                } ?: com.jervis.infrastructure.llm.OpenRouterTier.NONE,
            )
        } else {
            null
        },
        gitCommitConfig = gitCommitConfig,
        reviewLanguage = this.reviewLanguage,
        connectionCapabilities = this.connectionCapabilities.map { it.toEntity() },
        resources = this.resources.map { it.toEntity() },
        resourceLinks = this.resourceLinks.map { it.toEntity() },
        active = this.active,
    )
}

fun ProjectConnectionCapability.toDto(): ProjectConnectionCapabilityDto =
    ProjectConnectionCapabilityDto(
        connectionId = this.connectionId.toString(),
        capability =
            com.jervis.dto.connection.ConnectionCapability
                .valueOf(this.capability.name),
        enabled = this.enabled,
        resourceIdentifier = this.resourceIdentifier,
        selectedResources = this.selectedResources,
    )

fun ProjectConnectionCapabilityDto.toEntity(): ProjectConnectionCapability =
    ProjectConnectionCapability(
        connectionId = ObjectId(this.connectionId),
        capability =
            com.jervis.dto.connection.ConnectionCapability
                .valueOf(this.capability.name),
        enabled = this.enabled,
        resourceIdentifier = this.resourceIdentifier,
        selectedResources = this.selectedResources,
    )

fun ProjectResource.toDto(): ProjectResourceDto =
    ProjectResourceDto(
        id = this.id,
        connectionId = this.connectionId.toString(),
        capability = com.jervis.dto.connection.ConnectionCapability.valueOf(this.capability.name),
        resourceIdentifier = this.resourceIdentifier,
        displayName = this.displayName,
    )

fun ProjectResourceDto.toEntity(): ProjectResource =
    ProjectResource(
        id = this.id.ifEmpty { ObjectId.get().toString() },
        connectionId = ObjectId(this.connectionId),
        capability = com.jervis.dto.connection.ConnectionCapability.valueOf(this.capability.name),
        resourceIdentifier = this.resourceIdentifier,
        displayName = this.displayName,
    )

fun ResourceLink.toDto(): ResourceLinkDto =
    ResourceLinkDto(sourceId = this.sourceId, targetId = this.targetId)

fun ResourceLinkDto.toEntity(): ResourceLink =
    ResourceLink(sourceId = this.sourceId, targetId = this.targetId)
