package com.jervis.repository.mongo

import com.jervis.entity.mongo.ProjectDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for project documents.
 */
@Repository
interface ProjectMongoRepository : CoroutineCrudRepository<ProjectDocument, String> {
    /**
     * Finds the project marked as active (default).
     */
    suspend fun findByIsActiveIsTrue(): ProjectDocument?
}
