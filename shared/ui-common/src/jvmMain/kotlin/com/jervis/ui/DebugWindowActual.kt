package com.jervis.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime

/**
 * JVM implementation of currentLocalDateTime using java.time
 */
internal actual fun currentLocalDateTime(): LocalDateTime {
    return java.time.LocalDateTime.now().toKotlinLocalDateTime()
}
