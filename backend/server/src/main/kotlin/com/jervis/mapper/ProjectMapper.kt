package com.jervis.mapper

import com.jervis.dto.ProjectDto
import com.jervis.entity.ProjectDocument
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId

fun ProjectDocument.toDto(): ProjectDto =
    ProjectDto(
        id = this.id.toString(),
        clientId = this.clientId.toString(),
        name = this.name,
        description = this.description,
        communicationLanguageEnum = this.communicationLanguageEnum,
        connectionIds = this.connectionIds.map { it.toString() },
    )

fun ProjectDto.toDocument(): ProjectDocument {
    val resolvedId = if (ObjectId.isValid(this.id)) ObjectId(this.id) else ObjectId.get()
    val resolvedClientId = requireNotNull(this.clientId) { "clientId is required" }
    return ProjectDocument(
        id = ProjectId(resolvedId),
        clientId = ClientId(ObjectId(resolvedClientId)),
        name = this.name,
        description = this.description,
        communicationLanguageEnum = this.communicationLanguageEnum,
        connectionIds = this.connectionIds.map { ObjectId(it) },
    )
}
