package com.jervis.service.listener.domain

/**
 * Represents an attachment from an external service
 */
data class ServiceAttachment(
    val id: String,
    val name: String,
    val contentType: String?,
    val size: Long?,
    val url: String?,
)
