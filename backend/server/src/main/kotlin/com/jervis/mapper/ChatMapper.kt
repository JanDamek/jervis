package com.jervis.mapper

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.domain.atlassian.classifyAttachmentType
import com.jervis.dto.AttachmentDto
import com.jervis.dto.ChatMessageDto
import com.jervis.entity.ChatMessageDocument

fun ChatMessageDocument.toDto(): ChatMessageDto = ChatMessageDto(
    role = this.role.name,
    content = this.content,
    timestamp = this.timestamp.toString(),
    correlationId = this.correlationId
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
