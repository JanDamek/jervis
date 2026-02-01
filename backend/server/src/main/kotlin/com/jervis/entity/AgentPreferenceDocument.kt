package com.jervis.entity

import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Agent preferences - self-modifiable configuration that agents can read and update.
 *
 * Scopes:
 * - GLOBAL: System-wide preferences (clientId = null, projectId = null)
 * - CLIENT: Client-specific preferences (projectId = null)
 * - PROJECT: Project-specific preferences
 *
 * Examples:
 * - preferredLanguage: "cs", "en"
 * - detailLevel: "concise", "detailed", "technical"
 * - codingStyle: "functional", "object-oriented"
 * - testStrategy: "unit-first", "integration-first"
 * - errorHandling: "verbose", "minimal"
 * - maxIterations: "5", "10", "20"
 */
@Document(collection = "agent_preferences")
@CompoundIndexes(
    CompoundIndex(name = "scope_key", def = "{'clientId': 1, 'projectId': 1, 'key': 1}", unique = true)
)
data class AgentPreferenceDocument(
    @Id
    val id: ObjectId = ObjectId(),

    /**
     * Scope identifiers (null = higher scope)
     * - Both null: GLOBAL
     * - clientId set, projectId null: CLIENT
     * - Both set: PROJECT
     */
    val clientId: ClientId? = null,
    val projectId: ProjectId? = null,

    /**
     * Preference key (e.g., "preferredLanguage", "detailLevel")
     */
    val key: String,

    /**
     * Preference value (stored as string, agents parse as needed)
     */
    val value: String,

    /**
     * Optional description/reason for this preference
     */
    val description: String? = null,

    /**
     * How agent learned this preference
     */
    val source: PreferenceSource,

    /**
     * Confidence level (0.0 - 1.0)
     * - 1.0: Explicitly set by user
     * - 0.5-0.9: Learned from patterns
     * - < 0.5: Tentative inference
     */
    val confidence: Double = 1.0,

    /**
     * When this preference was created
     */
    val createdAt: Instant = Instant.now(),

    /**
     * When this preference was last updated
     */
    val updatedAt: Instant = Instant.now(),

    /**
     * Number of times this preference was used
     */
    val usageCount: Int = 0,

    /**
     * Last time this preference was accessed
     */
    val lastUsedAt: Instant? = null
)

enum class PreferenceSource {
    USER_EXPLICIT,      // User explicitly set this preference
    USER_IMPLICIT,      // Inferred from user behavior
    AGENT_LEARNED,      // Agent learned from successful patterns
    SYSTEM_DEFAULT      // System default value
}
