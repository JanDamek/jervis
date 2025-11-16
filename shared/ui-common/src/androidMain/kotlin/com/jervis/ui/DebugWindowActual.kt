package com.jervis.ui

import kotlinx.datetime.LocalDateTime

/**
 * Android implementation of currentLocalDateTime without requiring API 26 or experimental APIs
 */
internal actual fun currentLocalDateTime(): LocalDateTime {
    val cal = java.util.Calendar.getInstance()
    val year = cal.get(java.util.Calendar.YEAR)
    val month = cal.get(java.util.Calendar.MONTH) + 1 // Calendar months are 0-based
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = cal.get(java.util.Calendar.MINUTE)
    val second = cal.get(java.util.Calendar.SECOND)
    val nano = cal.get(java.util.Calendar.MILLISECOND) * 1_000_000
    return LocalDateTime(year, month, day, hour, minute, second, nano)
}
