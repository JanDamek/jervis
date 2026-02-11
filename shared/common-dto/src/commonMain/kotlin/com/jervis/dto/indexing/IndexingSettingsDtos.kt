package com.jervis.dto.indexing

import com.jervis.dto.connection.ConnectionCapability
import kotlinx.serialization.Serializable

/**
 * Polling interval settings per ConnectionCapability.
 *
 * Each capability has its own polling interval in minutes.
 * The CentralPoller uses these intervals to determine how often
 * to check for new items per capability type.
 *
 * Defaults:
 * - REPOSITORY (Git): 30 min
 * - WIKI: 60 min
 * - BUGTRACKER (Jira): 10 min
 * - EMAIL_READ: 1 min
 * - EMAIL_SEND: not polled (send-only)
 */
@Serializable
data class PollingIntervalSettingsDto(
    /** Interval per capability in minutes */
    val intervals: Map<ConnectionCapability, Int> = defaultIntervals(),
) {
    companion object {
        fun defaultIntervals(): Map<ConnectionCapability, Int> = mapOf(
            ConnectionCapability.REPOSITORY to 30,
            ConnectionCapability.BUGTRACKER to 10,
            ConnectionCapability.WIKI to 60,
            ConnectionCapability.EMAIL_READ to 1,
        )
    }
}

/**
 * Request DTO for updating polling interval settings.
 * Only provided entries are updated; missing capabilities keep their current value.
 */
@Serializable
data class PollingIntervalUpdateDto(
    /** Intervals to update (capability → minutes) */
    val intervals: Map<ConnectionCapability, Int>,
)
