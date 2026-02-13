package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class AttachmentDto(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String = "",
    val contentBase64: String? = null,
)
