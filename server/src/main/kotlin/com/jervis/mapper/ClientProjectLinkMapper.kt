package com.jervis.mapper

import com.jervis.dto.ClientProjectLinkDto
import com.jervis.entity.mongo.ClientProjectLinkDocument
import org.bson.types.ObjectId

fun ClientProjectLinkDocument.toDto(): ClientProjectLinkDto =
    ClientProjectLinkDto(
        id = this.id.toHexString(),
        clientId = this.clientId.toHexString(),
        projectId = this.projectId.toHexString(),
        isDisabled = this.isDisabled,
        anonymizationEnabled = this.anonymizationEnabled,
        historical = this.historical,
    )

fun ClientProjectLinkDto.toDocument(): ClientProjectLinkDocument =
    ClientProjectLinkDocument(
        id = ObjectId(this.id),
        clientId = ObjectId(this.clientId),
        projectId = ObjectId(this.projectId),
        isDisabled = this.isDisabled,
        anonymizationEnabled = this.anonymizationEnabled,
        historical = this.historical,
    )
