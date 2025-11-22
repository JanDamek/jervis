package com.jervis.domain.qualifier

import com.jervis.dto.PendingTaskTypeEnum
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Repository for accessing QualifierRule documents in MongoDB.
 * Uses coroutine support for non-blocking reactive operations.
 */
interface QualifierRuleRepository : CoroutineCrudRepository<QualifierRule, ObjectId> {
    /**
     * Finds all qualifier rules for a specific task type.
     */
    suspend fun findByQualifierType(qualifierType: PendingTaskTypeEnum): List<QualifierRule>

    /**
     * Deletes all rules for a specific qualifier type.
     */
    suspend fun deleteByQualifierType(qualifierType: PendingTaskTypeEnum)

    /**
     * Counts rules for a specific qualifier type.
     */
    @Query("{ 'qualifierType': ?0 }")
    suspend fun countByQualifierType(qualifierType: PendingTaskTypeEnum): Long
}
