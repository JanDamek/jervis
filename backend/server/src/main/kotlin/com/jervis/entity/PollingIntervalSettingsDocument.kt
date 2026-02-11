package com.jervis.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Singleton MongoDB document for polling interval settings per capability.
 * Stores intervals in minutes keyed by ConnectionCapability name.
 *
 * Defaults:
 * - REPOSITORY: 30 min
 * - BUGTRACKER: 10 min
 * - WIKI: 60 min
 * - EMAIL_READ: 1 min
 */
@Document(collection = "polling_interval_settings")
data class PollingIntervalSettingsDocument(
    @Id
    val id: String = SINGLETON_ID,
    /** Capability name → interval in minutes */
    val intervals: Map<String, Int> = DEFAULT_INTERVALS,
) {
    companion object {
        const val SINGLETON_ID = "polling-intervals-global"
        val DEFAULT_INTERVALS = mapOf(
            "REPOSITORY" to 30,
            "BUGTRACKER" to 10,
            "WIKI" to 60,
            "EMAIL_READ" to 1,
        )
    }
}
