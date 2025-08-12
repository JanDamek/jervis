package com.jervis.events

import org.springframework.context.ApplicationEvent

/**
 * Event class for settings changes
 */
class SettingsChangeEvent(
    source: Any,
    val changedSettings: Set<String> = emptySet(),
    val changeType: ChangeType = ChangeType.GENERAL
) : ApplicationEvent(source) {
    
    enum class ChangeType {
        GENERAL,
        MODEL_SETTINGS,
        API_SETTINGS,
        EXTERNAL_MODEL_SETTINGS,
        EMBEDDING_SETTINGS
    }
}
