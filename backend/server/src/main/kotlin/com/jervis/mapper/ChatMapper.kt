package com.jervis.mapper

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.domain.atlassian.classifyAttachmentType
import com.jervis.dto.AttachmentDto
import com.jervis.dto.ChatMessageDto
import com.jervis.entity.ChatMessageDocument
import com.jervis.entity.MessageRole

fun ChatMessageDocument.toDto(): ChatMessageDto = ChatMessageDto(
    role = when (this.role) {
        MessageRole.USER -> com.jervis.dto.ChatRole.USER
        MessageRole.ASSISTANT -> com.jervis.dto.ChatRole.ASSISTANT
        MessageRole.SYSTEM -> com.jervis.dto.ChatRole.SYSTEM
    },
    content = this.content,
    timestamp = this.timestamp.toString(),
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
