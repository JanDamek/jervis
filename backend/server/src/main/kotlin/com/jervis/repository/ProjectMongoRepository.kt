package com.jervis.repository

import com.jervis.entity.ProjectDocument
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for project documents.
 */
@Repository
interface ProjectMongoRepository : CoroutineCrudRepository<ProjectDocument, ObjectId> {
    /**
     * Finds the project marked as active (default).
     */
    suspend fun findByIsActiveIsTrue(): ProjectDocument?

    suspend fun findByName(name: String): ProjectDocument?
}
