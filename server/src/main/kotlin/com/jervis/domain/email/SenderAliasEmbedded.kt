package com.jervis.domain.email

import java.time.Instant

data class SenderAliasEmbedded(
    val type: AliasTypeEnum,
    val value: String,
    val displayName: String? = null,
    val verified: Boolean = false,
    val firstSeenAt: Instant = Instant.now(),
    val lastSeenAt: Instant = Instant.now(),
)
