package com.jervis.ui.util

import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Czech day names for relative date display (within 7 days).
 */
private val CZECH_DAY_NAMES = arrayOf(
    "pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle",
)

/**
 * Format timestamp to human-readable Czech relative format.
 *
 * Rules:
 * - Today → "21:20"
 * - Yesterday → "včera 11:30"
 * - 2 days ago → "předevčírem 11:30"
 * - 3–7 days ago → "pondělí 11:30" (day name)
 * - Older → "8. 3. 2026 11:30" (date)
 */
fun formatMessageTime(isoTimestamp: String): String {
    val instant = try {
        Instant.parse(isoTimestamp)
    } catch (_: Exception) {
        // Try epoch millis (e.g. "1773234689203")
        try {
            val epochMs = isoTimestamp.trim().toLong()
            Instant.fromEpochMilliseconds(epochMs)
        } catch (_: Exception) {
            return isoTimestamp
        }
    }

    val tz = TimeZone.currentSystemDefault()
    val messageDateTime = instant.toLocalDateTime(tz)
    val nowDate = Clock.System.now().toLocalDateTime(tz).date

    val messageDate = messageDateTime.date
    val time = formatTime(messageDateTime.hour, messageDateTime.minute)
    val daysDiff = nowDate.toEpochDays() - messageDate.toEpochDays()

    return when {
        daysDiff == 0L -> time
        daysDiff == 1L -> "včera $time"
        daysDiff == 2L -> "předevčírem $time"
        daysDiff in 3L..7L -> "${dayName(messageDate)} $time"
        else -> "${messageDate.dayOfMonth}. ${messageDate.monthNumber}. ${messageDate.year} $time"
    }
}

private fun formatTime(hour: Int, minute: Int): String {
    val h = hour.toString().padStart(2, '0')
    val m = minute.toString().padStart(2, '0')
    return "$h:$m"
}

private fun dayName(date: LocalDate): String {
    // dayOfWeek: MONDAY=1 .. SUNDAY=7
    return CZECH_DAY_NAMES[date.dayOfWeek.ordinal]
}
