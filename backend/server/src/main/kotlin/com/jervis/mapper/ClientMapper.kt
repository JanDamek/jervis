package com.jervis.mapper

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.ClientConnectionCapabilityDto
import com.jervis.dto.ClientDto
import com.jervis.entity.ClientConnectionCapability
import com.jervis.entity.ClientDocument
import com.jervis.entity.CloudModelPolicy
import com.jervis.entity.GitCommitConfig
import org.bson.types.ObjectId

fun ClientDocument.toDto(): ClientDto =
    ClientDto(
        id = this.id.toString(),
        name = this.name,
        description = this.description,
        archived = this.archived,
        defaultLanguageEnum = this.defaultLanguageEnum,
        lastSelectedProjectId = this.lastSelectedProjectId?.toString(),
        connectionIds = this.connectionIds.map { it.toString() },
        gitCommitMessageFormat = this.gitCommitConfig?.messageFormat,
        gitCommitAuthorName = this.gitCommitConfig?.authorName,
        gitCommitAuthorEmail = this.gitCommitConfig?.authorEmail,
        gitCommitCommitterName = this.gitCommitConfig?.committerName,
        gitCommitCommitterEmail = this.gitCommitConfig?.committerEmail,
        gitCommitGpgSign = this.gitCommitConfig?.gpgSign ?: false,
        gitCommitGpgKeyId = this.gitCommitConfig?.gpgKeyId,
        connectionCapabilities = this.connectionCapabilities.map { it.toDto() },
        autoUseAnthropic = this.cloudModelPolicy.autoUseAnthropic,
        autoUseOpenai = this.cloudModelPolicy.autoUseOpenai,
        autoUseGemini = this.cloudModelPolicy.autoUseGemini,
    )

fun ClientDto.toDocument(): ClientDocument {
    val gitCommitConfig =
        if (gitCommitMessageFormat != null || gitCommitAuthorName != null ||
            gitCommitAuthorEmail != null || gitCommitGpgSign
        ) {
            GitCommitConfig(
                messageFormat = gitCommitMessageFormat,
                authorName = gitCommitAuthorName,
                authorEmail = gitCommitAuthorEmail,
                committerName = gitCommitCommitterName,
                committerEmail = gitCommitCommitterEmail,
                gpgSign = gitCommitGpgSign,
                gpgKeyId = gitCommitGpgKeyId,
            )
        } else {
            null
        }

    return ClientDocument(
        id = ClientId(ObjectId(this.id)),
        name = this.name,
        description = this.description,
        archived = this.archived,
        defaultLanguageEnum = this.defaultLanguageEnum,
        lastSelectedProjectId = this.lastSelectedProjectId?.let { ProjectId(ObjectId(it)) },
        connectionIds = this.connectionIds.map { ObjectId(it) },
        gitCommitConfig = gitCommitConfig,
        connectionCapabilities = this.connectionCapabilities.map { it.toEntity() },
        cloudModelPolicy = CloudModelPolicy(
            autoUseAnthropic = this.autoUseAnthropic,
            autoUseOpenai = this.autoUseOpenai,
            autoUseGemini = this.autoUseGemini,
        ),
    )
}

fun ClientConnectionCapability.toDto(): ClientConnectionCapabilityDto =
    ClientConnectionCapabilityDto(
        connectionId = this.connectionId.toString(),
        capability = this.capability,
        enabled = this.enabled,
        resourceIdentifier = this.resourceIdentifier,
        indexAllResources = this.indexAllResources,
        selectedResources = this.selectedResources,
    )

fun ClientConnectionCapabilityDto.toEntity(): ClientConnectionCapability =
    ClientConnectionCapability(
        connectionId = ObjectId(this.connectionId),
        capability = this.capability,
        enabled = this.enabled,
        resourceIdentifier = this.resourceIdentifier,
        indexAllResources = this.indexAllResources,
        selectedResources = this.selectedResources,
    )
