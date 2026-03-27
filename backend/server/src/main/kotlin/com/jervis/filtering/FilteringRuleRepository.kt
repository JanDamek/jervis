package com.jervis.filtering

import com.jervis.filtering.FilteringRuleDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * EPIC 10: MongoDB repository for filtering rules.
 *
 * All filtering and ordering done in DB queries (not app-level filtering).
 */
@Repository
interface FilteringRuleRepository : CoroutineCrudRepository<FilteringRuleDocument, ObjectId> {

    /**
     * Find all enabled rules, ordered by creation date (newest first).
     * Used by listRules() for display.
     */
    fun findByEnabledTrueOrderByCreatedAtDesc(): Flow<FilteringRuleDocument>

    /**
     * Find enabled rules matching a specific source type.
     * Used by evaluate() for matching.
     */
    fun findByEnabledTrueAndSourceTypeInOrderByActionDesc(
        sourceTypes: Collection<String>,
    ): Flow<FilteringRuleDocument>

    /**
     * Find enabled rules for a specific client, ordered by creation date.
     */
    fun findByEnabledTrueAndClientIdOrderByCreatedAtDesc(
        clientId: String,
    ): Flow<FilteringRuleDocument>

    /**
     * Find enabled rules for a specific client + project.
     */
    fun findByEnabledTrueAndClientIdAndProjectIdOrderByCreatedAtDesc(
        clientId: String,
        projectId: String,
    ): Flow<FilteringRuleDocument>
}
