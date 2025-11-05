package com.jervis.domain.confluence

import com.jervis.domain.MessageChannelEnum
import java.time.Instant

data class ChannelMappingEmbedded(
    val channel: MessageChannelEnum,
    val externalId: String,
    val externalThreadId: String? = null,
    val addedAt: Instant = Instant.now(),
)
