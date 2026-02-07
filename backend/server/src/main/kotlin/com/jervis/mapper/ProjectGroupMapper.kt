package com.jervis.mapper

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectGroupId
import com.jervis.dto.ProjectGroupDto
import com.jervis.entity.ProjectGroupDocument
import org.bson.types.ObjectId

fun ProjectGroupDocument.toDto(): ProjectGroupDto =
    ProjectGroupDto(
        id = this.id.toString(),
        clientId = this.clientId.toString(),
        name = this.name,
        description = this.description,
        connectionCapabilities = this.connectionCapabilities.map { it.toDto() },
        resources = this.resources.map { it.toDto() },
        resourceLinks = this.resourceLinks.map { it.toDto() },
    )

fun ProjectGroupDto.toDocument(): ProjectGroupDocument {
    val resolvedId = if (ObjectId.isValid(this.id)) ObjectId(this.id) else ObjectId.get()

    return ProjectGroupDocument(
        id = ProjectGroupId(resolvedId),
        clientId = ClientId(ObjectId(this.clientId)),
        name = this.name,
        description = this.description,
        connectionCapabilities = this.connectionCapabilities.map { it.toEntity() },
        resources = this.resources.map { it.toEntity() },
        resourceLinks = this.resourceLinks.map { it.toEntity() },
    )
}
