package com.jervis.mapper

import com.jervis.domain.git.MonoRepoConfig
import com.jervis.dto.ClientDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.dto.MonoRepoConfigDto
import com.jervis.entity.ClientDocument
import org.bson.types.ObjectId

fun ClientDocument.toDto(gitCredentials: GitCredentialsDto? = null): ClientDto =
    ClientDto(
        id = this.id.toHexString(),
        name = this.name,
        gitProvider = this.gitProvider,
        gitAuthType = this.gitAuthType,
        monoRepoUrl = this.monoRepoUrl,
        monoRepos = this.monoRepos.map { it.toDto() },
        monoRepoCredentialsRef = null,
        defaultBranch = this.defaultBranch,
        gitConfig = this.gitConfig?.toDto(),
        gitCredentials = gitCredentials,
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        defaultLanguageEnum = this.defaultLanguageEnum,
        dependsOnProjects = this.dependsOnProjects.map { it.toHexString() },
        isDisabled = this.isDisabled,
        disabledProjects = this.disabledProjects.map { it.toHexString() },
        lastSelectedProjectId = this.lastSelectedProjectId?.toHexString(),
        connectionIds = this.connectionIds.map { it.toHexString() },
    )

fun MonoRepoConfig.toDto(): MonoRepoConfigDto =
    MonoRepoConfigDto(
        id = this.id,
        name = this.name,
        repositoryUrl = this.repositoryUrl,
        defaultBranch = this.defaultBranch,
        hasCredentialsOverride = this.credentialsOverride != null,
    )

fun ClientDto.toDocument(): ClientDocument =
    ClientDocument(
        id = ObjectId(this.id),
        name = this.name,
        gitProvider = this.gitProvider,
        gitAuthType = this.gitAuthType,
        monoRepoUrl = this.monoRepoUrl,
        monoRepos = this.monoRepos.map { it.toDomain() },
        defaultBranch = this.defaultBranch,
        gitConfig = this.gitConfig?.toDomain(),
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        defaultLanguageEnum = this.defaultLanguageEnum,
        dependsOnProjects = this.dependsOnProjects.map { ObjectId(it) },
        isDisabled = this.isDisabled,
        disabledProjects = this.disabledProjects.map { ObjectId(it) },
        lastSelectedProjectId = this.lastSelectedProjectId?.let { ObjectId(it) },
        connectionIds = this.connectionIds.map { ObjectId(it) },
    )

fun MonoRepoConfigDto.toDomain(): MonoRepoConfig =
    MonoRepoConfig(
        id = this.id,
        name = this.name,
        repositoryUrl = this.repositoryUrl,
        defaultBranch = this.defaultBranch,
        credentialsOverride = null,
    )
