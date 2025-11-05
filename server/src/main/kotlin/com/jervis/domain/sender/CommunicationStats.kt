package com.jervis.domain.sender

data class CommunicationStats(
    val averageResponseTimeMs: Long?,
    val preferredChannel: String?,
    val typicalResponseDay: String?,
    val timezone: String?,
)
