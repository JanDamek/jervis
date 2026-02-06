package com.jervis.mapper

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.ProjectConnectionCapabilityDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectResourceDto
import com.jervis.dto.ResourceLinkDto
import com.jervis.entity.GitCommitConfig
import com.jervis.entity.ProjectConnectionCapability
import com.jervis.entity.ProjectDocument
import com.jervis.entity.ProjectResource
import com.jervis.entity.ResourceLink
import org.bson.types.ObjectId

fun ProjectDocument.toDto(): ProjectDto =
    ProjectDto(
        id = this.id.toString(),
        clientId = this.clientId.toString(),
        name = this.name,
        description = this.description,
        communicationLanguageEnum = this.communicationLanguageEnum,
        gitCommitMessageFormat = this.gitCommitConfig?.messageFormat,
        gitCommitAuthorName = this.gitCommitConfig?.authorName,
        gitCommitAuthorEmail = this.gitCommitConfig?.authorEmail,
        gitCommitCommitterName = this.gitCommitConfig?.committerName,
        gitCommitCommitterEmail = this.gitCommitConfig?.committerEmail,
        gitCommitGpgSign = this.gitCommitConfig?.gpgSign,
        gitCommitGpgKeyId = this.gitCommitConfig?.gpgKeyId,
        connectionCapabilities = this.connectionCapabilities.map { it.toDto() },
        resources = this.resources.map { it.toDto() },
        resourceLinks = this.resourceLinks.map { it.toDto() },
    )

fun ProjectDto.toDocument(): ProjectDocument {
    val resolvedId = if (ObjectId.isValid(this.id)) ObjectId(this.id) else ObjectId.get()
    val resolvedClientId = requireNotNull(this.clientId) { "clientId is required" }

    val gitCommitConfig =
        if (gitCommitMessageFormat != null || gitCommitAuthorName != null ||
            gitCommitAuthorEmail != null || gitCommitGpgSign != null
        ) {
            GitCommitConfig(
                messageFormat = gitCommitMessageFormat,
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
        name = this.name,
        description = this.description,
        communicationLanguageEnum = this.communicationLanguageEnum,
        buildConfig = null, // TODO: Map buildConfig
        costPolicy = com.jervis.entity.ProjectCostPolicy(),
        gitCommitConfig = gitCommitConfig,
        connectionCapabilities = this.connectionCapabilities.map { it.toEntity() },
        resources = this.resources.map { it.toEntity() },
        resourceLinks = this.resourceLinks.map { it.toEntity() },
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
