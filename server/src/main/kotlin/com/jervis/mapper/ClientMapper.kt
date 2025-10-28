package com.jervis.mapper

import com.jervis.dto.ClientDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.entity.ClientDocument
import org.bson.types.ObjectId

fun ClientDocument.toDto(gitCredentials: GitCredentialsDto? = null): ClientDto =
    ClientDto(
        id = this.id.toHexString(),
        name = this.name,
        gitProvider = this.gitProvider,
        gitAuthType = this.gitAuthType,
        monoRepoUrl = this.monoRepoUrl,
        monoRepoCredentialsRef = null, // Deprecated - credentials now embedded in ClientDocument
        defaultBranch = this.defaultBranch,
        gitConfig = this.gitConfig?.toDto(),
        gitCredentials = gitCredentials,
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        defaultLanguageEnum = this.defaultLanguageEnum,
        dependsOnProjects = this.dependsOnProjects.map { it.toHexString() },
        isDisabled = this.isDisabled,
        disabledProjects = this.disabledProjects.map { it.toHexString() },
    )

fun ClientDto.toDocument(): ClientDocument =
    ClientDocument(
        id = ObjectId(this.id),
        name = this.name,
        gitProvider = this.gitProvider,
        gitAuthType = this.gitAuthType,
        monoRepoUrl = this.monoRepoUrl,
        defaultBranch = this.defaultBranch,
        gitConfig = this.gitConfig?.toDomain(),
        shortDescription = this.shortDescription,
        fullDescription = this.fullDescription,
        defaultLanguageEnum = this.defaultLanguageEnum,
        dependsOnProjects = this.dependsOnProjects.map { ObjectId(it) },
        isDisabled = this.isDisabled,
        disabledProjects = this.disabledProjects.map { ObjectId(it) },
    )
