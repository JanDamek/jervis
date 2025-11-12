package com.jervis.mapper

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatRequestContextDto

fun ChatRequestContextDto.toDomain() =
    ChatRequestContext(
        clientId = this.clientId,
        projectId = this.projectId,
        quick = this.quick,
    )
