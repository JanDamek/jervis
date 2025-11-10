package com.jervis.repository.mongo

import com.jervis.entity.ProjectEvolutionDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ProjectEvolutionMongoRepository : CoroutineCrudRepository<ProjectEvolutionDocument, ObjectId> {
    /**
     * Find all evolution entries for a project, ordered chronologically.
     */
    fun findByProjectIdOrderByOrderAsc(projectId: ObjectId): Flow<ProjectEvolutionDocument>

    /**
     * Find evolution entry by commit hash.
     */
    suspend fun findByProjectIdAndCommitHash(
        projectId: ObjectId,
        commitHash: String,
    ): ProjectEvolutionDocument?

    /**
     * Get latest N evolution entries for a project.
     */
    fun findTop50ByProjectIdOrderByOrderDesc(projectId: ObjectId): Flow<ProjectEvolutionDocument>

    /**
     * Get count of evolution entries for a project.
     */
    suspend fun countByProjectId(projectId: ObjectId): Long
}
