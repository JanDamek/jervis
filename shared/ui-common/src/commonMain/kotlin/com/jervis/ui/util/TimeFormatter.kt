package com.jervis.ui.util

import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Format ISO-8601 timestamp to human-readable Czech format.
 * - Today → "HH:mm"
 * - Yesterday → "Včera HH:mm"
 * - This year → "d. M. HH:mm"
 * - Older → "d. M. yyyy HH:mm"
 */
fun formatMessageTime(isoTimestamp: String): String {
    val instant = try {
        Instant.parse(isoTimestamp)
    } catch (_: Exception) {
        // Try parsing as epoch seconds or other formats
        return isoTimestamp
    }

    val tz = TimeZone.currentSystemDefault()
    val messageDateTime = instant.toLocalDateTime(tz)
    val nowDateTime = Clock.System.now().toLocalDateTime(tz)

    val messageDate = messageDateTime.date
    val nowDate = nowDateTime.date

    val h = messageDateTime.hour.toString().padStart(2, '0')
    val m = messageDateTime.minute.toString().padStart(2, '0')
    val time = "$h:$m"

    return when {
        messageDate == nowDate -> time
        messageDate.toEpochDays() == nowDate.toEpochDays() - 1 -> "Včera $time"
        messageDate.year == nowDate.year -> "${messageDate.dayOfMonth}. ${messageDate.monthNumber}. $time"
        else -> "${messageDate.dayOfMonth}. ${messageDate.monthNumber}. ${messageDate.year} $time"
    }
}
