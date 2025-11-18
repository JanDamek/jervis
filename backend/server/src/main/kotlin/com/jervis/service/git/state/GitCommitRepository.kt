package com.jervis.service.git.state

import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface GitCommitRepository : CoroutineCrudRepository<GitCommitDocument, ObjectId> {
    fun findByProjectId(projectId: ObjectId): Flow<GitCommitDocument>

    fun findByProjectIdAndStateOrderByCommitDateAsc(
        projectId: ObjectId,
        state: GitCommitState,
    ): Flow<GitCommitDocument>

    fun findByClientIdAndMonoRepoId(
        clientId: ObjectId,
        monoRepoId: String,
    ): Flow<GitCommitDocument>

    fun findByClientIdAndMonoRepoIdAndStateOrderByCommitDateAsc(
        clientId: ObjectId,
        monoRepoId: String,
        state: GitCommitState,
    ): Flow<GitCommitDocument>

    // Global counts for UI overview
    @org.springframework.data.mongodb.repository.Query(value = "{ 'state': ?0 }", count = true)
    suspend fun countByState(state: GitCommitState): Long
}
