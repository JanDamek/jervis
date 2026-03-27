package com.jervis.preferences

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Singleton MongoDB document for system-level configuration.
 */
@Document(collection = "system_config")
data class SystemConfigDocument(
    @Id
    val id: String = SINGLETON_ID,
    /** Project selected as JERVIS Internal (for orchestrator planning). */
    val jervisInternalProjectId: ObjectId? = null,
) {
    companion object {
        const val SINGLETON_ID = "system-config-global"
    }
}
