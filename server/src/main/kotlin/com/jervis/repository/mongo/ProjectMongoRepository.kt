package com.jervis.repository.mongo

import com.jervis.entity.ProjectDocument
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono

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

    /**
     * Finds the project with oldest Git sync timestamp (or never synced).
     * Used for sequential Git synchronization scheduling.
     */
    fun findFirstByOrderByLastGitSyncAtAscCreatedAtAsc(): Mono<ProjectDocument>
}
