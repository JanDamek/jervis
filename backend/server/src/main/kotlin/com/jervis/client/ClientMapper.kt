package com.jervis.client

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.client.ClientConnectionCapabilityDto
import com.jervis.dto.client.ClientDto
import com.jervis.client.ClientConnectionCapability
import com.jervis.client.ClientDocument
import com.jervis.infrastructure.llm.CloudModelPolicy
import com.jervis.project.GitCommitConfig
import org.bson.types.ObjectId

fun ClientDocument.toDto(): ClientDto =
    ClientDto(
        id = this.id.toString(),
        name = this.name,
        description = this.description,
        archived = this.archived,
        defaultLanguageEnum = this.defaultLanguageEnum,
        lastSelectedProjectId = this.lastSelectedProjectId?.toString(),
        defaultProjectId = this.defaultProjectId?.toString(),
        connectionIds = this.connectionIds.map { it.toString() },
        gitCommitMessageFormat = this.gitCommitConfig?.messageFormat,
        gitCommitMessagePattern = this.gitCommitConfig?.messagePattern,
        gitCommitAuthorName = this.gitCommitConfig?.authorName,
        gitCommitAuthorEmail = this.gitCommitConfig?.authorEmail,
        gitCommitCommitterName = this.gitCommitConfig?.committerName,
        gitCommitCommitterEmail = this.gitCommitConfig?.committerEmail,
        gitCommitGpgSign = this.gitCommitConfig?.gpgSign ?: false,
        gitCommitGpgKeyId = this.gitCommitConfig?.gpgKeyId,
        gitTopCommitters = this.gitTopCommitters,
        connectionCapabilities = this.connectionCapabilities.map { it.toDto() },
        autoUseAnthropic = this.cloudModelPolicy.autoUseAnthropic,
        autoUseOpenai = this.cloudModelPolicy.autoUseOpenai,
        autoUseGemini = this.cloudModelPolicy.autoUseGemini,
        maxOpenRouterTier = this.cloudModelPolicy.maxOpenRouterTier.name,
        reviewLanguage = this.reviewLanguage,
    )

fun ClientDto.toDocument(): ClientDocument {
    val gitCommitConfig =
        if (gitCommitMessageFormat != null || gitCommitMessagePattern != null ||
            gitCommitAuthorName != null || gitCommitAuthorEmail != null || gitCommitGpgSign
        ) {
            GitCommitConfig(
                messageFormat = gitCommitMessageFormat,
                messagePattern = gitCommitMessagePattern,
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

    val parsedId = try { ClientId(ObjectId(this.id)) } catch (_: Exception) { ClientId(ObjectId.get()) }

    return ClientDocument(
        id = parsedId,
        name = this.name,
        description = this.description,
        archived = this.archived,
        defaultLanguageEnum = this.defaultLanguageEnum,
        lastSelectedProjectId = this.lastSelectedProjectId?.let { ProjectId(ObjectId(it)) },
        defaultProjectId = this.defaultProjectId?.let { ProjectId(ObjectId(it)) },
        connectionIds = this.connectionIds.map { ObjectId(it) },
        gitCommitConfig = gitCommitConfig,
        gitTopCommitters = this.gitTopCommitters,
        connectionCapabilities = this.connectionCapabilities.map { it.toEntity() },
        cloudModelPolicy = CloudModelPolicy(
            autoUseAnthropic = this.autoUseAnthropic,
            autoUseOpenai = this.autoUseOpenai,
            autoUseGemini = this.autoUseGemini,
            maxOpenRouterTier = try { com.jervis.infrastructure.llm.OpenRouterTier.valueOf(this.maxOpenRouterTier) } catch (_: Exception) { com.jervis.infrastructure.llm.OpenRouterTier.NONE },
        ),
        reviewLanguage = this.reviewLanguage,
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
