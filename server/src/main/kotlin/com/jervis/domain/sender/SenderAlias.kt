package com.jervis.domain.sender

import com.jervis.domain.email.AliasTypeEnum
import java.time.Instant

data class SenderAlias(
    val type: AliasTypeEnum,
    val value: String,
    val displayName: String?,
    val verified: Boolean,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
)
