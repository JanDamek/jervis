package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Singleton MongoDB document for system-level configuration.
 * Stores brain connections (Jervis's own Jira + Confluence).
 */
@Document(collection = "system_config")
data class SystemConfigDocument(
    @Id
    val id: String = SINGLETON_ID,
    /** Project selected as JERVIS Internal (for orchestrator planning). */
    val jervisInternalProjectId: ObjectId? = null,
    /** Connection ID for Jervis's own bugtracker (Jira). */
    val brainBugtrackerConnectionId: ObjectId? = null,
    /** Jira project key for the brain project (e.g. "JERVIS"). */
    val brainBugtrackerProjectKey: String? = null,
    /** Connection ID for Jervis's own wiki (Confluence). */
    val brainWikiConnectionId: ObjectId? = null,
    /** Confluence space key for the brain wiki. */
    val brainWikiSpaceKey: String? = null,
    /** Confluence root page ID under which brain pages are created. */
    val brainWikiRootPageId: String? = null,
) {
    companion object {
        const val SINGLETON_ID = "system-config-global"
    }
}
