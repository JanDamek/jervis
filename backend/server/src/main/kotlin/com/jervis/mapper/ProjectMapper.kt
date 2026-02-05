package com.jervis.mapper

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.ProjectConnectionCapabilityDto
import com.jervis.dto.ProjectDto
import com.jervis.entity.GitCommitConfig
import com.jervis.entity.ProjectConnectionCapability
import com.jervis.entity.ProjectDocument
import org.bson.types.ObjectId

fun ProjectDocument.toDto(): ProjectDto =
    ProjectDto(
        id = this.id.toString(),
        clientId = this.clientId.toString(),
        name = this.name,
        description = this.description,
        communicationLanguageEnum = this.communicationLanguageEnum,
        gitRepositoryConnectionId = this.gitRepositoryConnectionId?.toString(),
        gitRepositoryIdentifier = this.gitRepositoryIdentifier,
        bugtrackerConnectionId = this.bugtrackerConnectionId?.toString(),
        bugtrackerProjectKey = this.bugtrackerProjectKey,
        wikiConnectionId = this.wikiConnectionId?.toString(),
        wikiSpaceKey = this.wikiSpaceKey,
        gitCommitMessageFormat = this.gitCommitConfig?.messageFormat,
        gitCommitAuthorName = this.gitCommitConfig?.authorName,
        gitCommitAuthorEmail = this.gitCommitConfig?.authorEmail,
        gitCommitCommitterName = this.gitCommitConfig?.committerName,
        gitCommitCommitterEmail = this.gitCommitConfig?.committerEmail,
        gitCommitGpgSign = this.gitCommitConfig?.gpgSign,
        gitCommitGpgKeyId = this.gitCommitConfig?.gpgKeyId,
        connectionCapabilities = this.connectionCapabilities.map { it.toDto() },
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
        gitRepositoryConnectionId = gitRepositoryConnectionId?.let { ObjectId(it) },
        gitRepositoryIdentifier = gitRepositoryIdentifier,
        bugtrackerConnectionId = bugtrackerConnectionId?.let { ObjectId(it) },
        bugtrackerProjectKey = bugtrackerProjectKey,
        wikiConnectionId = wikiConnectionId?.let { ObjectId(it) },
        wikiSpaceKey = wikiSpaceKey,
        buildConfig = null, // TODO: Map buildConfig
        costPolicy = com.jervis.entity.ProjectCostPolicy(),
        gitCommitConfig = gitCommitConfig,
        connectionCapabilities = this.connectionCapabilities.map { it.toEntity() },
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
