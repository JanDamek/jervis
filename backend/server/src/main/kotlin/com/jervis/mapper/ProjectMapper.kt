package com.jervis.mapper

import com.jervis.dto.ProjectDto
import com.jervis.entity.ProjectDocument
import org.bson.types.ObjectId

fun ProjectDocument.toDto(): ProjectDto =
    ProjectDto(
        id = this.id.toHexString(),
        clientId = this.clientId.toHexString(),
        name = this.name,
        description = this.description,
        communicationLanguageEnum = this.communicationLanguageEnum,
        connectionIds = this.connectionIds.map { it.toHexString() },
    )

fun ProjectDto.toDocument(): ProjectDocument {
    val resolvedId = if (ObjectId.isValid(this.id)) ObjectId(this.id) else ObjectId.get()
    val resolvedClientId = requireNotNull(this.clientId) { "clientId is required" }
    return ProjectDocument(
        id = resolvedId,
        clientId = ObjectId(resolvedClientId),
        name = this.name,
        description = this.description,
        communicationLanguageEnum = this.communicationLanguageEnum,
        connectionIds = this.connectionIds.map { ObjectId(it) },
    )
}
