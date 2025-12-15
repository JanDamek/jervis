package com.jervis.mapper

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatRequestContextDto
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId

fun ChatRequestContextDto.toDomain() =
    ChatRequestContext(
        clientId = ClientId(ObjectId(this.clientId)),
        projectId = ProjectId(ObjectId(this.projectId)),
    )
