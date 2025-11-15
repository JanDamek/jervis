package com.jervis.ui

import kotlinx.datetime.*
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS/Native implementation of currentLocalDateTime
 */
@OptIn(kotlin.time.ExperimentalTime::class)
internal actual fun currentLocalDateTime(): LocalDateTime {
    val timestamp = NSDate().timeIntervalSince1970
    val instant = Instant.fromEpochSeconds(timestamp.toLong(), (timestamp % 1 * 1_000_000_000).toInt())
    return instant.toLocalDateTime(TimeZone.currentSystemDefault())
}
