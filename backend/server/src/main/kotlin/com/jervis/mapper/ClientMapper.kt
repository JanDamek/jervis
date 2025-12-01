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
        gitConfig = this.gitConfig?.toDto(),
        gitCredentials = gitCredentials,
        defaultLanguageEnum = this.defaultLanguageEnum,
        lastSelectedProjectId = this.lastSelectedProjectId?.toHexString(),
        connectionIds = this.connectionIds.map { it.toHexString() },
    )

fun ClientDto.toDocument(): ClientDocument =
    ClientDocument(
        id = ObjectId(this.id),
        name = this.name,
        gitProvider = this.gitProvider,
        gitAuthType = this.gitAuthType,
        gitConfig = this.gitConfig?.toDomain(),
        defaultLanguageEnum = this.defaultLanguageEnum,
        lastSelectedProjectId = this.lastSelectedProjectId?.let { ObjectId(it) },
        connectionIds = this.connectionIds.map { ObjectId(it) },
    )
