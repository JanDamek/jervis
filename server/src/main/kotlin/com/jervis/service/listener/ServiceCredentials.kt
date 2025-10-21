package com.jervis.service.listener

import com.jervis.domain.authentication.ServiceTypeEnum
import java.time.Instant

/**
 * Ephemeral holder of service credentials/config derived from ClientDocument.
 * Not a MongoDB document. Built at runtime for polling/webhook orchestration.
 */
data class ServiceCredentials(
    val clientId: String,
    val projectId: String? = null,
    val serviceTypeEnum: ServiceTypeEnum,
    val isActive: Boolean = true,
    val lastUsedAt: Instant? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val webhookUrl: String? = null,
)
