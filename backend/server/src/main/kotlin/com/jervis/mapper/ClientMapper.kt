package com.jervis.mapper

import com.jervis.dto.ClientDto
import com.jervis.dto.GitCredentialsDto
import com.jervis.entity.ClientDocument
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId

fun ClientDocument.toDto(gitCredentials: GitCredentialsDto? = null): ClientDto =
    ClientDto(
        id = this.id.toString(),
        name = this.name,
        gitProvider = this.gitProvider,
        gitAuthType = this.gitAuthType,
        gitConfig = this.gitConfig?.toDto(),
        gitCredentials = gitCredentials,
        defaultLanguageEnum = this.defaultLanguageEnum,
        lastSelectedProjectId = this.lastSelectedProjectId?.toString(),
        connectionIds = this.connectionIds.map { it.toString() },
    )

fun ClientDto.toDocument(): ClientDocument =
    ClientDocument(
        id = ClientId(ObjectId(this.id)),
        name = this.name,
        gitProvider = this.gitProvider,
        gitAuthType = this.gitAuthType,
        gitConfig = this.gitConfig?.toDomain(),
        defaultLanguageEnum = this.defaultLanguageEnum,
        lastSelectedProjectId = this.lastSelectedProjectId?.let { ProjectId(ObjectId(it)) },
        connectionIds = this.connectionIds.map { ObjectId(it) },
    )
