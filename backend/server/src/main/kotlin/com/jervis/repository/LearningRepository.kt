package com.jervis.repository

import com.jervis.entity.LearningDocument
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for agent learning storage.
 */
@Repository
interface LearningRepository : CoroutineCrudRepository<LearningDocument, ObjectId> {
    /**
     * Find all valid learnings for a scope.
     */
    fun findByClientIdAndProjectIdAndIsValidTrue(
        clientId: ClientId?,
        projectId: ProjectId?,
    ): Flow<LearningDocument>

    /**
     * Find learnings by category.
     */
    fun findByCategoryAndIsValidTrue(category: String): Flow<LearningDocument>

    /**
     * Find global (universal) learnings.
     */
    fun findByClientIdIsNullAndProjectIdIsNullAndIsValidTrue(): Flow<LearningDocument>
}
