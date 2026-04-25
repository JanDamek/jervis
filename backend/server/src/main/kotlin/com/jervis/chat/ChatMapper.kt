package com.jervis.chat

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.domain.atlassian.classifyAttachmentType
import com.jervis.dto.chat.AttachmentDto
import com.jervis.dto.chat.ChatMessageDto

fun ChatMessageDocument.toDto(): ChatMessageDto = ChatMessageDto(
    role = when (this.role) {
        MessageRole.USER -> com.jervis.dto.chat.ChatRole.USER
        MessageRole.ASSISTANT -> com.jervis.dto.chat.ChatRole.ASSISTANT
        MessageRole.SYSTEM -> com.jervis.dto.chat.ChatRole.SYSTEM
        MessageRole.BACKGROUND -> com.jervis.dto.chat.ChatRole.BACKGROUND
        MessageRole.ALERT -> com.jervis.dto.chat.ChatRole.ALERT
    },
    content = this.content,
    requestTime = this.requestTime?.toString().orEmpty(),
    responseTime = this.responseTime?.toString().orEmpty(),
    correlationId = this.correlationId,
    metadata = this.metadata,
    sequence = this.sequence,
)

fun AttachmentDto.toDomain(): AttachmentMetadata = AttachmentMetadata(
    id = this.id,
    filename = this.filename,
    mimeType = this.mimeType,
    sizeBytes = this.sizeBytes,
    storagePath = this.storagePath,
    type = classifyAttachmentType(this.mimeType)
)

fun AttachmentMetadata.toDto(): AttachmentDto = AttachmentDto(
    id = this.id,
    filename = this.filename,
    mimeType = this.mimeType,
    sizeBytes = this.sizeBytes,
    storagePath = this.storagePath
)
