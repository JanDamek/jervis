package com.jervis.domain.email

data class CommunicationStatsEmbedded(
    val averageResponseTimeMs: Long? = null,
    val preferredChannel: String? = null,
    val typicalResponseDay: String? = null,
    val timezone: String? = null,
)
