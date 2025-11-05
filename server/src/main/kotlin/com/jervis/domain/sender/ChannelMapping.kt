package com.jervis.domain.sender

import com.jervis.domain.MessageChannelEnum
import java.time.Instant

data class ChannelMapping(
    val channel: MessageChannelEnum,
    val externalId: String,
    val externalThreadId: String?,
    val addedAt: Instant,
)
