package com.jervis.entity

import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Agent learning storage - autonomous extraction and persistence of knowledge.
 *
 * Learning Scopes:
 * - GENERAL: Universal knowledge applicable everywhere (clientId = null, projectId = null)
 *   Examples: "Spring Boot apps use @Service annotation", "TypeScript supports union types"
 *
 * - CLIENT: Client-specific knowledge (projectId = null)
 *   Examples: "Client uses Czech language", "Client prefers functional programming"
 *
 * - PROJECT: Project-specific knowledge (both IDs set)
 *   Examples: "This project uses Kotlin coroutines", "Database is PostgreSQL 14"
 *
 * Learning Types:
 * - PATTERN: Successful solution pattern (e.g., "For X problem, use Y approach")
 * - FACT: Verified factual information (e.g., "API endpoint is /api/v1/users")
 * - CONSTRAINT: Limitation or requirement (e.g., "Never use reflection in service X")
 * - PREFERENCE: Inferred preference (e.g., "User prefers detailed explanations")
 * - TECHNIQUE: Proven technique or workflow (e.g., "Use TDD for critical business logic")
 */
@Document(collection = "agent_learning")
@CompoundIndexes(
    CompoundIndex(name = "scope_category", def = "{'clientId': 1, 'projectId': 1, 'category': 1}"),
    CompoundIndex(name = "type_confidence", def = "{'learningType': 1, 'confidence': -1}")
)
data class LearningDocument(
    @Id
    val id: ObjectId = ObjectId(),

    /**
     * Scope identifiers
     * - Both null: GENERAL (universal knowledge)
     * - clientId set, projectId null: CLIENT
     * - Both set: PROJECT
     */
    val clientId: ClientId? = null,
    val projectId: ProjectId? = null,

    /**
     * What was learned (human-readable summary).
     * Examples:
     * - "For OAuth2 integration, use Spring Security OAuth2 Client"
     * - "Database schema uses snake_case naming"
     * - "User prefers Czech responses"
     */
    val summary: String,

    /**
     * Detailed content (optional, for complex learnings).
     */
    val content: String? = null,

    /**
     * Learning category for organization.
     * Examples: "architecture", "database", "api", "language", "testing", "deployment"
     */
    @Indexed
    val category: String,

    /**
     * Type of learning.
     */
    @Indexed
    val learningType: LearningType,

    /**
     * Where agent learned this.
     */
    val source: LearningSource,

    /**
     * Confidence level (0.0 - 1.0).
     * - 1.0: Explicitly verified or stated by user
     * - 0.7-0.9: Strong evidence from multiple observations
     * - 0.5-0.6: Single observation or inference
     * - < 0.5: Tentative hypothesis (don't persist!)
     */
    val confidence: Double,

    /**
     * Task ID where this was learned (for traceability).
     */
    val sourceTaskId: String? = null,

    /**
     * Evidence supporting this learning (optional).
     * Examples: file paths, documentation URLs, conversation excerpts
     */
    val evidence: List<String> = emptyList(),

    /**
     * Tags for semantic search.
     */
    val tags: List<String> = emptyList(),

    /**
     * When this was learned.
     */
    @Indexed
    val learnedAt: Instant = Instant.now(),

    /**
     * Number of times this learning was confirmed/applied successfully.
     */
    val successCount: Int = 0,

    /**
     * Number of times this learning was contradicted/failed.
     */
    val failureCount: Int = 0,

    /**
     * Last time this learning was accessed/confirmed.
     */
    val lastUsedAt: Instant? = null,

    /**
     * Whether this learning is still valid (can be invalidated if contradicted).
     */
    val isValid: Boolean = true
)

enum class LearningType {
    PATTERN,        // Solution pattern
    FACT,           // Verified fact
    CONSTRAINT,     // Limitation or requirement
    PREFERENCE,     // User/project preference
    TECHNIQUE       // Proven workflow or technique
}

enum class LearningSource {
    TASK_SUCCESS,       // Learned from successful task completion
    USER_FEEDBACK,      // User explicitly stated or confirmed
    DOCUMENTATION,      // Extracted from docs/KB
    CODE_ANALYSIS,      // Discovered via code inspection
    ERROR_RESOLUTION,   // Learned from fixing errors
    PATTERN_RECOGNITION // Inferred from repeated patterns
}
