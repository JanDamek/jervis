package com.jervis.mapper

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatRequestContextDto

fun ChatRequestContextDto.toDomain() =
    ChatRequestContext(
        clientId = this.clientId,
        projectId = this.projectId,
        autoScope = this.autoScope,
        quick = this.quick,
        confirmedScope = this.confirmedScope,
        existingContextId = this.existingContextId,
    )
